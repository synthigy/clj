(ns synthigy.client.auth
  "Token providers for the Synthigy client.

   A provider is a map `{:token-fn f :invalidate-fn g}`:

     :token-fn     — 0-arg returning a default bearer token.
                     Optional 1-arg variant `(token-fn audience)` returns
                     a token for another service that trusts Synthigy as IdP.
     :invalidate-fn — 0-arg clearing the provider's cache so the next
                      `token-fn` call refetches. 1-arg variant clears
                      a specific audience. Optional.

   The SDK calls `token-fn` before every request; on HTTP 401 it calls
   `invalidate-fn` (if present) and retries once. How tokens are fetched,
   cached, or refreshed is entirely the provider's concern — this ns ships
   two sensible defaults:

     (static token)                — one fixed token, no refresh
     (oauth {:client-id ...})      — client-credentials flow, caches per
                                     audience, refreshes before expiry"
  (:require
   [synthigy.client.error :as err]
   [babashka.http-client :as http]
   [babashka.json :as json])
  (:import
   [java.io BufferedReader InputStreamReader]))


(defn static
  "Provider wrapping a single pre-fetched token. `invalidate-fn` is a
   no-op — there's nothing to refresh."
  [token]
  {:token-fn (fn
               ([] token)
               ([_audience]
                (throw (err/ex-info "Static token cannot fetch per-audience tokens"
                                {:code "CONFIG_ERROR"}))))
   :invalidate-fn (fn ([]) ([_audience]))})


(defn- fetch-token
  [{:keys [token-url client-id client-secret]} audience]
  (let [form (cond-> {"grant_type" "client_credentials"
                      "client_id" client-id
                      "client_secret" client-secret}
               audience (assoc "audience" audience))
        response (http/post token-url
                   {:form-params form
                    :headers {"Accept" "application/json"}
                    :throw false})
        status (:status response)]
    (when-not (<= 200 status 299)
      (throw (err/ex-info (str "Token request failed: HTTP " status)
                      {:code "TOKEN_ERROR" :status status :body (:body response)})))
    (let [body (json/read-str (:body response) {:key-fn keyword})
          {:keys [access_token expires_in]} body]
      {:access-token access_token
       :expires-at (+ (System/currentTimeMillis)
                      (* (or expires_in 3600) 1000))})))


(defn- expired?
  [t buffer-ms]
  (or (nil? t)
      (nil? (:expires-at t))
      (< (:expires-at t) (+ (System/currentTimeMillis) buffer-ms))))


(defn- usable?
  "True when `d` is a cached token delay we can still serve, so the cache
   swap! keeps it instead of starting another fetch:

     - not yet realized → a fetch is in flight; share it (single-flight)
     - realized & fresh  → reuse the cached token
     - realized & expired or poisoned by a failed fetch → not usable

   Side-effect-free (only derefs already-realized delays) so swap! retries
   are safe."
  [d buffer-ms]
  (and d
       (or (not (realized? d))
           (try (not (expired? @d buffer-ms))
                (catch Throwable _ false)))))


