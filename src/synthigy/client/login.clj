(ns synthigy.client.login
  "OIDC authorization-code + PKCE — the protocol half of a browser login.

   Owns the PKCE pair, the authorize URL, the code exchange and reading the
   user out of the id_token. Sessions, cookies and redirects stay the app's.
   The one piece of state the protocol needs — the in-flight login, from
   /login until the callback — lives in a login store, a map
   `{:put-fn (fn [state login]) :take-fn (fn [state])}` passed to
   `connect!` as `:login-store`. `take-fn` is one-shot.

   JVM only: the exchange authenticates as a confidential client, and a
   browser can't hold a secret."
  (:require
   [synthigy.client.error :as err]
   [babashka.http-client :as http]
   [babashka.json :as json]
   [clojure.string :as str])
  (:import
   [java.net URLEncoder]
   [java.nio.charset StandardCharsets]
   [java.security MessageDigest SecureRandom]
   [java.util Base64]))


(def default-ttl-ms (* 5 60 1000))

(defonce random (SecureRandom.))


(defn b64url-encode
  [^bytes raw]
  (.encodeToString (.withoutPadding (Base64/getUrlEncoder)) raw))

(defn random-token
  [n]
  (let [buf (byte-array n)]
    (.nextBytes ^SecureRandom random buf)
    (b64url-encode buf)))

(defn pkce
  "A fresh PKCE verifier and its S256 challenge (RFC 7636)."
  []
  (let [verifier (random-token 32)
        digest (.digest (MessageDigest/getInstance "SHA-256")
                        (.getBytes ^String verifier StandardCharsets/US_ASCII))]
    {:code-verifier verifier :code-challenge (b64url-encode digest)}))

