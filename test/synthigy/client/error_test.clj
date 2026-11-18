(ns synthigy.client.error-test
  (:require [babashka.http-client]
            [babashka.json :as json]
            [clojure.test :refer [deftest is testing]]
            [cognitect.transit :as transit]
            [synthigy.client :as client]
            [synthigy.client.auth :as auth]
            [synthigy.client.core :as core]
            [synthigy.client.error :as err]
            [synthigy.client.http :as http]))

(defn data-of
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest categories-match-the-other-sdks
  (is (= {:code "NO_TOKEN" :category "auth" :retryable false}
         (ex-data (err/ex-info "x" {:code "NO_TOKEN"}))))
  (is (= ["rate_limit" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "TIMEOUT"})))))
  (is (= ["rate_limit" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "SERVER_BUSY"})))))
  (is (= ["validation" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "BAD_DATA_SHAPE"})))))
  (is (= ["not_found" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "REF_NOT_FOUND"})))))
  (is (= ["conflict" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "GUARD_VIOLATION"})))))
  (is (= ["internal" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "DB_UNAVAILABLE"})))))
  (is (= ["internal" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "DB_ERROR"})))))
  (is (= ["network" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "TRANSPORT_ERROR"})))))
  (is (= ["auth" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "access_denied"})))))
  (testing "unknown and missing codes fall back to internal, not retryable"
    (is (= ["internal" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "NEW_SERVER_CODE"})))))
    (is (= ["internal" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {}))))))
  (testing "the server's :retryable wins"
    (is (true? (:retryable (ex-data (err/ex-info "x" {:code "DB_UNAVAILABLE" :retryable true})))))
    (is (false? (:retryable (ex-data (err/ex-info "x" {:code "INTERNAL_ERROR" :retryable false}))))))
  (testing "permission denials are iam"
    (doseq [code ["RELATION_FORBIDDEN" "ATTRIBUTE_FORBIDDEN" "ROW_FORBIDDEN"
                  "CREATE_FORBIDDEN" "DELETE_FORBIDDEN" "SLOT_OCCUPIED"]]
      (is (= ["iam" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code code})))) code)))
  (testing "file link codes"
    (is (= [["iam" false] ["not_found" false] ["validation" false]]
           (for [code ["LINKS_DISABLED" "LINK_NOT_FOUND" "INVALID_EXPIRES"]]
             ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code code})))))))
  (testing "cause is kept"
    (let [cause (Exception. "root")]
      (is (identical? cause (ex-cause (err/ex-info "x" {:code "NETWORK_ERROR"} cause)))))))

(deftest refused-token-mint-is-typed
  (doseq [[status body code message]
          [[401 "{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}"
            "INVALID_CLIENT" "Token request refused (401): invalid_client — Client authentication failed"]
           [400 "{\"error\":\"invalid_scope\"}" "INVALID_SCOPE" "Token request refused (400): invalid_scope"]
           [400 "{\"error\":\"invalid_target\",\"error_description\":\"Client is not authorized for audience x\"}"
            "INVALID_AUDIENCE" "Token request refused (400): invalid_target — Client is not authorized for audience x"]
           [400 "{\"error\":\"unauthorized_client\"}" "TOKEN_ERROR" "Token request refused (400): unauthorized_client"]
           [502 "Bad Gateway" "TOKEN_ERROR" "Token request failed: HTTP 502"]]]
    (with-redefs [babashka.http-client/post (constantly {:status status :body body})]
      (let [e (try ((:token-fn (auth/oauth {:token-url "http://stub/oauth/token"
                                            :client-id "id" :client-secret "wrong"})))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= [code "auth" false status message]
               ((juxt :code :category :retryable :status (constantly (ex-message e))) (ex-data e)))
            code)
        (is (= (= code "INVALID_CLIENT") (some? (:hint (ex-data e)))) code)))))

