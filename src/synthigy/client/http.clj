(ns synthigy.client.http
  "HTTP transport for Synthigy client.

   ONE PROCESS, ONE CLIENT: every fn here reads the connected client from
   `synthigy.client.core/*client*` — nothing below the public API passes a
   client around (the Clojure server-restart idiom: `connect!` destroys the
   old client and installs the new one, so the root var is always the only
   live client). Each request resolves the client ONCE and uses that
   snapshot for token + retry + hooks.

   Tokens come from the client's `:token-fn` (0-arg for default token,
   1-arg for audience-scoped). On 401 the client's `:invalidate-fn` (if
   any) is called to force a token refresh, then the request is retried
   once. If that still fails with 401, UNAUTHORIZED is thrown."
  (:require
   [babashka.http-client :as http]
   [babashka.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [cognitect.transit :as transit]
   [synthigy.client.core :as core])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream]))


;; /data speaks transit so temporal values arrive as real java.util.Date (JSON
;; would flatten them to strings). Plain codec, no custom handlers — bodies are
;; plain maps/scalars; the deployed model rides as a pre-encoded transit STRING.
;; Writer is compact `:json`; the reader auto-detects verbose too.
(defn- ->transit
  [data]
  (let [out (ByteArrayOutputStream.)]
    (transit/write (transit/writer out :json) data)
    (.toString out "UTF-8")))

(defn- <-transit
  [^String s]
  (transit/read (transit/reader (ByteArrayInputStream. (.getBytes s "UTF-8")) :json)))


(defn token
  "Resolve a bearer token via the connected client's token-fn. Optional
   `audience` requests a token for another service (IdP federation)."
  ([] ((:token-fn (core/the-client))))
  ([audience] ((:token-fn (core/the-client)) audience)))


(defn- invalidate!
  [client]
  (when-let [f (:invalidate-fn client)]
    (f)))


(defn base-url
  "The connected client's server base URL. Sibling paths (/schema, /lint,
   /history, /oauth/onboard, /data/events, /data/subscription/set …) hang off
   this; /data itself is appended by `request`."
  []
  (:endpoint (core/the-client)))


(defn- dispatch
  [method url opts]
  (case method
    :get (http/get url opts)
    :post (http/post url opts)
    :put (http/put url opts)
    :delete (http/delete url opts)))


(defn- safe-invoke!
  [f & args]
  (when f
    (try (apply f args)
         (catch Throwable e
           (binding [*out* *err*]
             (println "synthigy.client hook error:" (.getMessage e)))))))


(defn- authed-once
  [client method url opts]
  (let [bearer ((:token-fn client))
        headers (merge {"Authorization" (str "Bearer " bearer)
                        "Accept" "application/json"}
                       (:headers opts))
        streaming? (= :stream (:as opts))
        with-timeout (if (and (:request-timeout client)
                              (not streaming?)
                              (not (contains? opts :timeout)))
                       (assoc opts :timeout (:request-timeout client))
                       opts)
        req {:method method :url url :headers headers :body (:body opts)}
        started (System/currentTimeMillis)]
    (safe-invoke! (:on-request client) req)
    (try
      (let [resp (dispatch method url (assoc with-timeout :headers headers :throw false))
            elapsed (- (System/currentTimeMillis) started)]
        (safe-invoke! (:on-response client) req
                      (assoc (select-keys resp [:status :headers :body])
                             :elapsed-ms elapsed))
        resp)
      (catch Throwable e
        (safe-invoke! (:on-error client) req e)
        (throw e)))))


(defn authed
  "Run an authenticated HTTP request against the connected client. On 401,
   invalidate the token cache (if the provider supports it) and retry once.
   A second 401 throws UNAUTHORIZED. Resolves the client ONCE — token,
   retry and hooks all use the same snapshot."
  [method url opts]
  (let [client (core/the-client)]
   (try
    (let [resp (let [r (authed-once client method url opts)]
                 (if (and (= 401 (:status r)) (:invalidate-fn client))
                   (do (invalidate! client)
                       (authed-once client method url opts))
                   r))]
      (when (= 401 (:status resp))
        (throw (ex-info "Unauthorized"
                        {:code "UNAUTHORIZED" :status 401})))
      resp)
    (catch clojure.lang.ExceptionInfo e (throw e))
    (catch Exception e
      (throw (ex-info (str "Transport error: " (.getMessage e))
                      {:code "TRANSPORT_ERROR"
                       :cause e}))))))


