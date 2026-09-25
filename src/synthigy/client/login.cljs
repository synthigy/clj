(ns synthigy.client.login
  "Browser OIDC login as a public client: authorization code + PKCE, silent renew
   through a hidden iframe, tokens in memory only. Pass `(provider)` to `connect!`."
  (:require
   [synthigy.client.error :as err]
   [cljs-bean.core :refer [->clj ->js]]
   [clojure.string :as str]
   [promesa.core :as p]))


(def pending-prefix "synthigy.login:")

(def pending-ttl-ms (* 5 60 1000))

(def message-source "synthigy-login")

(def defaults
  {:scope "openid profile"
   :automatic-silent-renew? true
   :load-user-info? false
   :silent-timeout-ms 10000
   :expiring-seconds 60})

(defonce state
  (atom {:config nil
         :tokens nil
         :user nil
         :expires-at nil
         :renewal nil
         :timers []
         :listeners {}}))


(defn on
  "Subscribe to :user-loaded, :user-unloaded, :access-token-expiring, :access-token-expired or :silent-renew-error."
  [event f]
  (let [id (random-uuid)]
    (swap! state assoc-in [:listeners event id] f)
    (fn [] (swap! state update-in [:listeners event] dissoc id))))

(defn emit!
  [event payload]
  (doseq [f (vals (get-in @state [:listeners event]))]
    (try (f payload)
         (catch :default e
           (js/console.warn "synthigy.client.login listener error:" e)))))