(defn oauth
  "OAuth client-credentials provider.

   Options:
     :token-url     — required; full URL of the token endpoint
     :client-id     — required
     :client-secret — required
     :audience      — default audience for every mint; the platform's
                      audience model is opt-in, and a mint naming none
                      resolves to the identity-only OIDC audience that
                      /data rejects. Defaults to $SYNTHIGY_AUDIENCE.
     :token-buffer  — seconds before expiry to refresh (default 30)

   Tokens are cached per audience (`nil` = default). The returned map
   plugs straight into create-client opts via merge."
  [{:keys [token-url client-id client-secret token-buffer audience]
    :or {token-buffer 30
         audience (System/getenv "SYNTHIGY_AUDIENCE")}
    :as config}]
  {:pre [(and token-url client-id client-secret)]}
  ;; cache: {audience (delay {:access-token .. :expires-at ..})}. Storing a
  ;; delay (not the bare token) makes refresh single-flight: concurrent
  ;; callers for the same audience converge on one delay via swap!'s CAS and
  ;; share its single fetch, instead of each firing a duplicate token request.
  (let [cache (atom {})
        buffer-ms (* token-buffer 1000)
        resolve (fn [aud]
                  (let [d (-> (swap! cache
                                     (fn [m]
                                       (if (usable? (clojure.core/get m aud) buffer-ms)
                                         m
                                         (assoc m aud
                                                (delay (fetch-token config aud))))))
                              (clojure.core/get aud))]
                    ;; Force outside the swap! so the fetch never runs inside a
                    ;; (retryable) swap fn. A throwing fetch propagates here and
                    ;; leaves a poisoned delay that usable? replaces next call.
                    (:access-token @d)))]
    {:token-fn (fn
                 ([] (resolve audience))
                 ([a] (resolve (or a audience))))
     :invalidate-fn (fn
                      ([] (reset! cache {}))
                      ([audience] (swap! cache dissoc audience)))}))


;; ============================================================================
;; Supervised stdio — SYNTHIGY_SUPERVISED=1. The SDK never mints locally under this mode: the CLI/commander
;; is the platform's stdio owner, so a token is asked for over JSON-RPC on
;; the process's OWN stdio (supervise grammar) instead. `{token, expires_in}`
;; is deliberately byte-compatible with robotics' `request-access-token`
;; response, so this ask serves both parents (exec locally, the commander
;; through a reacher agent in production).
;;
;; ONE reader thread for the whole process lifetime, process-wide (not one
;; per ask): two independent readers on the same stdin would race/corrupt/
;; steal each other's line. A single malformed/undecodable line must never
;; take the loop down with it — every future ask would otherwise hang to
;; its own timeout forever with no way to ever succeed again. One shared
;; cache + lock too (not per-provider): every `supervised` provider
;; constructed in a process shares the one supervising parent, so a second
;; independent ask would just be a wasted round trip.
;; ============================================================================

(def ^:private supervised-timeout-ms 5000)

(defonce ^:private supervised-reader-started? (atom false))
(defonce ^:private supervised-pending (atom {}))   ; request id -> promise
(defonce ^:private supervised-next-id (atom 0))
(defonce ^:private supervised-write-lock (Object.))
(defonce ^:private supervised-cache-lock (Object.))
(defonce ^:private supervised-tokens (atom {}))    ; audience-key -> [token expires-at-ms]

(defn- supervised-read-loop!
  []
  (let [rdr (BufferedReader. (InputStreamReader. System/in "UTF-8"))]
    (loop []
      (let [line (try (.readLine rdr)
                       ;; Undecodable bytes, a closed/torn-down stream,
                       ;; whatever — treated exactly like EOF: there is no
                       ;; listener anymore.
                       (catch Throwable _ nil))]
        (if (nil? line)
          (let [waiters (vals @supervised-pending)]
            (reset! supervised-pending {})
            (doseq [p waiters] (deliver p ::eof)))
          (do
            (try
              (let [stripped (.trim ^String line)]
                (when (and (seq stripped) (= \{ (first stripped)))
                  (let [msg (json/read-str stripped {:key-fn keyword})]
                    (when (= "2.0" (:jsonrpc msg))
                      (when-let [p (get @supervised-pending (:id msg))]
                        (swap! supervised-pending dissoc (:id msg))
                        (deliver p msg))))))
              ;; A malformed candidate line must not take the dispatcher
              ;; down with it — keep reading.
              (catch Throwable _ nil))
            (recur)))))))

(defn- supervised-ensure-reader!
  []
  (when (compare-and-set! supervised-reader-started? false true)
    (doto (Thread. ^Runnable supervised-read-loop! "synthigy-supervised-stdin")
      (.setDaemon true)
      (.start))))

(defn- supervised-write-frame!
  "Atomic single write of `frame + \\n` (the frame
   rule). Returns false on a write failure (broken pipe — parent already
   gone) instead of raising; the caller falls through to the timeout path."
  [frame]
  (locking supervised-write-lock
    (try
      (.write System/out (.getBytes (str (json/write-str frame) "\n") "UTF-8"))
      (.flush System/out)
      true
      (catch Throwable _ false))))