(defn- parse-json-body
  [body]
  (try (json/read-str body {:key-fn keyword})
       (catch Exception _ nil)))


(defn- response-content-type
  [resp]
  (let [h (:headers resp)]
    (or (get h "content-type") (get h "Content-Type")
        (some (fn [[k v]] (when (= "content-type" (str/lower-case (name k))) v)) h))))


(defn- parse-body
  "Decode a response body by what the server actually returned. /data replies
   transit (real java.util.Date, keyword keys); sibling endpoints (/schema,
   /lint, /logs) force JSON — so branch on the response Content-Type."
  [body content-type]
  (try
    (when (and body (not= "" body))
      (if (and content-type (str/includes? content-type "transit"))
        (<-transit body)
        (json/read-str body {:key-fn keyword})))
    (catch Exception _ nil)))


(defn- ensure-2xx
  [{:keys [status body] :as resp} context]
  (let [ct (response-content-type resp)]
    (if (<= 200 status 299)
      (parse-body body ct)
      (let [parsed (parse-body body ct)]
        (throw (ex-info (or (get-in parsed [:error :message])
                            (str context " failed: HTTP " status))
                        {:code (or (get-in parsed [:error :code]) "HTTP_ERROR")
                         :status status
                         :body (or parsed body)}))))))


(defn request
  "POST to /data with the given body (transit). Returns the parsed response —
   temporal values come back as real java.util.Date."
  [body]
  (ensure-2xx
    (authed :post (str (base-url) "/data")
            {:body (->transit body)
             :headers {"Content-Type" "application/transit+json"
                       "Accept" "application/transit+json"}})
    "Request"))


(defn get-json
  "Authenticated GET returning parsed JSON."
  [url opts]
  (ensure-2xx (authed :get url opts) "GET"))


(defn post-json
  "Authenticated POST with JSON body, returning parsed JSON."
  [url body]
  (ensure-2xx
    (authed :post url
            {:body (json/write-str body)
             :headers {"Content-Type" "application/json"}})
    "POST"))


(defn- ensure-2xx-onboard
  "Like ensure-2xx, but /oauth/onboard and /oauth/onboard/complete's error
   wire shape is {:error \"<code>\"} — a bare string, not the
   {:error {:code :message}} /data envelope — so it needs its own mapping.
   ensure-2xx's (get-in parsed [:error :code]) would silently return nil
   against a string :error and fall through to a generic HTTP_ERROR, losing
   the actual code. `label` names the failing call in the thrown message."
  [label {:keys [status body]}]
  (if (<= 200 status 299)
    (parse-json-body body)
    (let [parsed (parse-json-body body)
          code (some-> parsed :error str/upper-case)]
      (if code
        (throw (ex-info (str label " failed: " code) {:code code :status status}))
        (throw (ex-info (str label " request failed: HTTP " status)
                        {:code "HTTP_ERROR" :status status :body body}))))))


(defn onboard
  "Authenticated POST to /oauth/onboard, returning parsed JSON."
  [body]
  (ensure-2xx-onboard "onboard"
    (authed :post (str (base-url) "/oauth/onboard")
            {:body (json/write-str body)
             :headers {"Content-Type" "application/json"}})))


(defn onboard-complete
  "Authenticated POST to /oauth/onboard/complete, returning parsed JSON."
  [body]
  (ensure-2xx-onboard "onboard-complete"
    (authed :post (str (base-url) "/oauth/onboard/complete")
            {:body (json/write-str body)
             :headers {"Content-Type" "application/json"}})))


(defn flush-subs!
  "POST the connected client's local subscription mirror as the full set
   (the /data/subscription/set endpoint is full-replace)."
  []
  (post-json (str (base-url) "/data/subscription/set")
             (core/build-subscriptions-body @(:subs (core/the-client)))))


;; ============================================================================
;; SSE listener — /data/events
;; ============================================================================

(defn- sse-connect
  [last-event-id]
  (let [url (str (base-url) "/data/events")
        headers (cond-> {"Accept" "text/event-stream"}
                  last-event-id (assoc "Last-Event-ID" last-event-id))
        resp (authed :get url {:headers headers :as :stream})]
    (when-not (<= 200 (:status resp) 299)
      (throw (ex-info (str "SSE connect failed: HTTP " (:status resp))
                      {:code "HTTP_ERROR" :status (:status resp)})))
    (:body resp)))


(defn- dispatch-frame!
  [{:keys [event-type data-lines event-id]} on-event on-parse-error last-id]
  (when (seq data-lines)
    (let [raw (str/join "\n" data-lines)]
      (try
        ;; The delta kind lives in the JSON payload's `:type` (record/update,
        ;; relation/link, entity/touched, …). The SSE `event:` field is always
        ;; "data" — only a fallback for the rare frame with no payload type.
        (let [payload (json/read-str raw {:key-fn keyword})]
          (on-event (cond-> payload
                      (not (contains? payload :type)) (assoc :type event-type))))
        (catch Exception e
          (on-parse-error e raw)))))
  (when event-id (reset! last-id event-id)))


