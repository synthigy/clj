(ns synthigy.client.http
  "HTTP transport for the Synthigy CLJS client.

   Uses js/fetch — works in every modern browser and Node 18+. Promise-
   returning throughout; callers use promesa (`p/let`) or `.then`.

   Tokens come from the client's `:token-fn` (0-arg for default token,
   1-arg for audience-scoped). On 401 the client's `:invalidate-fn` (if
   any) is called to force a token refresh, then the request is retried
   once. If that still fails with 401, UNAUTHORIZED is thrown."
  (:require
   [cljs-bean.core :refer [->js]]
   [clojure.string :as str]
   [cognitect.transit :as transit]
   [promesa.core :as p]
   [synthigy.client.core :as core]))


;; /data speaks transit so temporal values arrive as real js/Date (JSON would
;; flatten them to strings). Writer is compact `:json`; the reader auto-detects
;; verbose too, so it reads whatever the server emits. No custom handlers — the
;; wire carries plain maps/scalars + built-in dates/keywords/sets; the deployed
;; model comes back as a pre-encoded transit STRING (decoded by the frontend).
(def ^:private tw (transit/writer :json))
(def ^:private tr (transit/reader :json))


(defn token
  "Resolve a bearer token via the connected client's token-fn. Returns a
   promise (token-fn may be sync — a raw string — or async — a Promise).
   `js/Promise.resolve` is deliberate: it FLATTENS a thenable, so an async
   token-fn resolves to the token string. `promesa/resolved` does NOT
   flatten — it would wrap the inner promise, and callers would send
   `Bearer [object Promise]`."
  ([] (js/Promise.resolve ((:token-fn (core/the-client)))))
  ([audience]
   (js/Promise.resolve ((:token-fn (core/the-client)) audience))))


(defn- invalidate!
  [client]
  (when-let [f (:invalidate-fn client)]
    (f)))


(defn base-url
  "The connected client's server base URL. Sibling paths (/schema, /lint,
   /data/events, /data/subscription/set …) hang off this; /data itself is
   appended by `request`."
  []
  (:endpoint (core/the-client)))


(defn- safe-invoke!
  [f & args]
  (when f
    (try (apply f args)
         (catch :default e
           (js/console.warn "synthigy.client hook error:" (ex-message e))))))


(defn- merge-headers
  [base extra]
  (->js (reduce (fn [m [k v]] (assoc m (name k) v)) base extra)))


(defn- parse-body
  "Decode a response body by what the server actually returned. /data replies
   transit (real js/Date, keyword keys); sibling endpoints (/schema, /lint)
   force JSON — so branch on the response Content-Type rather than assuming.
   JSON goes through js->clj (real data, NOT a lazy bean) so any downstream
   transit re-encode in the frontend works."
  [text content-type]
  (try
    (when (and text (not= "" text))
      (if (and content-type (str/includes? content-type "transit"))
        (transit/read tr text)
        (js->clj (js/JSON.parse text) :keywordize-keys true)))
    (catch :default _ nil)))


