(ns demo.core
  "Real-browser verification for the CLJS SDK's watch/mux port (Phase 1).
   Not a shipped example — a throwaway harness driven by Playwright to prove
   fetch + fetch-streaming SSE + the mux actually work in a REAL browser
   (the shadow-cljs :node-test target only proves the code runs under Node,
   which has no CORS enforcement and a different fetch/stream implementation).

   Config comes from the URL query string (?endpoint=…&token=…&xid=…&title=…)
   — set by the test harness, not hardcoded, since the token is minted fresh
   per run."
  (:require [synthigy.client :as client]
            [promesa.core :as p]))

(defn- $ [id] (.getElementById js/document id))

(defn- qp [k]
  (.get (js/URLSearchParams. (.-search js/location)) k))

(defn- set-text! [id text]
  (set! (.-textContent ($ id)) text))

(defn- finish! [pass? msg]
  (set-text! "test-result" (str (if pass? "PASS" "FAIL") ": " msg)))

(defn init []
  (let [endpoint (qp "endpoint")
        token (qp "token")
        xid (qp "xid")
        orig-title (qp "title")
        demo-title (str orig-title " (demo-live-update)")]
    (set-text! "endpoint" endpoint)
    (client/connect! {:endpoint endpoint :token token})
    (set-text! "status" "connected")
    (-> (client/search :music_track {:xid {:_eq xid}} [:xid :title])
        (p/then
         (fn [rows]
           (set-text! "search-result" (pr-str rows))
           ;; entity-track false + :records [xid] — scope the watch to just
           ;; this ONE record, not the whole music_track entity (low traffic,
           ;; deterministic — the entity-wide track would also fire on
           ;; unrelated writes from other demo runs / seed data).
           (client/watch-query :music_track {:xid {:_eq xid}} [:xid :title]
                                :entity-track false :records [xid])))
        (p/then
         (fn [live]
           (set-text! "live-title" (pr-str @live))
           (set! (.-__liveAtom js/window) live)
           (let [restored? (atom false)
                 timeout-id
                 (js/setTimeout
                  (fn []
                    (when-not @restored?
                      (reset! restored? true)
                      (finish! false "live update never arrived within 8s")))
                  8000)]
             (add-watch live ::demo
                        (fn [_ _ _ rows]
                          (set-text! "live-title" (pr-str rows))
                          (when (and (not @restored?)
                                     (some #(= demo-title (:title %)) rows))
                            (reset! restored? true)
                            (js/clearTimeout timeout-id)
                            (finish! true "watch-query reflected the live sync")
                            ;; best-effort cleanup — restore the original title
                            ;; so this demo run doesn't leave the shared dev DB
                            ;; altered.
                            (client/sync :music_track {:xid xid :title orig-title}))))
             ;; Trigger the write AFTER the watch is registered (mux-flush!'s
             ;; subscription POST has already fired synchronously inside
             ;; watch-query's mux-register! — the SSE stream is already
             ;; listening for this xid by the time we get here).
             (client/sync :music_track {:xid xid :title demo-title}))))
        (p/catch
         (fn [e]
           (finish! false (str "threw: " (ex-message e) " " (pr-str (ex-data e)))))))))