(defn decode-jwt-payload
  "A JWT's payload, unverified — only for a token from our own authenticated token POST."
  [jwt]
  (let [parts (str/split (str jwt) #"\.")]
    (when (>= (count parts) 2)
      (try
        (json/read-str (String. (.decode (Base64/getUrlDecoder) ^String (second parts))
                                StandardCharsets/UTF_8)
                       {:key-fn keyword})
        (catch Exception _ nil)))))


(defn no-login-store-error
  []
  (err/ex-info
   (str "no login store: pass (connect! {:login-store …}) — a map with "
        ":put-fn (fn [state login]) and :take-fn (fn [state]), backed by "
        "whatever already holds your sessions. For a single-process dev "
        "server, pass (synthigy.client.login/memory-login-store).")
   {:code "NO_LOGIN_STORE"}))

(defn rejecting-login-store
  "The default store: refuses rather than silently keeping logins in one process."
  []
  {:put-fn (fn [_ _] (throw (no-login-store-error)))
   :take-fn (fn [_] (throw (no-login-store-error)))})

(defn memory-login-store
  "In-flight logins in an atom — SINGLE PROCESS ONLY; behind a load balancer use a shared store."
  ([] (memory-login-store {}))
  ([{:keys [ttl-ms] :or {ttl-ms default-ttl-ms}}]
   (let [pending (atom {})
         live? #(> (:created-at %) (- (System/currentTimeMillis) ttl-ms))]
     {:put-fn (fn [state login]
                (swap! pending #(assoc (into {} (filter (comp live? val)) %) state login))
                nil)
      :take-fn (fn [state]
                 (let [[old] (swap-vals! pending dissoc state)
                       login (get old state)]
                   (when (and login (live? login)) login)))})))


(defn store
  [client]
  (or (:login-store client) (rejecting-login-store)))

(defn require-confidential
  [{:keys [client-id client-secret-fn]}]
  (when-not (and client-id client-secret-fn)
    (throw (err/ex-info
            (str "login needs :client-id + :client-secret: the code exchange "
                 "authenticates this process as a confidential client. A "
                 "browser or native app is a public client and drives the same "
                 "flow with a PKCE library of its own.")
            {:code "LOGIN_REQUIRES_CONFIDENTIAL_CLIENT"}))))

(defn query-string
  [pairs]
  (str/join "&" (for [[k v] pairs]
                  (str (URLEncoder/encode ^String k "UTF-8") "="
                       (URLEncoder/encode (str v) "UTF-8")))))

(defn start
  [client redirect-uri {:keys [return-to scope public-endpoint]
                        :or {return-to "/" scope "openid"}}]
  (require-confidential client)
  (when (str/blank? redirect-uri)
    (throw (err/ex-info
            (str "login-start needs redirect-uri — the URL on YOUR server the "
                 "IdP sends the browser back to, registered on this OAuth client.")
            {:code "LOGIN_REQUIRES_CONFIDENTIAL_CLIENT"})))
  (let [state (random-token 24)
        nonce (random-token 24)
        {:keys [code-verifier code-challenge]} (pkce)]
    ;; stored BEFORE the URL is handed out — a fast browser would come back to nothing
    ((:put-fn (store client))
     state {:code-verifier code-verifier
            :nonce nonce
            :return-to return-to
            :created-at (System/currentTimeMillis)})
    {:url (str (str/replace (or public-endpoint (:endpoint client)) #"/+$" "")
               "/oauth/authorize?"
               (query-string [["response_type" "code"]
                              ["client_id" (:client-id client)]
                              ["redirect_uri" redirect-uri]
                              ["scope" scope]
                              ["state" state]
                              ["code_challenge" code-challenge]
                              ["code_challenge_method" "S256"]
                              ["nonce" nonce]]))}))

(defn complete
  [client code state redirect-uri]
  (require-confidential client)
  (let [pending (when state ((:take-fn (store client)) state))]
    (when-not pending
      (throw (err/ex-info
              (str "unknown or expired login state: the store never saw it, it "
                   "was already consumed, or it timed out. A callback landing on "
                   "a different instance than the one that served /login does this.")
              {:code "LOGIN_STATE_UNKNOWN"})))
    ;; never through the authed path — its 401 retry would replay a burned one-shot code
    (let [{:keys [status body]}
          (http/post (str (:endpoint client) "/oauth/token")
                     {:form-params {"grant_type" "authorization_code"
                                    "code" code
                                    "redirect_uri" redirect-uri
                                    "client_id" (:client-id client)
                                    "client_secret" ((:client-secret-fn client))
                                    "code_verifier" (:code-verifier pending)}
                      :headers {"Accept" "application/json"}
                      :timeout (:request-timeout client)
                      :throw false})]
      (when-not (<= 200 status 299)
        (throw (err/ex-info (str "authorization-code exchange failed (" status ")")
                        {:code "LOGIN_EXCHANGE_FAILED" :status status :body body})))
      (let [tokens (json/read-str body {:key-fn keyword})
            claims (decode-jwt-payload (:id_token tokens))]
        (when-not claims
          (throw (err/ex-info (str "token response carried no readable id_token — "
                               "request the openid scope, which is what names the user.")
                          {:code "LOGIN_EXCHANGE_FAILED"})))
        (when-not (:xid claims)
          (throw (err/ex-info (str "id_token carries no xid claim — without it the user "
                               "can't be named in :acting-as, and calls would "
                               "silently run as this service.")
                          {:code "LOGIN_EXCHANGE_FAILED"})))
        (when (not= (:nonce claims) (:nonce pending))
          (throw (err/ex-info "id_token nonce does not match the one this login started with"
                          {:code "LOGIN_NONCE_MISMATCH"})))
        {:user {:xid (:xid claims)
                :name (:sub claims)
                :scopes (vec (remove str/blank?
                                     (str/split (str (or (:scope tokens) (:scope claims) ""))
                                                #" ")))}
         :tokens tokens
         :return-to (:return-to pending)}))))

(defn cancel
  [client state]
  (when-let [pending (when state ((:take-fn (store client)) state))]
    {:return-to (:return-to pending)}))
