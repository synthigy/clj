(ns synthigy.client.watch-test
  "Browser (CLJS) watch multiplexer — same shared pure logic as the JVM
   client (synthigy.client.core/mux-union, /event-matches?, exhaustively
   covered by synthigy.client.watch-test in test/synthigy/client/watch_test.clj),
   plus the CLJS-specific transport wiring: plain-callback fan-out (no
   core.async — JS has no second thread to protect a sink from blocking),
   and the flush-serialization fix ported from sdk/js's WatchMultiplexer
   (a bare `swap!` + fire-and-forget POST would let two overlapping flushes
   race over the network and let a stale, smaller union land last).

   No real client/server — `synthigy.client.sse/listen` and
   `synthigy.client.http/post-json` are stubbed by directly `set!`-ing them
   (NOT `with-redefs`/`binding`: both restore their original value the
   instant the wrapping form's SYNCHRONOUS body returns, which is before
   any `js/setTimeout`/promise continuation in these async tests runs —
   the stub would already be gone, and `core/*client*` back to nil, by
   the time the continuation fires. `stub-listen` gives its replacement
   the SAME 2-arity shape as the real `sse/listen`: the call site in
   client.cljs is compiled against the ORIGINAL var's multi-arity shape
   as a direct `cljs$core$IFn$_invoke$arity$2` dispatch, and a plain
   single-arity replacement fn has no such property — the call throws
   \"is not a function\" at runtime otherwise."
  (:require [cljs.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [synthigy.client :as client]
            [synthigy.client.core :as core]
            [synthigy.client.http :as http]
            [synthigy.client.sse :as sse]
            [synthigy.client.subscriptions :as subs]))

(defn- fake-client []
  (core/create-client {:endpoint "http://x" :token "t"}))

(defn- stub-listen
  ([handler] (stub-listen handler (fn [] nil)))
  ([handler stop-fn]
   (fn stub
     ([on-event] (stub on-event nil))
     ([on-event opts]
      (handler on-event opts)
      stop-fn))))

;; ── shared core logic compiles + behaves the same under CLJS ───────────────

(deftest shared-mux-logic-smoke
  (testing "core/event-matches? and core/mux-union — same fns the JVM client uses"
    (is (true?  (core/event-matches? {:records ["u-1"]} {:type "record/update" :record-xid "u-1"})))
    (is (false? (core/event-matches? {:records ["u-1"]} {:type "record/update" :record-xid "u-9"})))
    (is (= #{"a" "b" "c"}
           (:records (core/mux-union {:w1 {:interest {:records ["a" "b"]}}
                                      :w2 {:interest {:records ["b" "c"]}}}))))))

;; ── dispatch: fan-out to every matching watcher; a throwing sink must not
;;    take down delivery to the OTHERS (JS has no thread to isolate a slow
;;    sink behind, but a synchronously-throwing sink must still be caught). ──

(deftest watch-dispatch-fans-out-and-isolates-a-throwing-sink
  (testing "two watches, one entity each — each gets only its own events; a throwing sink doesn't block the other"
    (async done
      (let [orig-listen sse/listen
            orig-post http/post-json
            captured-on-event (atom nil)
            got-b (atom nil)]
        (set! sse/listen (stub-listen (fn [on-event _opts] (reset! captured-on-event on-event))))
        (set! http/post-json (fn [_url _body] (p/resolved {})))
        (set! core/*client* (fake-client))
        (let [wa (client/watch {:entities ["A"]} (fn [_ev] (throw (js/Error. "sink boom"))))
              wb (client/watch {:entities ["B"]} (fn [ev] (reset! got-b ev)))]
          ;; both watches registered synchronously — the on-event callback
          ;; is already captured by the (stubbed) sse/listen call above.
          (@captured-on-event {:type "entity/touched" :entity "B"})
          (js/setTimeout
           (fn []
             (is (= "B" (:entity @got-b)) "watch B received its matching event")
             ;; NOT (:close wa)/(:close wb) here: close schedules its own
             ;; unregister flush via mux-flush! (a queued microtask, then a
             ;; chained promise) that would still be PENDING when we restore
             ;; the stubs below — it would then fire later, mid-flight, into
             ;; the NEXT test's (different) stubbed http/post-json. Nothing
             ;; here tests close's own cleanup (disconnect-stops-listener-
             ;; and-clears-state does), so just leave the watches be.
             (set! sse/listen orig-listen)
             (set! http/post-json orig-post)
             (set! core/*client* nil)
             (done))
           0))))))

;; ── flush serialization: the exact race the sdk/js WatchMultiplexer guards
;;    against — a second flush must not fire its POST until the first one
;;    has settled, so the server can never observe a stale union landing
;;    after a newer one. ──────────────────────────────────────────────────

(deftest mux-flush-serializes-overlapping-posts
  (testing "a flush triggered while one is already in flight waits for it to settle first"
    (async done
      (let [orig-listen sse/listen
            orig-post http/post-json
            events (atom [])]
        (set! sse/listen (stub-listen (fn [_on-event opts] ((:on-connect opts) "s-1"))))
        (set! http/post-json (fn [_url _body]
                                (swap! events conj :start)
                                (p/then (p/delay 20)
                                        (fn [_] (swap! events conj :end) {}))))
        (set! core/*client* (fake-client))
        (let [w (client/watch {:entities ["A"]} (fn [_ev]))]
          ;; A setTimeout(0) macrotask boundary guarantees flush A's queued
          ;; microtask AND its chained `p/then` have both already run — so
          ;; its `:start` is recorded — while its 20ms `p/delay` (:end) is
          ;; still pending. THEN trigger the second flush: it must wait for
          ;; flush A's promise to settle before starting its own POST.
          (js/setTimeout
           (fn []
             ((:add w) ["x-1"])
             (js/setTimeout
              (fn []
                ;; strictly sequential: the second POST must not have STARTED
                ;; until the first one ENDED — never [:start :start :end :end].
                (is (= [:start :end :start :end] @events)
                    (str "flushes overlapped instead of serializing: " @events))
                ;; NOT (:close w) here — see the comment in
                ;; watch-dispatch-fans-out-and-isolates-a-throwing-sink for why.
                (set! sse/listen orig-listen)
                (set! http/post-json orig-post)
                (set! core/*client* nil)
                (done))
              ;; flush A settles ~20ms after it starts; flush B then starts
              ;; and takes another ~20ms — 80ms gives comfortable margin
              ;; over the ~40ms critical path against test-runner jitter.
              80))
           0))))))

;; ── disconnect!: stops the SSE listener and clears watches/subs ────────────

(deftest disconnect-stops-listener-and-clears-state
  (testing "disconnect! calls the SSE stop fn, empties :watches, resets :subs, and POSTs the empty set"
    (async done
      (let [orig-listen sse/listen
            orig-post http/post-json
            c (fake-client)
            stopped? (atom false)]
        (set! sse/listen (stub-listen (fn [_on-event _opts]) (fn [] (reset! stopped? true))))
        (set! http/post-json (fn [_url _body] (p/resolved {})))
        (set! core/*client* c)
        (client/watch {:entities ["A"]} (fn [_ev]))
        (-> (client/disconnect!)
            (p/then (fn [_]
                      (is (true? @stopped?) "SSE listener was stopped")
                      (is (empty? (:watches @(:mux c))) "watches cleared")
                      (is (nil? core/*client*) "client uninstalled")))
            (p/catch (fn [e] (is false (str "disconnect! rejected: " (ex-message e)))))
            (p/then (fn [_]
                      (set! sse/listen orig-listen)
                      (set! http/post-json orig-post)
                      (set! core/*client* nil)
                      (done))))))))

;; ── watch-query: Promise<atom>, kept fresh on matching events ─────────────

(deftest watch-query-resolves-to-a-live-atom
  (testing "resolves to an atom with the initial snapshot; a matching event triggers a background refetch"
    (async done
      (let [orig-listen sse/listen
            orig-post http/post-json
            orig-search client/search
            n (atom 0)
            captured-on-event (atom nil)]
        (set! sse/listen (stub-listen (fn [on-event _opts] (reset! captured-on-event on-event))))
        (set! http/post-json (fn [_url _body] (p/resolved {})))
        (set! client/search (fn [_entity _args _selection & _opts]
                              (swap! n inc)
                              (p/resolved [{:xid (str "row-" @n)}])))
        (set! core/*client* (fake-client))
        (letfn [(restore! []
                  (set! sse/listen orig-listen)
                  (set! http/post-json orig-post)
                  (set! client/search orig-search)
                  (set! core/*client* nil))]
          (-> (client/watch-query :thing nil [:xid] :debounce-ms 5)
              (p/then
               (fn [live]
                 (is (= [{:xid "row-1"}] @live) "initial snapshot present")
                 (@captured-on-event {:type "entity/touched" :entity "thing"})
                 (js/setTimeout
                  (fn []
                    (is (= [{:xid "row-2"}] @live) "refetched after a matching poke")
                    ;; NOT close-watch! here — see the comment in
                    ;; watch-dispatch-fans-out-and-isolates-a-throwing-sink
                    ;; for why a trailing close's dangling flush must not be
                    ;; left pending across the restore.
                    (restore!)
                    (done))
                  30)))
              (p/catch (fn [e]
                         (is false (str "watch-query rejected: " (ex-message e)))
                         (restore!)
                         (done)))))))))

(def refusal {:code "FORBIDDEN" :message "no" :hint "h" :rule "stamp" :doc "errors/stamp"})

(defn assert-rejected [ev]
  (is (= ["subscription/rejected" "no" []] ((juxt :type :reason :records) ev)))
  (is (= (dissoc refusal :message) (select-keys (ex-data (:error ev)) [:code :hint :rule :doc]))))

(deftest a-refused-subscribe-reaches-the-watch
  (async done
    (let [orig-listen sse/listen
          orig-post http/post-json
          got (atom nil)]
      (set! sse/listen (stub-listen (fn [_on-event opts] ((:on-connect opts) "s-1"))))
      (set! http/post-json (fn [_url _body] (p/rejected (http/server-error refusal "POST" 403 nil))))
      (set! core/*client* (fake-client))
      (client/watch {:records ["r-1"]} #(reset! got %))
      (js/setTimeout
       (fn []
         (assert-rejected @got)
         (set! sse/listen orig-listen)
         (set! http/post-json orig-post)
         (set! core/*client* nil)
         (done))
       30))))

(deftest a-refused-refresh-reaches-on-event
  (async done
    (let [orig-listen sse/listen
          orig-post http/post-json
          orig-search client/search
          n (atom 0)
          got (atom nil)
          captured-on-event (atom nil)]
      (set! sse/listen (stub-listen (fn [on-event _opts] (reset! captured-on-event on-event))))
      (set! http/post-json (fn [_url _body] (p/resolved {})))
      (set! client/search (fn [_entity _args _selection & _opts]
                            (if (= 1 (swap! n inc))
                              (p/resolved [{:xid "row-1"}])
                              (p/rejected (http/server-error refusal "Request" 403 nil)))))
      (set! core/*client* (fake-client))
      (letfn [(restore! []
                (set! sse/listen orig-listen)
                (set! http/post-json orig-post)
                (set! client/search orig-search)
                (set! core/*client* nil))]
        (-> (client/watch-query :thing nil [:xid] :debounce-ms 5 :on-event #(reset! got %))
            (p/then
             (fn [live]
               (@captured-on-event {:type "entity/touched" :entity "thing"})
               (js/setTimeout
                (fn []
                  (assert-rejected @got)
                  (is (= [{:xid "row-1"}] @live) "a failed refresh keeps the last rows")
                  (restore!)
                  (done))
                30)))
            (p/catch (fn [e]
                       (is false (str "watch-query rejected: " (ex-message e)))
                       (restore!)
                       (done))))))))

(deftest a-refused-stream-reaches-every-watch-and-the-next-watch-reconnects
  (async done
    (let [orig-listen sse/listen
          orig-post http/post-json
          listens (atom 0)
          got (atom [])]
      (set! sse/listen (stub-listen (fn [_on-event opts]
                                      (when (= 1 (swap! listens inc))
                                        (js/setTimeout #((:on-error opts) (http/server-error refusal "SSE connect" 403 nil)) 5)))))
      (set! http/post-json (fn [_url _body] (p/resolved {:ok true})))
      (set! core/*client* (fake-client))
      (client/watch {:records ["r-1"]} #(swap! got conj %))
      (client/watch {:records ["r-2"]} #(swap! got conj %))
      (js/setTimeout
       (fn []
         (is (= 2 (count @got)) "both watches hear the refusal")
         (doseq [ev @got] (assert-rejected ev))
         (client/watch {:records ["r-3"]} (fn [_]))
         (is (= 2 @listens) "a watch after the refusal opens a new stream")
         (set! sse/listen orig-listen)
         (set! http/post-json orig-post)
         (set! core/*client* nil)
         (done))
       40))))

;; ── per-stream subscription sets, reconnect sentinel, backfill ─────────────

(defn stub!
  "Installs a listen stub that hands its opts to `opened`, a post-json stub and a fresh client; returns the restore fn."
  [opened post]
  (let [orig-listen sse/listen
        orig-post http/post-json]
    (set! sse/listen (stub-listen (fn [on-event opts] (opened on-event opts))))
    (set! http/post-json post)
    (set! core/*client* (fake-client))
    (fn []
      (set! sse/listen orig-listen)
      (set! http/post-json orig-post)
      (set! core/*client* nil))))

(defn finish [restore done]
  (fn [e]
    (when e (is false (str "test threw: " (ex-message e))))
    (restore)
    (done)))

(defn stream-gone [] (http/server-error {:code "STREAM_NOT_FOUND" :message "gone"} "POST" 400 nil))

(deftest every-subscription-post-names-the-stream-it-is-for
  (async done
    (let [streams (atom [])
          conn (atom nil)
          restore (stub! (fn [_ opts] (reset! conn (:on-connect opts)) ((:on-connect opts) "s-first"))
                         (fn [_ body] (swap! streams conj (:stream body)) (p/resolved {})))
          w (client/watch {:records ["u-1"]} (fn [_]))]
      (-> (p/delay 10)
          (p/then (fn [_] (@conn "s-second") (p/delay 10)))
          (p/then (fn [_]
                    (is (= ["s-first" "s-second"] @streams))
                    ((:close w))))
          (p/handle (fn [_ e] ((finish restore done) e)))))))

(deftest a-watch-opened-after-the-stream-closed-waits-for-the-new-stream
  (async done
    (let [streams (atom [])
          n (atom 0)
          restore (stub! (fn [_ opts] ((:on-connect opts) (str "s-" (swap! n inc))))
                         (fn [_ body] (swap! streams conj (:stream body)) (p/resolved {})))
          first-w (client/watch {:records ["u-1"]} (fn [_]))]
      (-> (p/delay 10)
          (p/then (fn [_] ((:close first-w)) (p/delay 10)))
          (p/then (fn [_]
                    (is (= ["s-1"] @streams) "closing the last watch closes the stream instead of posting")
                    (let [w (client/watch {:records ["u-2"]} (fn [_]))]
                      (p/then (p/delay 10) (fn [_] ((:close w)))))))
          (p/then (fn [_] (is (= ["s-1" "s-2"] @streams) "no POST went out with the closed stream's id")))
          (p/handle (fn [_ e] ((finish restore done) e)))))))

(deftest a-stream-the-engine-no-longer-has-is-not-a-rejected-subscription
  (async done
    (let [calls (atom 0)
          seen (atom [])
          restore (stub! (fn [_ opts] ((:on-connect opts) "s-1"))
                         (fn [_ _] (if (= 2 (swap! calls inc)) (p/rejected (stream-gone)) (p/resolved {}))))
          w (client/watch {:records ["u-1"]} #(swap! seen conj (:type %)))
          mux (:mux core/*client*)]
      (-> (p/delay 10)
          (p/then (fn [_] ((:add w) ["u-2"]) (p/delay 10)))
          (p/then (fn [_]
                    (is (not-any? #{"subscription/rejected"} @seen))
                    (is (nil? (:stream @mux)) "the dead id is dropped until the next open")
                    ((:add w) ["u-3"])
                    (p/delay 10)))
          (p/then (fn [_]
                    (is (= 2 @calls) "nothing is posted without a stream")
                    ((:close w))))
          (p/handle (fn [_ e] ((finish restore done) e)))))))

(deftest a-dead-stream-refusing-an-old-post-leaves-the-new-stream-in-charge
  (async done
    (let [posts (atom [])
          release (p/deferred)
          conn (atom nil)
          restore (stub! (fn [_ opts] (reset! conn (:on-connect opts)) ((:on-connect opts) "s-old"))
                         (fn [_ body]
                           (swap! posts conj (:stream body))
                           (if (and (= "s-old" (:stream body)) (> (count @posts) 1))
                             (p/then release (fn [_] (throw (stream-gone))))
                             (p/resolved {}))))
          w (client/watch {:records ["u-1"]} (fn [_]))
          mux (:mux core/*client*)]
      (-> (p/delay 10)
          (p/then (fn [_] ((:add w) ["u-2"]) (p/delay 10)))
          (p/then (fn [_] (@conn "s-new") (p/delay 10)))
          (p/then (fn [_] (p/resolve! release true) (p/delay 10)))
          (p/then (fn [_]
                    (is (= "s-new" (:stream @mux)) "the old refusal did not clear the new id")
                    (is (= "s-new" (last @posts)) "the union went to the new stream")
                    ((:close w))))
          (p/handle (fn [_ e] ((finish restore done) e)))))))

(deftest every-reconnect-tells-the-watch-it-resumed
  (async done
    (let [seen (atom [])
          restore (stub! (fn [_ opts] (doseq [s ["s-1" "s-2" "s-3"]] ((:on-connect opts) s)))
                         (fn [_ _] (p/resolved {})))
          w (client/watch {:records ["u-1"]} #(swap! seen conj (:type %)))]
      (-> (p/delay 10)
          (p/then (fn [_]
                    (is (= ["connection/resumed" "connection/resumed"] @seen))
                    ((:close w))))
          (p/handle (fn [_ e] ((finish restore done) e)))))))

(deftest a-live-query-re-runs-after-a-reconnect
  (async done
    (let [orig-search client/search
          runs (atom 0)
          conn (atom nil)
          restore (stub! (fn [_ opts] (reset! conn (:on-connect opts)) ((:on-connect opts) "s-1"))
                         (fn [_ _] (p/resolved {})))]
      (set! client/search (fn [& _] (p/resolved [{:xid (str "v" (swap! runs inc))}])))
      (-> (client/watch-query :thing nil [:xid] :debounce-ms 1)
          (p/then (fn [live]
                    (p/then (p/delay 20)
                            (fn [_]
                              (is (= 2 @runs) "snapshot, then one refetch once the subscription holds")
                              (@conn "s-2")
                              (p/then (p/delay 20)
                                      (fn [_]
                                        (is (= 3 @runs) "the reconnect re-ran the query")
                                        (client/close-watch! live)))))))
          (p/handle (fn [_ e]
                      (set! client/search orig-search)
                      ((finish restore done) e)))))))

(defn history-row [ts value]
  {:record-xid "u-1" :op "change" :ts ts :attribute-xid "name" :value value})

(defn label [ev]
  (if (= "connection/resumed" (:type ev)) "resumed" (get-in ev [:after :name])))

(defn backfill-run
  "Opens a backfilling watch, reconnects, delivers a live C while history is pending; resolves to what the watch saw."
  [history]
  (let [seen (atom [])
        asked (atom [])
        release (p/deferred)
        conn (atom nil)
        emit (atom nil)
        restore (stub! (fn [on-event opts]
                         (reset! emit on-event)
                         (reset! conn (:on-connect opts))
                         ((:on-connect opts) "s-1"))
                       (fn [url body]
                         (if (.endsWith url "/history")
                           (do (swap! asked conj body)
                               (p/then release (fn [_] (history))))
                           (p/resolved {}))))
        w (client/watch {:records ["u-1"]} #(swap! seen conj %) :backfill true)]
    (-> (p/delay 10)
        (p/then (fn [_]
                  (@emit {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:00:00Z" :after {:name "A"}})
                  (@conn "s-2")
                  (@emit {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:10:00Z" :after {:name "C"}})
                  (p/delay 10)))
        (p/then (fn [_]
                  (is (= ["A"] (map label @seen)) "nothing passes the replay while it runs")
                  (p/resolve! release true)
                  (p/delay 10)))
        (p/then (fn [_] ((:close w)) {:seen @seen :asked @asked}))
        (p/finally (fn [_ _] (restore))))))

(deftest backfill-replays-the-gap-before-the-sentinel-and-holds-live-events
  (async done
    (-> (backfill-run (fn [] {:result [(history-row "2026-11-18T09:00:00Z" "old")
                                       (history-row "2026-11-18T10:05:00Z" "B")]}))
        (p/then (fn [{:keys [seen asked]}]
                  (is (= ["A" "B" "resumed" "C"] (map label seen)))
                  (is (true? (:from-backfill (second seen))))
                  (is (= {:op "events" :opts {:record-xid "u-1" :track "entity" :limit 1000}}
                         (update (first asked) :opts dissoc :between)))
                  (is (= "2026-11-18T10:00:00Z" (first (get-in (first asked) [:opts :between])))
                      "the gap starts at the last event seen")))
        (p/handle (fn [_ e] (when e (is false (ex-message e))) (done))))))

(deftest a-failed-backfill-still-delivers-the-sentinel-and-held-events
  (async done
    (-> (backfill-run (fn [] (throw (js/Error. "history down"))))
        (p/then (fn [{:keys [seen]}] (is (= ["A" "resumed" "C"] (map label seen)))))
        (p/handle (fn [_ e] (when e (is false (ex-message e))) (done))))))

(defn flood-run
  "Opens a backfilling watch, reconnects, emits `n` live events while history is pending, optionally closes, then releases history."
  [n close-early?]
  (let [seen (atom [])
        release (p/deferred)
        conn (atom nil)
        emit (atom nil)
        restore (stub! (fn [on-event opts]
                         (reset! emit on-event)
                         (reset! conn (:on-connect opts))
                         ((:on-connect opts) "s-1"))
                       (fn [url _]
                         (if (.endsWith url "/history")
                           (p/then release (fn [_] {:result [(history-row "2026-11-18T10:05:00Z" "B")]}))
                           (p/resolved {}))))
        w (client/watch {:records ["u-1"]} #(swap! seen conj %) :backfill true)]
    (-> (p/delay 10)
        (p/then (fn [_]
                  (@emit {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:00:00Z" :after {:name "A"}})
                  (@conn "s-2")
                  (doseq [i (range n)]
                    (@emit {:type "record/update" :record-xid "u-1" :ts (str "2026-11-18T11:00:" (+ 100 i) "Z")
                            :after {:name (str "L" i)}}))
                  (p/delay 10)))
        (p/then (fn [_]
                  (when close-early? ((:close w)))
                  (p/resolve! release true)
                  (p/delay 20)))
        (p/then (fn [_] ((:close w)) @seen))
        (p/finally (fn [_ _] (restore))))))

(deftest a-slow-backfill-holds-every-live-event
  (async done
    (-> (flood-run 150 false)
        (p/then (fn [seen]
                  (is (= (concat ["A" "B" "resumed"] (map #(str "L" %) (range 150))) (map label seen)))))
        (p/handle (fn [_ e] (when e (is false (ex-message e))) (done))))))

(deftest closing-during-a-backfill-delivers-nothing-more
  (async done
    (-> (flood-run 5 true)
        (p/then (fn [seen] (is (= ["A"] (map label seen)))))
        (p/handle (fn [_ e] (when e (is false (ex-message e))) (done))))))

(deftest closing-during-a-backfill-stops-fetching-history
  (async done
    (let [calls (atom 0)
          release (p/deferred)
          conn (atom nil)
          emit (atom nil)
          restore (stub! (fn [on-event opts]
                           (reset! emit on-event)
                           (reset! conn (:on-connect opts))
                           ((:on-connect opts) "s-1"))
                         (fn [url _]
                           (if (.endsWith url "/history")
                             (do (swap! calls inc) (p/then release (fn [_] {:result []})))
                             (p/resolved {}))))
          w (client/watch {:records (mapv #(str "u-" %) (range 40))} (fn [_]) :backfill true)]
      (-> (p/delay 10)
          (p/then (fn [_]
                    (@emit {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:00:00Z" :after {:name "A"}})
                    (@conn "s-2")
                    (p/delay 10)))
          (p/then (fn [_]
                    (is (= 8 @calls) "the first batch is in flight")
                    ((:close w))
                    (p/resolve! release true)
                    (p/delay 20)))
          (p/then (fn [_] (is (= 8 @calls) "no batch is fetched after close")))
          (p/handle (fn [_ e] ((finish restore done) e)))))))

(deftest subscription-status-asks-about-the-watch-stream
  (async done
    (let [orig http/get-json
          asked (atom [])]
      (set! http/get-json (fn [url opts] (swap! asked conj [url opts]) (p/resolved {})))
      (set! core/*client* (assoc (fake-client) :mux (atom {:watches {} :stream "s-9"})))
      (subs/subscriptions)
      (set! core/*client* (fake-client))
      (subs/subscriptions)
      (is (= [["http://x/data/subscription/status" {:query-params {"stream" "s-9"}}]
              ["http://x/data/subscription/status" {}]]
             @asked))
      (set! http/get-json orig)
      (set! core/*client* nil)
      (done))))