(deftest verbs-throw-typed-errors
  (testing "client-side guard"
    (binding [core/*client* nil]
      (is (= ["NOT_CONNECTED" "validation" false]
             ((juxt :code :category :retryable) (data-of #(client/search :user nil [:name])))))))
  (testing "server per-op error keeps the server's fields"
    (binding [core/*client* {:endpoint "http://stub" :key-format "kebab"}]
      (with-redefs [http/request (constantly {:results [{:ok false
                                                          :error {:code "ENTITY_FORBIDDEN"
                                                                  :message "no"
                                                                  :entity "user"}}]})]
        (is (= ["ENTITY_FORBIDDEN" "iam" false "user"]
               ((juxt :code :category :retryable :entity)
                (data-of #(client/search :user nil [:name]))))))))
  (testing "config errors"
    (is (= "validation"
           (:category (data-of #(core/create-client {:endpoint "http://x" :bogus 1}))))))
  (testing "watch-sql-template without :entities"
    (binding [core/*client* {:endpoint "http://stub"}]
      (is (= ["MISSING_ENTITIES" "validation"]
             ((juxt :code :category) (data-of #(client/watch-sql-template "SELECT 1" nil))))))))

(def ruled {:rule "relation_data" :attribute "file" :doc "errors/relation_data"})

(defn transit-str
  [data]
  (let [out (java.io.ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toString out "UTF-8")))

(deftest server-errors-keep-rule-attribute-doc
  (let [error (merge {:code "BAD_DATA_SHAPE" :message "bad"} ruled)]
    (testing "->result: the failed operation's error map is the ex-data"
      (let [data (ex-data (core/->result {:ok false :error error} {:op "stack"}))]
        (is (= ruled (select-keys data (keys ruled))))
        (is (= "stack" (get-in data [:operation :op])))))
    (testing "a failed operation through a verb"
      (binding [core/*client* {:endpoint "http://stub" :key-format "kebab"}]
        (with-redefs [http/request (constantly {:results [{:ok false :error error}]})]
          (is (= ruled (select-keys (data-of #(client/stack :document {:file "f-1"})) (keys ruled)))))))
    (testing "a refused request, transit and JSON"
      (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t")}]
        (doseq [[ct body] [["application/transit+json" (transit-str {:error error})]
                           ["application/json" (json/write-str {:error error})]]]
          (with-redefs [babashka.http-client/post (constantly {:status 400 :headers {"content-type" ct} :body body})]
            (let [data (data-of #(client/search :user nil [:name]))]
              (is (= ruled (select-keys data (keys ruled))) ct)
              (is (= ["BAD_DATA_SHAPE" 400 "bad"] ((juxt :code :status :message) data)) ct))))))
    (testing "absent fields stay absent"
      (binding [core/*client* {:endpoint "http://stub" :key-format "kebab"}]
        (with-redefs [http/request (constantly {:results [{:ok false :error {:code "FK_VIOLATION" :message "x"}}]})]
          (is (= {} (select-keys (data-of #(client/search :user nil [:name])) [:hint :rule :attribute :doc])))))
      (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t")}]
        (with-redefs [babashka.http-client/post (constantly {:status 502 :headers {"content-type" "text/plain"} :body "Bad Gateway"})]
          (let [e (try (client/search :user nil [:name]) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= "Request failed: HTTP 502" (ex-message e)))
            (is (= {:code "HTTP_ERROR" :status 502 :body "Bad Gateway"}
                   (select-keys (ex-data e) [:code :status :body :rule :attribute :doc])))))))))

(defn busy-reply [code]
  {:status 503 :headers {"content-type" "application/json" "retry-after" "1"}
   :body (json/write-str {:error {:code code :message "busy" :retryable true}})})

(deftest a-busy-server-is-resent-then-a-retryable-rate-limit
  (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t")}]
    (let [calls (atom 0)
          waits (atom [])]
      (testing "resent until it gets in"
        (with-redefs [core/busy-delay-ms (fn [ra] (swap! waits conj ra) 0)
                      babashka.http-client/post
                      (fn [& _] (if (<= (swap! calls inc) 2)
                                  (busy-reply "SERVER_BUSY")
                                  {:status 200 :headers {"content-type" "application/json"}
                                   :body (json/write-str {:results [{:ok true :data []}]})}))]
          (is (= [] (client/search :movie nil [:title])))
          (is (= 3 @calls))
          (is (= ["1" "1"] @waits) "each wait follows Retry-After")))
      (testing "three attempts, then the refusal"
        (reset! calls 0)
        (with-redefs [core/busy-delay-ms (constantly 0)
                      babashka.http-client/post (fn [& _] (swap! calls inc) (busy-reply "SERVER_BUSY"))]
          (is (= {:code "SERVER_BUSY" :category "rate_limit" :retryable true :status 503}
                 (select-keys (data-of #(client/search :movie nil [:title])) [:code :category :retryable :status])))
          (is (= 3 @calls))))
      (testing "other refusals go once"
        (reset! calls 0)
        (with-redefs [core/busy-delay-ms (constantly 0)
                      babashka.http-client/post (fn [& _] (swap! calls inc) (busy-reply "MODULE_NOT_LOADED"))]
          (data-of #(client/search :movie nil [:title]))
          (is (= 1 @calls)))))))

(deftest the-request-timeout-bounds-the-busy-wait
  (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t") :request-timeout 200}]
    (let [calls (atom 0)]
      (with-redefs [core/busy-delay-ms (constantly 60000)
                    babashka.http-client/post (fn [& _] (swap! calls inc) (busy-reply "SERVER_BUSY"))]
        (is (= "SERVER_BUSY" (:code (data-of #(client/search :movie nil [:title])))))
        (is (= 1 @calls))))))

(deftest a-busy-resend-gets-only-the-time-left
  (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t") :request-timeout 400}]
    (let [timeouts (atom [])]
      (with-redefs [core/busy-delay-ms (constantly 150)
                    babashka.http-client/post (fn [_ opts]
                                                (swap! timeouts conj (:timeout opts))
                                                (if (= 1 (count @timeouts))
                                                  (busy-reply "SERVER_BUSY")
                                                  {:status 200 :headers {"content-type" "application/json"}
                                                   :body (json/write-str {:results [{:ok true :data []}]})}))]
        (is (= [] (client/search :movie nil [:title])))
        (is (= 2 (count @timeouts)))
        (is (<= (second @timeouts) 250) "the resend's timeout leaves out the time already spent")))))

(deftest the-busy-wait-follows-retry-after
  (dotimes [_ 200]
    (is (<= 1000 (core/busy-delay-ms "2") 3000))
    (is (<= 500 (core/busy-delay-ms nil) 1500))
    (is (<= 5000 (core/busy-delay-ms "99999999999999999") 15000))))

(deftest onboard-keeps-both-error-shapes
  (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t")}]
    (doseq [call [#(client/onboard "user-xid-1") #(client/onboard-complete "t")]]
      (testing "a bare string code"
        (with-redefs [babashka.http-client/post (constantly {:status 403 :headers {"content-type" "application/json"}
                                                             :body (json/write-str {:error "provision_forbidden"})})]
          (is (= {:code "PROVISION_FORBIDDEN" :status 403} (select-keys (data-of call) [:code :status])))))
      (testing "the server's error map"
        (with-redefs [babashka.http-client/post
                      (constantly {:status 503 :headers {"content-type" "application/json"}
                                   :body (json/write-str {:error (merge {:code "MODULE_NOT_LOADED"
                                                                         :message "oauth is not loaded" :hint "h"}
                                                                        ruled)})})]
          (let [e (try (call) nil (catch clojure.lang.ExceptionInfo e e))]
            (is (= "oauth is not loaded" (ex-message e)))
            (is (= (merge {:code "MODULE_NOT_LOADED" :status 503 :hint "h"} ruled)
                   (select-keys (ex-data e) [:code :status :hint :rule :attribute :doc])))))))))

(deftest a-refused-event-stream-keeps-the-servers-error
  (binding [core/*client* {:endpoint "http://stub" :token-fn (constantly "t")}]
    (let [stream #(java.io.ByteArrayInputStream. (.getBytes ^String % "UTF-8"))
          connect #(try (#'http/sse-connect nil "s-1") nil (catch clojure.lang.ExceptionInfo e e))]
      (testing "the server's error map"
        (with-redefs [babashka.http-client/get
                      (constantly {:status 403 :headers {"content-type" "application/json"}
                                   :body (stream (json/write-str {:error (merge {:code "FORBIDDEN" :message "no"} ruled)}))})]
          (let [e (connect)]
            (is (= "no" (ex-message e)))
            (is (= (merge {:code "FORBIDDEN" :status 403} ruled)
                   (select-keys (ex-data e) [:code :status :rule :attribute :doc]))))))
      (testing "no error body"
        (with-redefs [babashka.http-client/get
                      (constantly {:status 502 :headers {"content-type" "text/plain"} :body (stream "Bad Gateway")})]
          (let [e (connect)]
            (is (= "SSE connect failed: HTTP 502" (ex-message e)))
            (is (= {:code "HTTP_ERROR" :status 502} (select-keys (ex-data e) [:code :status])))))))))

(deftest a-top-level-error-on-a-200-throws
  (binding [core/*client* {:endpoint "http://stub" :key-format "kebab"}]
    (with-redefs [http/request (constantly {:error {:code "NO_OPERATIONS" :message "No operations provided" :hint "h"}})]
      (is (= ["NO_OPERATIONS" "No operations provided" "h" 200]
             ((juxt :code :message :hint :status) (data-of #(client/search :user nil [:name])))))
      (is (= "NO_OPERATIONS" (:code (data-of #(client/batch [(core/op-search :user nil [:name])]))))))))
