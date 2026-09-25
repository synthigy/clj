(ns synthigy.client.login-test
  (:require [babashka.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [synthigy.client :as client]
            [synthigy.client.core :as core]
            [synthigy.client.login :as login])
  (:import [com.sun.net.httpserver HttpHandler HttpServer]
           [java.net InetSocketAddress URI URLDecoder]
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(defn parse-query
  [s]
  (into {} (for [kv (str/split (or s "") #"&") :when (seq kv)
                 :let [[k v] (str/split kv #"=" 2)]]
             [(URLDecoder/decode k "UTF-8") (URLDecoder/decode (or v "") "UTF-8")])))

(defn stub-server
  "Real HTTP server on an ephemeral port answering /oauth/token with (respond form)."
  [respond]
  (let [requests (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext server "/oauth/token"
                    (reify HttpHandler
                      (handle [_ ex]
                        (let [form (parse-query (slurp (.getRequestBody ex)))
                              {:keys [status body]} (respond form)
                              bytes (.getBytes ^String body StandardCharsets/UTF_8)]
                          (swap! requests conj {:form form
                                                :authorization (.getFirst (.getRequestHeaders ex) "Authorization")})
                          (.sendResponseHeaders ex status (alength bytes))
                          (with-open [o (.getResponseBody ex)] (.write o bytes))))))
    (.start server)
    {:server server
     :requests requests
     :endpoint (str "http://127.0.0.1:" (.getPort (.getAddress server)))}))

(defn jwt
  [claims]
  (str "e30." (login/b64url-encode (.getBytes (json/write-str claims) StandardCharsets/UTF_8)) ".sig"))

(defn make-client
  [endpoint & {:as extra}]
  (core/create-client (merge {:endpoint endpoint :token "static"
                              :client-id "bff" :client-secret "s3cret"
                              :login-store (login/memory-login-store)}
                             extra)))

(defn start-params
  [url]
  (parse-query (.getRawQuery (URI. url))))

(defmacro with-stub
  [[binding respond] & body]
  `(let [~binding (stub-server ~respond)]
     (try ~@body (finally (.stop ^HttpServer (:server ~binding) 0)))))

(defn ok-exchange
  [nonce-of]
  (fn [form]
    {:status 200
     :body (json/write-str {:access_token "at" :scope "openid profile"
                            :id_token (jwt {:sub "alice" :xid "x-alice" :nonce (nonce-of form)})})}))

(deftest guards
  (testing "confidential client required"
    (binding [core/*client* (core/create-client {:endpoint "http://x" :token "t"})]
      (is (= "LOGIN_REQUIRES_CONFIDENTIAL_CLIENT"
             (:code (ex-data (is (thrown? Exception (client/login-start "http://app/cb")))))))))
  (testing "no store → NO_LOGIN_STORE"
    (binding [core/*client* (core/create-client {:endpoint "http://x" :token "t"
                                                 :client-id "bff" :client-secret "s"})]
      (is (= "NO_LOGIN_STORE"
             (:code (ex-data (is (thrown? Exception (client/login-start "http://app/cb")))))))))
  (testing ":login-store is a known option; the secret never prints"
    (let [c (make-client "http://x")]
      (is (not (str/includes? (pr-str c) "s3cret"))))))

(deftest start-builds-authorize-url
  (let [puts (atom {})
        store {:put-fn (fn [s l] (swap! puts assoc s l)) :take-fn (fn [s] (get @puts s))}]
    (binding [core/*client* (make-client "http://engine:7887" :login-store store)]
      (let [{:keys [url]} (client/login-start "http://app/cb" :return-to "/movies"
                                              :public-endpoint "https://id.example.com/")
            p (start-params url)
            pending (get @puts (p "state"))]
        (is (str/starts-with? url "https://id.example.com/oauth/authorize?"))
        (is (= {"response_type" "code" "client_id" "bff" "redirect_uri" "http://app/cb"
                "scope" "openid" "code_challenge_method" "S256"}
               (select-keys p ["response_type" "client_id" "redirect_uri" "scope" "code_challenge_method"])))
        (is (= (p "nonce") (:nonce pending)))
        (is (= "/movies" (:return-to pending)))
        (testing "challenge is the real S256 of the stored verifier"
          (is (= (p "code_challenge")
                 (login/b64url-encode (.digest (MessageDigest/getInstance "SHA-256")
                                               (.getBytes ^String (:code-verifier pending) "US-ASCII"))))))))))

(deftest complete-happy-path
  (let [store (login/memory-login-store)
        nonces (atom {})]
    (with-stub [srv (ok-exchange (fn [form] (get @nonces (form "code_verifier"))))]
      (binding [core/*client* (make-client (:endpoint srv) :login-store
                                           {:put-fn (fn [s l]
                                                      (swap! nonces assoc (:code-verifier l) (:nonce l))
                                                      ((:put-fn store) s l))
                                            :take-fn (:take-fn store)})]
        (let [state ((start-params (:url (client/login-start "http://app/cb"))) "state")
              res (client/login-complete "the-code" state "http://app/cb")
              {:keys [form authorization]} (first @(:requests srv))]
          (is (= {:xid "x-alice" :name "alice" :scopes ["openid" "profile"]} (:user res)))
          (is (= "at" (get-in res [:tokens :access_token])))
          (is (= "/" (:return-to res)))
          (is (= {"grant_type" "authorization_code" "code" "the-code"
                  "redirect_uri" "http://app/cb" "client_id" "bff" "client_secret" "s3cret"}
                 (dissoc form "code_verifier")))
          (is (nil? authorization) "the exchange carries no bearer")
          (testing "state is one-shot"
            (is (= "LOGIN_STATE_UNKNOWN"
                   (:code (ex-data (is (thrown? Exception
                                                (client/login-complete "the-code" state "http://app/cb")))))))))))))

(deftest complete-failures
  (testing "nonce mismatch"
    (with-stub [srv (ok-exchange (constantly "wrong"))]
      (binding [core/*client* (make-client (:endpoint srv))]
        (let [state ((start-params (:url (client/login-start "http://app/cb"))) "state")]
          (is (= "LOGIN_NONCE_MISMATCH"
                 (:code (ex-data (is (thrown? Exception (client/login-complete "c" state "http://app/cb")))))))))))
  (testing "non-2xx carries :status"
    (with-stub [srv (constantly {:status 400 :body "{\"error\":\"invalid_grant\"}"})]
      (binding [core/*client* (make-client (:endpoint srv))]
        (let [state ((start-params (:url (client/login-start "http://app/cb"))) "state")
              data (ex-data (is (thrown? Exception (client/login-complete "c" state "http://app/cb"))))]
          (is (= "LOGIN_EXCHANGE_FAILED" (:code data)))
          (is (= 400 (:status data)))))))
  (testing "no id_token"
    (with-stub [srv (constantly {:status 200 :body "{\"access_token\":\"at\"}"})]
      (binding [core/*client* (make-client (:endpoint srv))]
        (let [state ((start-params (:url (client/login-start "http://app/cb"))) "state")]
          (is (= "LOGIN_EXCHANGE_FAILED"
                 (:code (ex-data (is (thrown? Exception (client/login-complete "c" state "http://app/cb"))))))))))))

(deftest cancel-and-ttl
  (binding [core/*client* (make-client "http://x")]
    (let [state ((start-params (:url (client/login-start "http://app/cb" :return-to "/back"))) "state")]
      (is (= {:return-to "/back"} (client/login-cancel state)))
      (is (nil? (client/login-cancel state)))
      (is (nil? (client/login-cancel nil)))))
  (testing "memory store expires"
    (let [{:keys [put-fn take-fn]} (login/memory-login-store {:ttl-ms 1})]
      (put-fn "s" {:created-at (- (System/currentTimeMillis) 10)})
      (is (nil? (take-fn "s"))))))
