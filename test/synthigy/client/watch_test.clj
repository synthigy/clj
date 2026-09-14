(ns synthigy.client.watch-test
  "Pure-logic tests for the watch multiplexer's event matcher — no server.
   Guards the live /data/events event-shape contract: record/* discriminated
   by :record-xid, relation/* by data[0] (the subscribed endpoint), and the
   coalesced pokes entity/touched by :entity, relation/touched by :relation.
   These lock down the fix for the pre-migration matcher that treated record
   deltas as opaque and broadcast them to every watcher.

   Also covers the core.async decoupling (spawn-watch-channel!,
   debounced-refetch!) — a slow subscriber sink must never block another
   subscriber's delivery, and a burst of pokes must coalesce to ONE
   refetch. No client/server needed: these are pure functions taking their
   own atoms/channels, not `(core/the-client)`."
  (:require [clojure.core.async :as a]
            [clojure.test :refer [deftest is testing]]
            [synthigy.client :as client]
            [synthigy.client.core :as core]
            [synthigy.client.http :as http]))

(def matches?             #'core/event-matches?)
(def mux-union            #'core/mux-union)
(def dispatch-frame!      #'http/dispatch-frame!)
(def spawn-watch-channel! #'client/spawn-watch-channel!)
(def debounced-refetch!   #'client/debounced-refetch!)
(def mux-dispatch         #'client/mux-dispatch)

(deftest record-events-match-by-record-xid
  (testing "record/* matches only when :record-xid is in the interest"
    (is (true?  (matches? {:records ["u-1"]} {:type "record/update" :record-xid "u-1"})))
    (is (false? (matches? {:records ["u-1"]} {:type "record/update" :record-xid "u-9"})))
    (is (false? (matches? {:records ["u-9"]} {:type "record/insert" :record-xid "u-1" :after {}}))))
  (testing "REGRESSION: a record delta for a foreign record must NOT broadcast to all"
    ;; Before the fix, record deltas carried no recognized discriminator and
    ;; hit the opaque→everyone branch. This is the bug the fix closes.
    (is (false? (matches? {:records ["a"]} {:type "record/delete" :record-xid "b" :before {}})))))

(deftest relation-events-match-on-data0-only
  (testing "relation/* matches the SUBSCRIBED endpoint (data[0]), never data[1]"
    (is (true?  (matches? {:records ["u-1"]} {:type "relation/link" :data ["u-1" "m-9"]})))
    (is (false? (matches? {:records ["m-9"]} {:type "relation/link" :data ["u-1" "m-9"]})))))

(deftest touched-pokes-match-by-name
  (testing "entity/touched by :entity — interest entities coerced via name"
    (is (true?  (matches? {:entities [:Movie]}  {:type "entity/touched" :entity "Movie"})))
    (is (true?  (matches? {:entities ["Movie"]} {:type "entity/touched" :entity "Movie"})))
    (is (false? (matches? {:entities ["User"]}  {:type "entity/touched" :entity "Movie"}))))
  (testing "relation/touched by :relation (singular)"
    (is (true?  (matches? {:relations ["Movie.actors"]} {:type "relation/touched" :relation "Movie.actors"})))
    (is (false? (matches? {:relations ["Movie.genres"]} {:type "relation/touched" :relation "Movie.actors"})))))

(deftest opaque-events-deliver-to-all
  (testing "sentinels / events with no discriminator reach every watcher"
    (is (true? (matches? {:records ["u-1"]} {:type "connection/resumed"})))
    (is (true? (matches? {:entities ["x"]}  {:type "schema/changed"})))))

(deftest model-deploy-events-match-by-type-scoped-to-models-interest
  ;; REGRESSION: model-deploy events (server: {:event "deployed-model" :data
  ;; {:action "deploy" ...}} — format-sse puts :event on the SSE `event:`
  ;; line, only :data's value becomes the JSON body; the transport layer
  ;; fills :type in from that event: name since the payload itself has
  ;; none) carry NO :record-xid/:entity/:relation. Before this fix,
  ;; event-matches? never looked at :type at all, so these fell into the
  ;; "opaque, deliver to all" branch — harmless with a single watch-schema
  ;; watcher, but wrong: a record/entity-only watcher would ALSO receive
  ;; every model deploy notification as noise, and watch-schema's own
  ;; :models interest scoping (advertised by mux-union) never actually
  ;; filtered anything.
  (testing ":models interest scopes deployed-model/runtime-model precisely"
    (is (true?  (matches? {:models ["deployed-model"]} {:type "deployed-model" :action "deploy"})))
    (is (false? (matches? {:models ["runtime-model"]}  {:type "deployed-model" :action "deploy"})))
    (is (true?  (matches? {:models ["runtime-model"]}  {:type "runtime-model" :action "deploy"}))))
  (testing "a record/entity-only watcher does NOT receive model-deploy noise"
    (is (false? (matches? {:records ["u-1"]}  {:type "deployed-model" :action "deploy"})))
    (is (false? (matches? {:entities ["User"]} {:type "runtime-model" :action "deploy"}))))
  (testing "genuinely opaque sentinels (not a known model type) still deliver to all — unaffected"
    (is (true? (matches? {:records ["u-1"]} {:type "connection/resumed"})))
    (is (true? (matches? {:entities ["x"]}  {:type "schema/changed"})))))

;; ── Fusion: N watches → ONE consolidated /data/subscription/set union ──────

(deftest mux-union-fuses-every-watch-interest
  (testing "records from every live watch are unioned"
    (is (= #{"a" "b" "c"}
           (:records (mux-union {:w1 {:interest {:records ["a" "b"]}}
                                 :w2 {:interest {:records ["b" "c"]}}})))))
  (testing "entity / relation / operation interests coerced to name strings + unioned"
    (let [u (mux-union {:w1 {:interest {:entities [:user] :operations [:update]}}
                        :w2 {:interest {:entities ["role"] :relations [:roles]}}})]
      (is (= #{"user" "role"} (:entities u)))
      (is (= #{"roles"}       (:relations u)))
      (is (= #{"update"}      (:operations u)))))
  (testing "no watches → empty union (server told to hold nothing)"
    (is (= {:records #{} :entities #{} :relations #{} :models #{} :operations #{}}
           (mux-union {})))))

;; ── SSE frame parser: the payload's delta :type must survive (never clobbered
;;    to the SSE `event:` name). Guards the http.clj dispatch-frame! fix. ──────

(deftest dispatch-frame-preserves-payload-delta-type
  (testing "payload :type (record/update) wins over the SSE event: name ('data')"
    (let [got (atom nil)]
      (dispatch-frame! {:event-type "data"
                        :data-lines ["{\"type\":\"record/update\",\"record-xid\":\"u-1\",\"after\":{\"name\":\"x\"}}"]}
                       #(reset! got %) (fn [_ _]) (atom nil))
      (is (= "record/update" (:type @got)) "delta kind preserved, NOT 'data'")
      (is (= "u-1" (:record-xid @got)))
      (is (= {:name "x"} (:after @got)))))
  (testing "relation delta keeps its tuple + type"
    (let [got (atom nil)]
      (dispatch-frame! {:event-type "data"
                        :data-lines ["{\"type\":\"relation/link\",\"data\":[\"u-1\",\"m-9\"]}"]}
                       #(reset! got %) (fn [_ _]) (atom nil))
      (is (= "relation/link" (:type @got)))
      (is (= ["u-1" "m-9"] (:data @got)))))
  (testing "falls back to the SSE event: name only when the payload carries no :type"
    (let [got (atom nil)]
      (dispatch-frame! {:event-type "entity"
                        :data-lines ["{\"entity\":\"user\"}"]}
                       #(reset! got %) (fn [_ _]) (atom nil))
      (is (= "entity" (:type @got))))))

;; ── core.async decoupling: a slow subscriber must never block another ──────

(deftest slow-sink-does-not-block-another-watchers-delivery
  (testing "spawn-watch-channel!: two independent channels, one slow sink"
    (let [fast-got (promise)
          slow-got (promise)
          slow-ch  (spawn-watch-channel! (fn [ev] (Thread/sleep 2000) (deliver slow-got ev)))
          fast-ch  (spawn-watch-channel! (fn [ev] (deliver fast-got ev)))]
      (a/put! slow-ch {:type "record/update" :record-xid "slow"})
      (a/put! fast-ch {:type "record/update" :record-xid "fast"})
      ;; the fast sink must fire promptly — it must NOT wait behind the
      ;; slow one, since each channel has its OWN consumer thread.
      (is (= "fast" (:record-xid (deref fast-got 500 :timeout)))
          "fast sink blocked behind the slow one — decoupling broken")
      (is (= "slow" (:record-xid (deref slow-got 3000 :timeout)))
          "slow sink never fired at all"))))

(deftest mux-dispatch-put-never-blocks-on-a-full-slow-channel
  (testing "mux-dispatch (a/put!) returns immediately even into a stalled channel"
    (let [slow-ch (spawn-watch-channel! (fn [_ev] (Thread/sleep 5000)))
          fake-mux (atom {:watches {"w1" {:interest {:records ["r-1"]} :ch slow-ch}}})
          started  (System/nanoTime)]
      ;; flood past the sliding buffer's size — a/put! must never park.
      (dotimes [_ 300]
        (mux-dispatch fake-mux {:type "record/update" :record-xid "r-1"}))
      (is (< (/ (- (System/nanoTime) started) 1e6) 1000)
          "mux-dispatch blocked on a slow/full channel — should never park"))))

(deftest mux-dispatch-only-routes-to-matching-interest
  (testing "non-matching watcher's channel receives nothing"
    (let [got-a (promise)
          got-b (promise)
          ch-a (spawn-watch-channel! #(deliver got-a %))
          ch-b (spawn-watch-channel! #(deliver got-b %))
          fake-mux (atom {:watches {"a" {:interest {:records ["r-1"]} :ch ch-a}
                                    "b" {:interest {:records ["r-9"]} :ch ch-b}}})]
      (mux-dispatch fake-mux {:type "record/update" :record-xid "r-1"})
      (is (= "r-1" (:record-xid (deref got-a 500 :timeout)))
          "matching watcher should receive the event")
      (is (= :timeout (deref got-b 300 :timeout))
          "non-matching watcher must receive nothing"))))

;; ── core.async decoupling: debounce coalesces a burst into ONE refetch ─────

(deftest debounced-refetch-coalesces-a-burst
  (testing "N rapid pokes inside the window -> exactly ONE refetch"
    (let [gen (atom 0)
          calls (atom 0)
          done (promise)]
      (dotimes [_ 20] (debounced-refetch! gen 40 (fn [] (swap! calls inc) (deliver done true))))
      (is (true? (deref done 1000 :timeout)))
      (Thread/sleep 100) ;; let any wrongly-fired extras land
      (is (= 1 @calls) "20 pokes inside one window should coalesce to 1 refetch")))

  (testing "two well-separated bursts -> two refetches"
    (let [gen (atom 0)
          calls (atom 0)]
      (dotimes [_ 5] (debounced-refetch! gen 30 (fn [] (swap! calls inc))))
      (Thread/sleep 150)
      (dotimes [_ 5] (debounced-refetch! gen 30 (fn [] (swap! calls inc))))
      (Thread/sleep 150)
      (is (= 2 @calls) "separated bursts should each refetch once"))))

(deftest debounced-refetch-does-not-spend-a-thread-per-poke
  (testing "a burst of pending debounces costs ~0 extra live threads while parked"
    (let [gen (atom 0)
          before (.getThreadCount (java.lang.management.ManagementFactory/getThreadMXBean))]
      ;; 200 pokes, none fired yet (still inside the window) — with the old
      ;; `future` + `Thread/sleep` design this would park 200 real threads;
      ;; `core.async/timeout` parks on go's shared pool instead.
      (dotimes [_ 200] (debounced-refetch! gen 500 (fn [])))
      (let [mid (.getThreadCount (java.lang.management.ManagementFactory/getThreadMXBean))]
        (is (< (- mid before) 50)
            (str "200 pending debounces should not cost ~200 threads, got "
                 (- mid before)))))))

;; ── disconnect! must not leak watch consumer threads ──────────────────────

(def spawn-watch-channel* #'client/spawn-watch-channel!)

(deftest disconnect-closes-every-watch-channel
  ;; REGRESSION: disconnect! is the reconnect idiom — connect! calls it on the
  ;; previous client. A watch consumer loop terminates ONLY when its channel
  ;; closes-and-drains (a/<!! → nil). disconnect! used to just drop the
  ;; :watches map, orphaning the channels and leaking one parked consumer
  ;; thread per live watch on every disconnect!/reconnect. It must a/close!
  ;; each channel, exactly like mux-unregister! does.
  (testing "channels of all live watches are closed after disconnect!"
    (let [ch1  (spawn-watch-channel* (fn [_]))
          ch2  (spawn-watch-channel* (fn [_]))
          fake {:endpoint "http://localhost:0/data"
                :mux  (atom {:watches {"w1" {:interest {} :ch ch1}
                                       "w2" {:interest {} :ch ch2}}})
                :subs (atom {:data {} :entities #{} :relations #{} :models #{}})}]
      ;; keep the teardown hermetic — no real network for the final flush
      (with-redefs [http/post-json (fn [& _] nil)]
        (binding [core/*client* fake]
          (client/disconnect!)))
      (doseq [ch [ch1 ch2]]
        ;; a closed channel hands every taker nil immediately, so it wins the
        ;; alts!! race against the timeout; an OPEN channel would let the
        ;; timeout win. Deterministic, never hangs.
        (is (= ch (second (a/alts!! [ch (a/timeout 1000)])))
            "watch channel should be closed after disconnect!"))
      (is (empty? (:watches @(:mux fake))) "watches map cleared"))))

(deftest watch-sql-template-requires-entities
  (testing "a SQL template has no root entity, so the interest cannot be inferred"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :entities"
                          (client/watch-sql-template "SELECT count(*) AS n FROM {movie}" nil))))
  (testing "an empty :entities is as unusable as none"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"needs :entities"
                          (client/watch-sql-template "SELECT 1" nil :entities [])))))

(deftest watch-sql-template-passes-entities-as-mux-interest
  (testing "user-facing :entities becomes the :interest-entities live-value* reads"
    (let [seen (atom nil)]
      (with-redefs [client/watch-xsql (fn [op-map params opts]
                                        (reset! seen [op-map params opts]) :handle)]
        (is (= :handle (client/watch-sql-template "SELECT 1" {:a 1}
                                                  :entities ["Movie" "UserRating"]
                                                  :acting-as "u-1")))
        (let [[op-map params opts] @seen]
          (is (= {:op "sql-template" :source "SELECT 1"} op-map))
          (is (= {:a 1} params))
          (is (= ["Movie" "UserRating"] (:interest-entities opts)))
          (is (= "u-1" (:acting-as opts)))
          (is (nil? (:entities opts)) ":entities must not ride along under its old name"))))))
