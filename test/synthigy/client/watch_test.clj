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
            [synthigy.client.http :as http]
            [synthigy.client.subscriptions :as subs]))

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

(deftest record-events-honour-the-watchs-op-filter
  (let [inserts {:records ["x"] :operations [:insert]}]
    (is (matches? inserts {:type "record/insert" :record-xid "x"}))
    (is (not (matches? inserts {:type "record/update" :record-xid "x"})))
    (is (not (matches? inserts {:type "relation/link" :data ["x" "y"]})))
    (is (matches? {:records ["x"]} {:type "record/delete" :record-xid "x"}) "no filter takes every op")))

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
    (let [u (mux-union {:w1 {:interest {:records ["a"] :entities [:user] :operations [:update]}}
                        :w2 {:interest {:entities ["role"] :relations [:roles]}}})]
      (is (= #{"user" "role"} (:entities u)))
      (is (= #{"roles"}       (:relations u)))
      (is (= #{"update"}      (:operations u)))))
  (testing "a watch that wants every op keeps the op filter off for all"
    (is (= #{} (:operations (mux-union {:w1 {:interest {:records ["a"] :operations [:insert]}}
                                        :w2 {:interest {:records ["b"]}}}))))
    (is (= #{"insert"} (:operations (mux-union {:w1 {:interest {:records ["a"] :operations [:insert]}}
                                                :w2 {:interest {:entities [:user]}}})))))
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
        (is (= ch (second (a/alts!! [ch #_{:clj-kondo/ignore [:type-mismatch]} (a/timeout 1000)])))
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

(def mux-set-interest! #'client/mux-set-interest!)
(def live-value*       #'client/live-value*)

(deftest a-closed-watch-stays-closed
  ;; REGRESSION: closing a :track-rows watch while its refetch was in flight
  ;; let the refetch reset! the atom, whose row-tracking watch re-created the
  ;; unregistered entry as {:interest …} with no :ch. mux-dispatch then threw on
  ;; (a/put! nil …), and every watcher after the ghost lost the event.
  (testing "an entry without a channel is skipped, later watchers still get the event"
    (let [got (promise)
          ch  (spawn-watch-channel! #(deliver got %))
          fake-mux (atom {:watches (array-map "ghost" {:interest {:records ["r-1"]}}
                                              "live"  {:interest {:records ["r-1"]} :ch ch})})]
      (mux-dispatch fake-mux {:type "record/update" :record-xid "r-1"})
      (is (= "r-1" (:record-xid (deref got 500 :timeout))))))
  (testing "re-pointing the interest of an unregistered watch does not revive it"
    (let [fake {:endpoint "http://localhost:0/data"
                :mux  (atom {:watches {}})
                :subs (atom {:data {} :entities #{} :relations #{} :models #{}})}]
      (with-redefs [http/post-json (fn [& _] nil)]
        (binding [core/*client* fake]
          (mux-set-interest! "gone" {:records ["r-1"]})))
      (is (empty? (:watches @(:mux fake))))))
  (testing "a refetch that finishes after close neither repaints the atom nor registers interest"
    (let [fake {:endpoint "http://localhost:0/data"
                :mux  (atom {:watches {}})
                :subs (atom {:data {} :entities #{} :relations #{} :models #{}})}
          release (promise)
          calls (atom 0)
          run (fn [] (if (= 1 (swap! calls inc)) [{:xid "a"}] (do @release [{:xid "b"}])))]
      (with-redefs [http/post-json (fn [& _] nil)
                    client/listen  (fn [& _] nil)]
        (binding [core/*client* fake]
          (let [v (live-value* "thing" run {:records ["room"] :track-rows true :entity-track false
                                            :debounce-ms 1})
                id (:watch-id (meta v))
                ch (get-in @(:mux fake) [:watches id :ch])]
            (a/>!! ch {:type "record/update" :record-xid "room"})
            (Thread/sleep 100)
            (client/close-watch! v)
            (deliver release true)
            (Thread/sleep 200)
            (is (= [{:xid "a"}] @v) "the closed atom keeps its last value")
            (is (empty? (:watches @(:mux fake))) "no ghost entry")))))))

(def refusal {:code "FORBIDDEN" :message "no" :hint "h" :rule "stamp" :doc "errors/stamp"})

(defn assert-rejected [ev]
  (is (= ["subscription/rejected" "no" []] ((juxt :type :reason :records) ev)))
  (is (= (dissoc refusal :message) (select-keys (ex-data (:error ev)) [:code :hint :rule :doc]))))

(defn fake-client []
  {:endpoint "http://localhost:0/data"
   :mux  (atom {:watches {}})
   :subs (atom {:data {} :entities #{} :relations #{} :models #{}})})

(deftest a-refused-re-subscribe-reaches-the-watch
  (let [fake (fake-client)
        reconnect (promise)
        refuse? (atom false)
        got (promise)]
    (with-redefs [http/post-json (fn [& _] (when @refuse? (throw (http/server-error refusal "POST" 403 nil))))
                  client/listen  (fn [_ {:keys [on-connect]}]
                                   (on-connect "s-1") @reconnect (reset! refuse? true) (on-connect "s-2"))]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["r-1"]} #(deliver got %))]
          (deliver reconnect true)
          (assert-rejected (deref got 2000 :timeout))
          (reset! refuse? false)
          ((:close w)))))))

(deftest a-refused-first-subscribe-leaves-no-watch-behind
  (let [fake (fake-client)
        refuse? (atom true)]
    (with-redefs [http/post-json (fn [& _] (when @refuse? (throw (http/server-error refusal "POST" 403 nil))))
                  client/listen  (fn [_ {:keys [on-connect]}] (on-connect "s-1"))]
      (binding [core/*client* fake]
        (is (thrown? clojure.lang.ExceptionInfo (client/watch {:records ["r-1"]} (fn [_]))))
        (is (empty? (:watches @(:mux fake))) "the refused watch is gone from the multiplexer")
        (reset! refuse? false)
        (let [w (client/watch {:records ["r-2"]} (fn [_]))
              subscribed (set (mapcat :records (vals (:data @(:subs fake)))))]
          (is (= #{"r-2"} subscribed) "a later subscription set no longer carries the refused interest")
          ((:close w)))))))

(deftest a-refused-refresh-reaches-on-event
  (let [fake (fake-client)
        got (promise)
        calls (atom 0)
        run (fn [] (if (= 1 (swap! calls inc))
                     [{:xid "a"}]
                     (throw (http/server-error refusal "Request" 403 nil))))]
    (with-redefs [http/post-json (fn [& _] nil)
                  client/listen  (fn [& _] nil)]
      (binding [core/*client* fake]
        (let [v (live-value* "thing" run {:records ["room"] :entity-track false :debounce-ms 1
                                          :on-event #(deliver got %)})
              ch (get-in @(:mux fake) [:watches (:watch-id (meta v)) :ch])]
          (a/>!! ch {:type "record/update" :record-xid "room"})
          (assert-rejected (deref got 2000 :timeout))
          (is (= [{:xid "a"}] @v) "a failed refresh keeps the last rows")
          (client/close-watch! v))))))


(deftest a-refused-stream-reaches-every-watch-and-stops
  (let [streams (atom 0)
        got (promise)
        json-reply (fn [status body] {:status status :headers {"content-type" "application/json"}
                                      :body (java.io.ByteArrayInputStream. (.getBytes ^String (babashka.json/write-str body) "UTF-8"))})]
    (binding [core/*client* (core/create-client {:endpoint "http://stub" :token "t"})]
      (with-redefs [babashka.http-client/get (fn [& _] (swap! streams inc)
                                               (json-reply 403 {:error {:code "ENTITY_FORBIDDEN" :message "no events"}}))
                    babashka.http-client/post (fn [& _] {:status 200 :headers {"content-type" "application/json"}
                                                         :body "{\"ok\":true}"})]
        (let [w (binding [*err* (java.io.StringWriter.)]
                  (client/watch {:records ["r-1"]} #(when (= "subscription/rejected" (:type %)) (deliver got %))))]
          (try
            (let [ev (deref got 3000 nil)]
              (is (= "ENTITY_FORBIDDEN" (:code (ex-data (:error ev)))) "the watch heard the refusal"))
            (Thread/sleep 1500)
            (is (= 1 @streams) "a 403 is not retried")
            (finally ((:close w)))))))))

(defn wait-until [pred]
  (loop [n 0] (when (and (not (pred)) (< n 300)) (Thread/sleep 10) (recur (inc n)))))

(defn until-stopped [stop?]
  (while (not (stop?)) (Thread/sleep 5)))

(deftest every-subscription-post-names-the-stream-it-is-for
  (let [fake (fake-client)
        streams (atom [])
        reconnect (promise)]
    (with-redefs [http/post-json (fn [_ body] (swap! streams conj (:stream body)) {})
                  client/listen  (fn [_ {:keys [on-connect stop?]}]
                                   (on-connect "s-first") @reconnect (on-connect "s-second") (until-stopped stop?))]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} (fn [_]))]
          (deliver reconnect true)
          (wait-until #(= 2 (count @streams)))
          (is (= ["s-first" "s-second"] @streams))
          ((:close w)))))))

(deftest a-watch-opened-after-the-stream-closed-waits-for-the-new-stream
  (let [fake (fake-client)
        streams (atom [])
        n (atom 0)]
    (with-redefs [http/post-json (fn [_ body] (swap! streams conj (:stream body)) {})
                  client/listen  (fn [_ {:keys [on-connect stop?]}]
                                   (on-connect (str "s-" (swap! n inc))) (until-stopped stop?))]
      (binding [core/*client* fake]
        ((:close (client/watch {:records ["u-1"]} (fn [_]))))
        (is (= ["s-1"] @streams) "closing the last watch closes the stream instead of posting")
        (let [w (client/watch {:records ["u-2"]} (fn [_]))]
          (is (= ["s-1" "s-2"] @streams) "no POST went out with the closed stream's id")
          ((:close w)))))))

(defn stream-gone [] (http/server-error {:code "STREAM_NOT_FOUND" :message "gone"} "POST" 400 nil))

(deftest a-stream-the-engine-no-longer-has-is-not-a-rejected-subscription
  (let [fake (fake-client)
        seen (atom [])
        calls (atom 0)]
    (with-redefs [http/post-json (fn [& _] (when (= 2 (swap! calls inc)) (throw (stream-gone))) {})
                  client/listen  (fn [_ {:keys [on-connect stop?]}] (on-connect "s-1") (until-stopped stop?))]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} #(swap! seen conj (:type %)))]
          ((:add w) ["u-2"])
          (Thread/sleep 100)
          (is (not-any? #{"subscription/rejected"} @seen))
          (is (nil? (:stream @(:mux fake))) "the dead id is dropped until the next open")
          ((:add w) ["u-3"])
          (is (= 2 @calls) "nothing is posted without a stream")
          ((:close w)))))))

(deftest a-dead-stream-refusing-an-old-post-leaves-the-new-stream-in-charge
  (let [fake (fake-client)
        posts (atom [])
        reconnect (promise)
        release (promise)]
    (with-redefs [http/post-json (fn [_ body]
                                   (swap! posts conj (:stream body))
                                   (when (and (= "s-old" (:stream body)) (> (count @posts) 1))
                                     @release
                                     (throw (stream-gone)))
                                   {})
                  client/listen  (fn [_ {:keys [on-connect stop?]}]
                                   (on-connect "s-old") @reconnect (on-connect "s-new") (until-stopped stop?))]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} (fn [_]))
              add (future (binding [core/*client* fake] ((:add w) ["u-2"])))]
          (wait-until #(= 2 (count @posts)))
          (deliver reconnect true)
          (wait-until #(= "s-new" (:stream @(:mux fake))))
          (deliver release true)
          @add
          (wait-until #(= 3 (count @posts)))
          (is (= "s-new" (:stream @(:mux fake))) "the old refusal did not clear the new id")
          (is (= "s-new" (last @posts)) "the union went to the new stream")
          ((:close w)))))))

(deftest every-reconnect-tells-the-watch-it-resumed
  (let [fake (fake-client)
        seen (atom [])]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen  (fn [_ {:keys [on-connect stop?]}]
                                   (on-connect "s-1") (on-connect "s-2") (on-connect "s-3") (until-stopped stop?))]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} #(swap! seen conj (:type %)))]
          (wait-until #(= 2 (count @seen)))
          (Thread/sleep 50)
          (is (= ["connection/resumed" "connection/resumed"] @seen))
          ((:close w)))))))

(deftest a-live-query-re-runs-after-a-reconnect
  (let [fake (fake-client)
        reconnect (promise)
        runs (atom 0)]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen  (fn [_ {:keys [on-connect stop?]}]
                                   (on-connect "s-1") @reconnect (on-connect "s-2") (until-stopped stop?))]
      (binding [core/*client* fake]
        (let [v (live-value* "thing" #(vector {:xid (str "v" (swap! runs inc))})
                             {:entity-track true :debounce-ms 1})]
          (wait-until #(= 2 @runs))
          (Thread/sleep 50)
          (is (= 2 @runs) "snapshot, then one refetch once the subscription holds")
          (deliver reconnect true)
          (wait-until #(= 3 @runs))
          (is (= 3 @runs) "the reconnect re-ran the query")
          (client/close-watch! v))))))

(defn backfill-listen [reconnect done]
  (fn [on-event {:keys [on-connect]}]
    (on-connect "s-1")
    (on-event {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:00:00Z" :after {:name "A"}})
    @reconnect
    (on-connect "s-2")
    (on-event {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:10:00Z" :after {:name "C"}})
    @done))

(defn label [ev]
  (if (= "connection/resumed" (:type ev)) "resumed" (get-in ev [:after :name])))

(deftest backfill-replays-the-gap-before-the-sentinel-and-holds-live-events
  (let [fake (fake-client)
        reconnect (promise)
        done (promise)
        release (promise)
        asked (atom [])
        seen (atom [])]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen (backfill-listen reconnect done)
                  client/history-events (fn [xid & {:as opts}]
                                          (swap! asked conj [xid opts])
                                          @release
                                          [{:record-xid xid :op "change" :ts "2026-11-18T09:00:00Z" :attribute-xid "name" :value "old"}
                                           {:record-xid xid :op "change" :ts "2026-11-18T10:05:00Z" :attribute-xid "name" :value "B"}
                                           {:record-xid "r-9" :op "change" :ts "2026-11-18T10:06:00Z" :attribute-xid "name" :value "X"}])]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1" "u-2"]} #(swap! seen conj %) :backfill true)]
          (wait-until #(= 1 (count @seen)))
          (deliver reconnect true)
          (wait-until #(seq @asked))
          (Thread/sleep 50)
          (deliver release true)
          (wait-until #(= 5 (count @seen)))
          (Thread/sleep 50)
          (is (= ["A" "B" "B" "resumed" "C"] (map label @seen)) "replay, then the sentinel, then the held live event")
          (is (= [["u-1" true] ["u-2" true]] (map (juxt :record-xid :from-backfill) (subvec @seen 1 3))))
          (is (= #{"u-1" "u-2"} (set (map first @asked))) "history is asked about each watched record")
          (is (= {:track "entity" :limit 1000} (dissoc (second (first @asked)) :between)))
          (is (= "2026-11-18T10:00:00Z" (first (:between (second (first @asked))))) "the gap starts at the last event seen")
          (deliver done true)
          ((:close w)))))))

(deftest a-failed-backfill-still-delivers-the-sentinel-and-held-events
  (let [fake (fake-client)
        reconnect (promise)
        done (promise)
        seen (atom [])]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen (backfill-listen reconnect done)
                  client/history-events (fn [& _] (throw (ex-info "history down" {})))]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} #(swap! seen conj %) :backfill true)]
          (wait-until #(= 1 (count @seen)))
          (deliver reconnect true)
          (wait-until #(= 3 (count @seen)))
          (is (= ["A" "resumed" "C"] (map label @seen)))
          (deliver done true)
          ((:close w)))))))

(deftest a-throwing-sink-still-gets-the-rest-of-the-backfill-and-the-sentinel
  (let [fake (fake-client)
        reconnect (promise)
        done (promise)
        seen (atom [])
        thrown? (atom false)]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen (backfill-listen reconnect done)
                  client/history-events (fn [xid & _]
                                          [{:record-xid xid :op "change" :ts "2026-11-18T10:05:00Z" :attribute-xid "name" :value "B"}])]
      (binding [core/*client* fake
                *err* (java.io.StringWriter.)]
        (let [w (client/watch {:records ["u-1" "u-2"]}
                              (fn [ev]
                                (when (and (:from-backfill ev) (compare-and-set! thrown? false true))
                                  (throw (ex-info "user code" {})))
                                (swap! seen conj ev))
                              :backfill true)]
          (wait-until #(= 1 (count @seen)))
          (deliver reconnect true)
          (wait-until #(= 4 (count @seen)))
          (is (= ["A" "B" "resumed" "C"] (map label @seen)))
          (deliver done true)
          ((:close w)))))))

(defn flood-listen [reconnect pushed done n]
  (fn [on-event {:keys [on-connect]}]
    (on-connect "s-1")
    (on-event {:type "record/update" :record-xid "u-1" :ts "2026-11-18T10:00:00Z" :after {:name "A"}})
    @reconnect
    (on-connect "s-2")
    (doseq [i (range n)]
      (on-event {:type "record/update" :record-xid "u-1" :ts (format "2026-11-18T11:%02d:%02dZ" (quot i 60) (mod i 60))
                 :after {:name (str "L" i)}}))
    (deliver pushed true)
    @done))

(deftest a-slow-backfill-holds-every-live-event-past-the-buffer
  (let [fake (fake-client)
        reconnect (promise)
        pushed (promise)
        done (promise)
        release (promise)
        seen (atom [])]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen (flood-listen reconnect pushed done 150)
                  client/history-events (fn [xid & _]
                                          @release
                                          [{:record-xid xid :op "change" :ts "2026-11-18T10:05:00Z" :attribute-xid "name" :value "B"}])]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} #(swap! seen conj %) :backfill true)]
          (wait-until #(= 1 (count @seen)))
          (deliver reconnect true)
          (deref pushed 3000 nil)
          (Thread/sleep 100)
          (is (= ["A"] (map label @seen)) "nothing passes the replay while it runs")
          (deliver release true)
          (wait-until #(= 153 (count @seen)))
          (Thread/sleep 50)
          (is (= (concat ["A" "B" "resumed"] (map #(str "L" %) (range 150))) (map label @seen))
              "backfill, sentinel, then all 150 live events in order")
          (deliver done true)
          ((:close w)))))))

(deftest closing-during-a-backfill-delivers-nothing-more
  (let [fake (fake-client)
        reconnect (promise)
        pushed (promise)
        done (promise)
        release (promise)
        asked (promise)
        seen (atom [])]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen (flood-listen reconnect pushed done 5)
                  client/history-events (fn [xid & _]
                                          (deliver asked true)
                                          @release
                                          [{:record-xid xid :op "change" :ts "2026-11-18T10:05:00Z" :attribute-xid "name" :value "B"}])]
      (binding [core/*client* fake]
        (let [w (client/watch {:records ["u-1"]} #(swap! seen conj %) :backfill true)]
          (wait-until #(= 1 (count @seen)))
          (deliver reconnect true)
          (deref asked 3000 nil)
          (deref pushed 3000 nil)
          (Thread/sleep 50)
          ((:close w))
          (deliver release true)
          (Thread/sleep 200)
          (is (= ["A"] (map label @seen)))
          (deliver done true))))))

(deftest closing-during-a-backfill-stops-fetching-history
  (let [fake (fake-client)
        reconnect (promise)
        done (promise)
        release (promise)
        calls (atom 0)
        seen (atom [])]
    (with-redefs [http/post-json (fn [& _] {})
                  client/listen (backfill-listen reconnect done)
                  client/history-events (fn [& _] (swap! calls inc) @release [])]
      (binding [core/*client* fake]
        (let [w (client/watch {:records (mapv #(str "u-" %) (range 40))} #(swap! seen conj %) :backfill true)]
          (wait-until #(= 1 (count @seen)))
          (deliver reconnect true)
          (wait-until #(= 8 @calls))
          ((:close w))
          (deliver release true)
          (Thread/sleep 200)
          (is (= 8 @calls) "only the first batch of history calls is made")
          (deliver done true))))))

(deftest backfill-asks-history-about-several-records-at-once
  (let [in-flight (atom 0)
        peak (atom 0)]
    (with-redefs [client/history-events (fn [& _]
                                          (swap! peak max (swap! in-flight inc))
                                          (Thread/sleep 100)
                                          (swap! in-flight dec)
                                          [])]
      (client/backfill-events {:records (mapv #(str "u-" %) (range 10))} "2026-11-18T10:00:00Z" 10 (atom false))
      (is (= 8 @peak)))))

(deftest fold-history-makes-one-event-per-record-and-op
  (is (= [{:type "record/update" :record-xid "u-1" :ts "t3" :txid "x1" :actor "a" :request nil :scope nil
           :after {:name "B" :age 3} :from-backfill true}
          {:type "record/delete" :record-xid "u-1" :ts "t4" :txid nil :actor nil :request nil :scope nil
           :from-backfill true}]
         (core/fold-history [{:record-xid "u-1" :op "change" :ts "t1" :attribute-xid "name" :value "old"}
                             {:record-xid "u-1" :op "change" :ts "t2" :attribute-xid "name" :value "B" :txid "x1" :actor "a"}
                             {:record-xid "u-1" :op "change" :ts "t3" :attribute-xid "age" :value 3}
                             {:record-xid "u-1" :op "delete" :ts "t4" :attribute-xid "__delete__"}
                             {:record-xid "r-9" :op "change" :ts "t5" :attribute-xid "name" :value "X"}]
                            ["u-1"] "t1"))))

(deftest subscription-status-asks-about-the-watch-stream
  (let [asked (atom [])]
    (with-redefs [http/get-json (fn [url opts] (swap! asked conj [url opts]) {})]
      (binding [core/*client* (assoc (fake-client) :mux (atom {:watches {} :stream "s-9"}))]
        (subs/subscriptions))
      (binding [core/*client* (fake-client)]
        (subs/subscriptions)))
    (is (= [["http://localhost:0/data/data/subscription/status" {:query-params {"stream" "s-9"}}]
            ["http://localhost:0/data/data/subscription/status" {}]]
           @asked))))

(deftest each-connect-mints-its-own-stream-id
  (let [urls (atom [])
        opened (atom [])]
    (binding [core/*client* (core/create-client {:endpoint "http://stub" :token "t"})]
      (with-redefs [babashka.http-client/get (fn [url & _] (swap! urls conj url)
                                               {:status 200 :body (java.io.ByteArrayInputStream. (byte-array 0))})]
        (http/sse-listen (fn [_]) {:on-connect #(swap! opened conj %)
                                   :stop? #(= 2 (count @opened))})))
    (is (= 2 (count (set @opened))) "a fresh id per connection")
    (is (every? #(re-matches #"[A-Za-z0-9_-]{8,64}" %) @opened))
    (is (= (map #(str "http://stub/data/events?stream=" %) @opened) @urls))))
