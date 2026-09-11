(ns synthigy.client.sse
  "SSE listener for the Synthigy CLJS client.

   Uses fetch streaming (ReadableStream + TextDecoder) — supports
   Authorization headers, matches sdk/js. Callback-driven: caller
   provides `on-event`; `listen` returns a 0-arg `stop` function that
   aborts the connection.

   Auto-reconnects with Last-Event-ID + exponential backoff (1s → 30s)."
  (:require
   [clojure.string :as str]
   [promesa.core :as p]
   [synthigy.client.http :as http]))


(defn- parse-frame
  [event-type data-lines]
  (when (seq data-lines)
    (try
      (let [payload (js->clj (js/JSON.parse (str/join "\n" data-lines))
                             :keywordize-keys true)]
        ;; The delta kind lives in the JSON payload's `:type` (record/update,
        ;; relation/link, entity/touched, …). The SSE `event:` field is always
        ;; "data" — only a fallback for a frame with no payload type.
        (cond-> payload
          (not (contains? payload :type)) (assoc :type event-type)))
      (catch :default _ nil))))


(defn- default-on-parse-error
  [e raw]
  (js/console.warn "synthigy.client SSE parse error:" (ex-message e) "— raw:" raw))


(defn- read-stream!
  [stream on-event on-parse-error last-id-atom stopped?]
  (let [reader (.getReader stream)
        decoder (js/TextDecoder.)]
    (letfn [(loop-read [buf event-type data-lines event-id]
              (if @stopped?
                (do (try (.cancel reader) (catch :default _)) nil)
                (-> (.read reader)
                    (p/then
                     (fn [r]
                       (if (.-done r)
                         nil
                         (let [chunk (.decode decoder (.-value r) #js {:stream true})
                               buf' (str buf chunk)
                               lines (str/split buf' #"\n" -1)
                               tail (last lines)
                               complete (butlast lines)]
                           (loop [[line & more] complete
                                  et event-type
                                  dl data-lines
                                  eid event-id]
                             (if (nil? line)
                               (loop-read tail et dl eid)
                               (cond
                                 (str/starts-with? line "event:")
                                 (recur more (str/trim (subs line 6)) dl eid)

                                 (str/starts-with? line "data:")
                                 (recur more et (conj dl (str/trim (subs line 5))) eid)

                                 (str/starts-with? line "id:")
                                 (recur more et dl (str/trim (subs line 3)))

                                 (= line "")
                                 (do (when-let [payload (parse-frame et dl)]
                                       (try (on-event payload)
                                            (catch :default e (on-parse-error e (str/join "\n" dl)))))
                                     (when eid (reset! last-id-atom eid))
                                     (recur more "message" [] nil))

                                 :else
                                 (recur more et dl eid)))))))))))]
      (loop-read "" "message" [] nil))))


(defn listen
  "Stream SSE notifications from /data/events, calling `on-event` with
   each `{:type :entity :relations :xids}` notification.

   Returns a 0-arg function that stops the listener (aborts the fetch,
   halts reconnect loop).

   Options:
     :max-delay       — cap for reconnect backoff ms (default 30000)
     :on-parse-error  — (fn [error raw]) for malformed frames
                        (default logs to console.warn)
     :on-connect      — 0-arg fn called after every successful (re)connect —
                        used to re-assert server-side state (e.g. re-POST the
                        subscription set, which may have been lost/replaced
                        while disconnected). Exceptions are swallowed.

   UNAUTHORIZED / FORBIDDEN propagate (no reconnect); other transport
   errors trigger reconnect with backoff."
  ([on-event] (listen on-event nil))
  ([on-event {:keys [max-delay on-parse-error on-connect]
                     :or {max-delay 30000
                          on-parse-error default-on-parse-error}}]
   (let [stopped? (atom false)
         last-id (atom nil)
         controller (atom nil)
         url (str (http/base-url) "/data/events")]
     (letfn [(connect [delay]
               (when-not @stopped?
                 (let [ctrl (js/AbortController.)
                       _ (reset! controller ctrl)
                       hdrs (cond-> {}
                              @last-id (assoc "Last-Event-ID" @last-id))]
                   (-> (http/authed :get url
                                    {:headers hdrs
                                     :stream? true
                                     :signal (.-signal ctrl)})
                       (p/then
                        (fn [resp]
                          (let [status (:status resp)]
                            (if (<= 200 status 299)
                              (do
                                (when on-connect
                                  (try (on-connect) (catch :default _)))
                                (p/then (read-stream! (:stream resp) on-event
                                                      on-parse-error last-id stopped?)
                                        (fn [_]
                                          (when-not @stopped?
                                            (reconnect 1000)))))
                              (throw (ex-info (str "SSE connect failed: HTTP " status)
                                              {:code "HTTP_ERROR" :status status}))))))
                       (p/catch
                        (fn [e]
                          (cond
                            @stopped? nil
                            (contains? #{"UNAUTHORIZED" "FORBIDDEN"}
                                       (:code (ex-data e)))
                            (throw e)
                            :else
                            (reconnect delay))))))))
             (reconnect [delay]
               (when-not @stopped?
                 (p/then (p/delay delay)
                         (fn [_] (connect (min (* delay 2) max-delay))))))]
       (connect 1000)
       (fn stop []
         (reset! stopped? true)
         (when-let [c @controller]
           (try (.abort c) (catch :default _))))))))
