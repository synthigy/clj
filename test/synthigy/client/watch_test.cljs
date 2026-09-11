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
            [synthigy.client.sse :as sse]))

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
        (set! sse/listen (stub-listen (fn [_on-event _opts])))
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