(defn- authed-once
  "Single authenticated fetch. Returns a promise of
   `{:status :headers :body-text :stream}` — body-text is unset for
   stream responses; stream is unset otherwise."
  [client method url {:keys [headers body timeout-ms stream? signal] :as opts}]
  ;; p/let's binding flattens a thenable, so a bare (token-fn) call resolves
  ;; to the token string whether token-fn is sync or async. NOT (p/resolved
  ;; (token-fn)) — promesa/resolved wraps rather than flattens a nested
  ;; promise, which would send `Bearer [object Promise]` for async providers.
  (p/let [bearer ((:token-fn client))
          base-headers {"Authorization" (str "Bearer " bearer)
                        "Accept" (if stream? "text/event-stream" "application/json")}
          all-headers (merge-headers base-headers headers)
          init (cond-> {:method (-> method name str/upper-case)
                        :headers all-headers}
                 body (assoc :body body)
                 signal (assoc :signal signal))
          req {:method method :url url
               :headers (merge base-headers headers)
               :body body}
          controller (when (and timeout-ms (not stream?) (not signal))
                       (js/AbortController.))
          init (if controller (assoc init :signal (.-signal controller)) init)
          timer (when controller
                  (js/setTimeout #(.abort controller) timeout-ms))
          started (js/Date.now)]
    (safe-invoke! (:on-request client) req)
    (-> (js/fetch url (->js init))
        (p/then
         (fn [resp]
           (when timer (js/clearTimeout timer))
           (let [status (.-status resp)
                 elapsed (- (js/Date.now) started)]
             (if stream?
               (do (safe-invoke! (:on-response client) req
                                 {:status status :elapsed-ms elapsed})
                   {:status status :stream (.-body resp)})
               (p/let [body-text (.text resp)]
                 (safe-invoke! (:on-response client) req
                               {:status status :body body-text :elapsed-ms elapsed})
                 {:status status :body-text body-text
                  :content-type (.. resp -headers (get "content-type"))})))))
        (p/catch
         (fn [e]
           (when timer (js/clearTimeout timer))
           (safe-invoke! (:on-error client) req e)
           (throw e))))))


(defn authed
  "Run an authenticated fetch. On 401, invalidate the token cache (if
   supported) and retry once. A second 401 throws UNAUTHORIZED. Returns
   a promise of `{:status :body-text}` or `{:status :stream}` for SSE."
  [method url opts]
  (let [client (core/the-client)]
   (-> (authed-once client method url opts)
      (p/then
       (fn [resp]
         (if (and (= 401 (:status resp)) (:invalidate-fn client))
           (do (invalidate! client)
               (authed-once client method url opts))
           resp)))
      (p/then
       (fn [resp]
         (if (= 401 (:status resp))
           (throw (ex-info "Unauthorized"
                           {:code "UNAUTHORIZED" :status 401}))
           resp)))
      (p/catch
       (fn [e]
         (if (instance? ExceptionInfo e)
           (throw e)
           (throw (ex-info (str "Transport error: " (ex-message e))
                           {:code "TRANSPORT_ERROR" :cause e}))))))))


(defn- ensure-2xx
  [{:keys [status body-text content-type]} context]
  (if (<= 200 status 299)
    (parse-body body-text content-type)
    (let [parsed (parse-body body-text content-type)]
      (throw (ex-info (or (get-in parsed [:error :message])
                          (str context " failed: HTTP " status))
                      {:code (or (get-in parsed [:error :code]) "HTTP_ERROR")
                       :status status
                       :body (or parsed body-text)})))))


(defn request
  "POST to /data with the given body (transit). Returns a promise of the
   parsed response — temporal values come back as real js/Date."
  [body]
  (let [client (core/the-client)]
    (p/let [resp (authed :post (str (:endpoint client) "/data")
                         {:body (transit/write tw body)
                          :headers {"Content-Type" "application/transit+json"
                                    "Accept" "application/transit+json"}
                          :timeout-ms (:request-timeout client)})]
      (ensure-2xx resp "Request"))))


(defn get-json
  "Authenticated GET returning parsed JSON (promise)."
  [url {:keys [query-params headers]}]
  (let [qs (when (seq query-params)
             (let [p (js/URLSearchParams.)]
               (doseq [[k v] query-params]
                 (.append p (name k) (str v)))
               (str "?" (.toString p))))
        full-url (str url qs)]
    (p/let [resp (authed :get full-url
                         {:headers headers
                          :timeout-ms (:request-timeout (core/the-client))})]
      (ensure-2xx resp "GET"))))


(defn post-json
  "Authenticated POST with JSON body, returning parsed JSON (promise)."
  [url body]
  (p/let [resp (authed :post url
                       {:body (js/JSON.stringify (->js body))
                        :headers {"Content-Type" "application/json"}
                        :timeout-ms (:request-timeout (core/the-client))})]
    (ensure-2xx resp "POST")))


(defn- ensure-2xx-onboard
  "Like ensure-2xx, but /oauth/onboard and /oauth/onboard/complete's error
   wire shape is {:error \"<code>\"} — a bare string, not the
   {:error {:code :message}} /data envelope — so it needs its own mapping.
   ensure-2xx's (get-in parsed [:error :code]) would silently return nil
   against a string :error and fall through to a generic HTTP_ERROR, losing
   the actual code. `label` names the failing call in the thrown message."
  [label {:keys [status body-text content-type]}]
  (if (<= 200 status 299)
    (parse-body body-text content-type)
    (let [parsed (parse-body body-text content-type)
          code (some-> (:error parsed) str/upper-case)]
      (if code
        (throw (ex-info (str label " failed: " code) {:code code :status status}))
        (throw (ex-info (str label " request failed: HTTP " status)
                        {:code "HTTP_ERROR" :status status :body body-text}))))))


(defn onboard
  "Authenticated POST to /oauth/onboard, returning parsed JSON (promise)."
  [body]
  (p/let [resp (authed :post (str (:endpoint (core/the-client)) "/oauth/onboard")
                       {:body (js/JSON.stringify (->js body))
                        :headers {"Content-Type" "application/json"}
                        :timeout-ms (:request-timeout (core/the-client))})]
    (ensure-2xx-onboard "onboard" resp)))


(defn onboard-complete
  "Authenticated POST to /oauth/onboard/complete, returning parsed JSON (promise)."
  [body]
  (p/let [resp (authed :post (str (:endpoint (core/the-client)) "/oauth/onboard/complete")
                       {:body (js/JSON.stringify (->js body))
                        :headers {"Content-Type" "application/json"}
                        :timeout-ms (:request-timeout (core/the-client))})]
    (ensure-2xx-onboard "onboard-complete" resp)))


(defn flush-subs!
  "POST the connected client's local subscription mirror as the full
   set (the /data/subscription/set endpoint is full-replace). Returns a
   Promise."
  []
  (post-json (str (base-url) "/data/subscription/set")
             (core/build-subscriptions-body @(:subs (core/the-client)))))