(defn- supervised-ask
  "Send one JSON-RPC request, block up to `timeout-ms` for the matching
   response. Returns the parsed message, or nil on timeout, parent EOF, or
   a write failure."
  [method params timeout-ms]
  (supervised-ensure-reader!)
  (let [id (swap! supervised-next-id inc)
        p (promise)]
    (swap! supervised-pending assoc id p)
    (if (supervised-write-frame! {:jsonrpc "2.0" :id id :method method :params params})
      (let [result (deref p timeout-ms ::timeout)]
        (swap! supervised-pending dissoc id)
        (when-not (#{::timeout ::eof} result) result))
      (do (swap! supervised-pending dissoc id) nil))))

(defn- supervised-get-token
  "Per-audience cache with a 30s pre-expiry buffer, matching `oauth`'s own
   discipline — extended process-wide (one cache, one lock covering the
   whole ask) since every `supervised` provider in a process shares one
   supervising parent."
  [audience]
  (let [key (or audience "")]
    (locking supervised-cache-lock
      (let [[cached expires-at] (get @supervised-tokens key)]
        (if (and cached (< (System/currentTimeMillis) (- expires-at 30000)))
          cached
          (let [params (cond-> {} audience (assoc :audience audience))
                msg (supervised-ask "auth.token" params supervised-timeout-ms)]
            (cond
              (nil? msg)
              (throw (err/ex-info
                      (str "Timed out waiting for auth.token from the supervising "
                           "parent — a hung or missing parent must not hang the "
                           "bot. Check the parent process (synthigy exec/agent, "
                           "or the robotics commander) is still connected.")
                      {:code "NO_TOKEN"}))

              (:error msg)
              (throw (err/ex-info (or (get-in msg [:error :message]) "auth.token request denied")
                              {:code "NO_TOKEN"}))

              :else
              (let [token (get-in msg [:result :token])]
                (when-not token
                  (throw (err/ex-info "auth.token response carried no token" {:code "NO_TOKEN"})))
                ;; A malformed/non-numeric expires_in from the parent must
                ;; not crash the cache-expiry computation.
                (let [expires-in (try (double (get-in msg [:result :expires_in]))
                                       (catch Throwable _ 300))
                      expires-in (if (Double/isNaN expires-in) 300 expires-in)]
                  (swap! supervised-tokens assoc key
                         [token (+ (System/currentTimeMillis) (* expires-in 1000))])
                  token)))))))))

(defn supervised
  "Provider for SYNTHIGY_SUPERVISED=1 — asks `auth.token` over the process's
   own stdio instead of minting locally; the CLI/commander is the
   platform's stdio owner. A thin handle onto
   a process-wide reader + cache: every `supervised` provider constructed
   in one process shares one stdin reader and one token cache instead of
   each asking the parent independently."
  []
  {:token-fn (fn
               ([] (supervised-get-token nil))
               ([audience] (supervised-get-token audience)))
   :invalidate-fn (fn
                    ([] (reset! supervised-tokens {}))
                    ([audience] (swap! supervised-tokens dissoc (or audience ""))))})

(defn no-token-error
  "The teaching throw: the error IS the UX, no
   flag, no silent anonymous fallback."
  []
  (err/ex-info
   (str "no Synthigy token: set SYNTHIGY_TOKEN, or SYNTHIGY_CLIENT_ID + "
        "SYNTHIGY_CLIENT_SECRET, or run under `synthigy exec` (or a "
        "Synthigy agent) so a parent can supply one.")
   {:code "NO_TOKEN"}))