(defn b64url
  [^js bytes]
  (-> (js/btoa (.apply js/String.fromCharCode nil (js/Array.from bytes)))
      (str/replace #"\+" "-")
      (str/replace #"/" "_")
      (str/replace #"=+$" "")))

(defn random-token
  [n]
  (b64url (js/crypto.getRandomValues (js/Uint8Array. n))))

(defn pkce
  "A fresh PKCE verifier and its S256 challenge (RFC 7636), as a promise."
  []
  (let [verifier (random-token 32)]
    (p/let [digest (js/crypto.subtle.digest "SHA-256" (.encode (js/TextEncoder.) verifier))]
      {:code-verifier verifier
       :code-challenge (b64url (js/Uint8Array. digest))})))

(defn decode-jwt-payload
  "A JWT's payload, unverified — only for a token from our own token response."
  [jwt]
  (when-let [part (second (str/split (str jwt) #"\."))]
    (try
      (let [b64 (-> part (str/replace #"-" "+") (str/replace #"_" "/"))
            padded (str b64 (subs "===" 0 (mod (- 4 (mod (count b64) 4)) 4)))
            bytes (js/Uint8Array.from (js/atob padded) #(.charCodeAt % 0))]
        (->clj (js/JSON.parse (.decode (js/TextDecoder.) bytes))))
      (catch :default _ nil))))


(defn config
  []
  (or (:config @state)
      (throw (err/ex-info "login not configured: call synthigy.client.login/configure! first"
                      {:code "CONFIG_ERROR"}))))

(defn configure!
  "Set :endpoint, :client-id, :redirect-uri and optional :silent-redirect-uri, :post-logout-redirect-uri, :scope, :audience, :load-user-info?."
  [{:keys [endpoint client-id redirect-uri] :as opts}]
  (when-not (and endpoint client-id redirect-uri)
    (throw (err/ex-info "login needs :endpoint, :client-id and :redirect-uri"
                    {:code "CONFIG_ERROR"})))
  (swap! state assoc :config
         (merge defaults
                {:silent-redirect-uri redirect-uri
                 :post-logout-redirect-uri redirect-uri}
                (update opts :endpoint str/replace #"/+$" "")))
  nil)

(defn configured? [] (some? (:config @state)))

(defn token [] (get-in @state [:tokens :access_token]))

(defn user [] (:user @state))


(defn authorize-url
  [{:keys [endpoint client-id scope audience]} {:keys [redirect-uri state nonce code-challenge prompt]}]
  (let [params (js/URLSearchParams.)]
    (doseq [[k v] [["response_type" "code"]
                   ["client_id" client-id]
                   ["redirect_uri" redirect-uri]
                   ["scope" scope]
                   ["state" state]
                   ["nonce" nonce]
                   ["code_challenge" code-challenge]
                   ["code_challenge_method" "S256"]
                   ["audience" audience]
                   ["prompt" prompt]]
            :when (some? v)]
      (.append params k v))
    (str endpoint "/oauth/authorize?" (.toString params))))

(defn callback-params
  "The authorization response carried by `href`, or nil when it carries none."
  [href]
  (let [q (.-searchParams (js/URL. href))
        state (.get q "state")
        code (.get q "code")
        error (.get q "error")]
    (when (and state (or code error))
      {:state state
       :code code
       :error error
       :error-description (.get q "error_description")})))

(defn callback? [] (some? (callback-params js/location.href)))

(defn start-request
  "PKCE pair, state and nonce for one authorize round trip."
  [redirect-uri prompt]
  (p/let [{:keys [code-verifier code-challenge]} (pkce)]
    {:redirect-uri redirect-uri
     :prompt prompt
     :state (random-token 24)
     :nonce (random-token 24)
     :code-verifier code-verifier
     :code-challenge code-challenge}))


(defn put-pending!
  [{:keys [state] :as request} return-to]
  (.setItem js/sessionStorage (str pending-prefix state)
            (js/JSON.stringify
             (->js (-> request
                          (select-keys [:redirect-uri :nonce :code-verifier])
                          (assoc :return-to return-to
                                 :created-at (js/Date.now)))))))

(defn take-pending!
  [state]
  (let [k (str pending-prefix state)
        raw (.getItem js/sessionStorage k)]
    (.removeItem js/sessionStorage k)
    (when raw
      (let [pending (->clj (js/JSON.parse raw))]
        (when (> (:created-at pending) (- (js/Date.now) pending-ttl-ms))
          pending)))))

(defn strip-callback!
  []
  (.replaceState js/history nil "" (str js/location.pathname js/location.hash)))


(defn exchange
  "Authorization code → token response, as a public client (no secret)."
  [{:keys [endpoint client-id]} code {:keys [redirect-uri code-verifier]}]
  (let [form (js/URLSearchParams.)]
    (doseq [[k v] [["grant_type" "authorization_code"]
                   ["code" code]
                   ["redirect_uri" redirect-uri]
                   ["client_id" client-id]
                   ["code_verifier" code-verifier]]]
      (.append form k v))
    (p/let [resp (js/fetch (str endpoint "/oauth/token")
                           #js {:method "POST"
                                :credentials "omit"
                                :headers #js {"Accept" "application/json"
                                              "Content-Type" "application/x-www-form-urlencoded"}
                                :body (.toString form)})
            text (.text resp)]
      (if (.-ok resp)
        (->clj (js/JSON.parse text))
        (throw (err/ex-info (str "authorization-code exchange failed (" (.-status resp) ")")
                        {:code "LOGIN_EXCHANGE_FAILED" :status (.-status resp) :body text}))))))


(declare renew!)

(defn clear-timers!
  []
  (run! js/clearTimeout (:timers @state))
  (swap! state assoc :timers []))

(defn schedule!
  [expires-at]
  (clear-timers!)
  (let [{:keys [automatic-silent-renew? expiring-seconds]} (config)
        now (js/Date.now)
        expiring (js/setTimeout
                  (fn []
                    (emit! :access-token-expiring nil)
                    (when automatic-silent-renew?
                      (p/catch (renew!) (fn [_] nil))))
                  (max 0 (- expires-at (* expiring-seconds 1000) now)))
        expired (js/setTimeout #(emit! :access-token-expired nil)
                               (max 0 (- expires-at now)))]
    (swap! state assoc :timers [expiring expired])))

(defn user-info
  "GET /oauth/userinfo with the fresh access token."
  [{:keys [endpoint]} access-token]
  (p/let [resp (js/fetch (str endpoint "/oauth/userinfo")
                         #js {:method "GET"
                              :credentials "omit"
                              :headers #js {"Accept" "application/json"
                                            "Authorization" (str "Bearer " access-token)}})
          text (.text resp)]
    (if (.-ok resp)
      (->clj (js/JSON.parse text))
      (throw (err/ex-info (str "userinfo request failed (" (.-status resp) ")")
                          {:code "USERINFO_FAILED" :status (.-status resp) :body text})))))

(defn accept!
  [tokens nonce]
  (let [claims (decode-jwt-payload (:id_token tokens))
        cfg (config)]
    (when-not claims
      (throw (err/ex-info "token response carried no readable id_token — request the openid scope"
                      {:code "LOGIN_EXCHANGE_FAILED"})))
    (when (not= (:nonce claims) nonce)
      (throw (err/ex-info "id_token nonce does not match the one this login started with"
                      {:code "LOGIN_NONCE_MISMATCH"})))
    (p/let [info (when (:load-user-info? cfg) (user-info cfg (:access_token tokens)))]
      (when (and info (not= (:sub info) (:sub claims)))
        (throw (err/ex-info "userinfo sub does not match the id_token"
                            {:code "USERINFO_SUB_MISMATCH"})))
      (let [user (merge (into {} claims) (into {} info))
            expires-at (+ (js/Date.now) (* 1000 (or (:expires_in tokens) 300)))]
        (swap! state assoc :tokens tokens :user user :expires-at expires-at)
        (schedule! expires-at)
        (emit! :user-loaded user)
        user))))

(defn authorization-error
  [{:keys [error error-description]}]
  (err/ex-info (or error-description error) {:code error}))


(defn login-start
  "Redirect the page to the authorize endpoint; `:prompt \"none\"` returns without a login form when a session is alive."
  [& {:keys [prompt return-to]}]
  (let [cfg (config)
        return-to (or return-to (str js/location.pathname js/location.search js/location.hash))]
    (p/let [request (start-request (:redirect-uri cfg) prompt)]
      (put-pending! request return-to)
      (js/location.assign (authorize-url cfg request)))))

(defn login-complete
  "Finish the login the current URL returns from; resolves {:user :return-to}, or nil when the URL carries no response."
  []
  (if-let [{:keys [state code] :as params} (callback-params js/location.href)]
    (let [pending (take-pending! state)]
      (strip-callback!)
      (cond
        (nil? pending)
        (p/rejected (err/ex-info "unknown or expired login state: already consumed, timed out, or started in another tab"
                             {:code "LOGIN_STATE_UNKNOWN"}))

        (nil? code)
        (p/rejected (authorization-error params))

        :else
        (p/let [tokens (exchange (config) code pending)
                user (accept! tokens (:nonce pending))]
          {:user user :return-to (:return-to pending)})))
    (p/resolved nil)))

(defn login-cancel
  "Drop the login the current URL returns from; resolves {:return-to}."
  []
  (when-let [{:keys [state]} (callback-params js/location.href)]
    (strip-callback!)
    (when-let [pending (take-pending! state)]
      {:return-to (:return-to pending)})))


(defn silent-frame
  [cfg request]
  (let [frame (js/document.createElement "iframe")
        origin (.-origin (js/URL. (:silent-redirect-uri cfg)))]
    (set! (.. frame -style -cssText) "position:absolute;width:0;height:0;border:0;visibility:hidden")
    (p/create
     (fn [resolve reject]
       (let [cleanup (atom nil)
             on-message
             (fn [^js e]
               (let [data (.-data e)]
                 (when (and (= origin (.-origin e))
                            (identical? (.-source e) (.-contentWindow frame))
                            (= message-source (some-> data (unchecked-get "source"))))
                   (@cleanup)
                   (let [params (callback-params (unchecked-get data "url"))]
                     (if (= (:state request) (:state params))
                       (resolve params)
                       (reject (err/ex-info "silent renew answered with a foreign state"
                                        {:code "LOGIN_STATE_UNKNOWN"})))))))
             timer (js/setTimeout
                    (fn []
                      (@cleanup)
                      (reject (err/ex-info "silent renew timed out" {:code "SILENT_TIMEOUT"})))
                    (:silent-timeout-ms cfg))]
         (reset! cleanup
                 (fn []
                   (js/clearTimeout timer)
                   (js/window.removeEventListener "message" on-message)
                   (.remove frame)))
         (js/window.addEventListener "message" on-message)
         (set! (.-src frame) (authorize-url cfg request))
         (js/document.body.appendChild frame))))))

(defn renew!
  "Silent renew through a hidden iframe with prompt=none; resolves the user, one renewal at a time."
  []
  (or (:renewal @state)
      (let [cfg (config)
            renewal
            (-> (p/let [request (start-request (:silent-redirect-uri cfg) "none")
                        {:keys [code] :as params} (silent-frame cfg request)]
                  (if code
                    (p/let [tokens (exchange cfg code request)]
                      (accept! tokens (:nonce request)))
                    (throw (authorization-error params))))
                (p/catch (fn [e]
                           (emit! :silent-renew-error e)
                           (throw e)))
                (p/finally (fn [_ _] (swap! state assoc :renewal nil))))]
        (swap! state assoc :renewal renewal)
        renewal)))

(defn silent-callback!
  "Call first thing on the silent redirect page; true when this window was a renewal iframe and has answered it."
  []
  (if (and (not (identical? js/window js/window.parent))
           (callback?))
    (do (.postMessage js/window.parent
                      #js {:source message-source :url js/location.href}
                      js/location.origin)
        true)
    false))


(defn remove-user!
  "Forget the tokens locally without ending the server session."
  []
  (clear-timers!)
  (swap! state assoc :tokens nil :user nil :expires-at nil)
  (emit! :user-unloaded nil))

(defn logout!
  "End the server session and redirect to :post-logout-redirect-uri."
  []
  (let [{:keys [endpoint post-logout-redirect-uri]} (config)
        id-token (get-in @state [:tokens :id_token])
        params (js/URLSearchParams.)]
    (when id-token (.append params "id_token_hint" id-token))
    (.append params "post_logout_redirect_uri" post-logout-redirect-uri)
    (remove-user!)
    (js/location.assign (str endpoint "/oauth/logout?" (.toString params)))))


(defn provider
  "`{:token-fn :invalidate-fn}` for `connect!`; a 401 retry waits for the renewal it triggers."
  []
  (let [current (fn []
                  (if-let [renewal (:renewal @state)]
                    (p/handle renewal (fn [_ _] (token)))
                    (token)))
        invalidate (fn []
                     (p/catch (p/do (renew!)) (fn [_] nil))
                     nil)]
    {:token-fn (fn ([] (current)) ([_audience] (current)))
     :invalidate-fn (fn ([] (invalidate)) ([_audience] (invalidate)))}))