(defn- read-sse-stream!
  [^java.io.BufferedReader rdr on-event on-parse-error stop? last-id]
  (loop [state {:event-type "message" :data-lines []}]
    (when-not (stop?)
      (when-let [line (.readLine rdr)]
        (cond
          (.isEmpty ^String line)
          (do (dispatch-frame! state on-event on-parse-error last-id)
              (recur {:event-type "message" :data-lines []}))

          (str/starts-with? line "event:")
          (recur (assoc state :event-type (str/trim (subs line 6))))

          (str/starts-with? line "data:")
          (recur (update state :data-lines (fnil conj []) (str/trim (subs line 5))))

          (str/starts-with? line "id:")
          (recur (assoc state :event-id (str/trim (subs line 3))))

          :else
          (recur state))))))


(defn- default-on-parse-error
  [^Throwable e raw]
  (binding [*out* *err*]
    (println "synthigy.client SSE parse error:" (.getMessage e) "— raw:" raw)))


(defn sse-listen
  "BLOCKS the calling thread. Streams SSE events from /data/events, calling
   `on-event` with each `{:type :entity :relations :xids}` notification.

   Auto-reconnects with Last-Event-ID and exponential backoff (1s → max-delay).
   UNAUTHORIZED/FORBIDDEN propagate; other errors trigger reconnect.

   Options:
     :stop?           0-arg predicate — truthy stops the loop between frames
     :max-delay       cap for reconnect backoff in ms (default 30000)
     :on-connect      0-arg fn called after every successful (re)connect —
                      used to re-assert server-side state (e.g. re-POST the
                      subscription set, which may have been lost/replaced
                      while disconnected). Exceptions are swallowed.
     :on-parse-error  fn [Throwable raw-payload] — called when a frame's
                      data fails JSON parsing. Default logs to *err*."
  [on-event {:keys [stop? max-delay on-connect on-parse-error]
                    :or {max-delay 30000
                         on-parse-error default-on-parse-error}}]
  (let [stop?* (or stop? (constantly false))
        last-id (atom nil)]
    (loop [delay 1000]
      (when-not (stop?*)
        (let [next-delay
              (try
                (with-open [rdr (io/reader (sse-connect @last-id))]
                  (when on-connect (try (on-connect) (catch Exception _)))
                  (read-sse-stream! rdr on-event on-parse-error stop?* last-id))
                1000
                (catch clojure.lang.ExceptionInfo e
                  (if (contains? #{"UNAUTHORIZED" "FORBIDDEN"} (:code (ex-data e)))
                    (throw e)
                    delay))
                (catch Exception _
                  delay))]
          (when-not (stop?*)
            (Thread/sleep next-delay)
            (recur (min (* next-delay 2) max-delay))))))))
