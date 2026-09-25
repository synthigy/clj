(ns synthigy.client.integration-test
  "Integration tests against a running Synthigy server.

   Credentials are read from env vars (with defaults for a local dev setup):
     SYNTHIGY_TEST_ENDPOINT       — default http://localhost:7887
     SYNTHIGY_TEST_CLIENT_ID      — default test-sdk
     SYNTHIGY_TEST_CLIENT_SECRET  — default test-secret

   ENVIRONMENT SETUP — the default client must be registered once per server
   (from the core nREPL):

     (require '[synthigy.iam :as iam])
     (iam/add-client {:id \"test-sdk\" :name \"SDK integration tests\"
                      :type :confidential :secret \"test-secret\" :active true
                      :settings {\"allowed-grants\" [\"client_credentials\" \"refresh_token\"]
                                 \"redirections\" [\"http://localhost\"]
                                 \"trusted\" true}})

   IMPORTANT: give the suite its OWN client identity. Server-side subscription
   sets are keyed per client identity and full-replace — running this suite as
   an identity a live app uses (e.g. a BFF) CLOBBERS that app's subscriptions
   and its live updates silently stop.

   The server must expose :user and :user_role entities and have at least
   one user named \"Alice\" with roles for the read-path tests to pass. The
   watch tests create + purge a probe user.

   Run: clj -M:integration"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [synthigy.client :as client]
   [synthigy.client.core :as core]
   [synthigy.client.subscriptions :as subs]
   [synthigy.client.auth :as auth]
   [synthigy.client.retry :as retry]))


(def endpoint (or (System/getenv "SYNTHIGY_TEST_ENDPOINT")
                  "http://localhost:7887"))
(def client-id (or (System/getenv "SYNTHIGY_TEST_CLIENT_ID")
                   "test-sdk"))
(def client-secret (or (System/getenv "SYNTHIGY_TEST_CLIENT_SECRET")
                       "test-secret"))


(def test-client
  ;; no :key-format — kebab is the SDK default; the kebab-output test below
  ;; asserts the default, not an option.
  (core/create-client
   {:endpoint endpoint
    :client-id client-id
    :client-secret client-secret}))

;; Every test runs against the bound default client — the SDK's normal mode.
(use-fixtures :once (fn [f] (binding [core/*client* test-client] (f))))


;; ── Read path ────────────────────────────────────────────────────────────────

(deftest search-test
  (let [results (client/search :user nil [:name :active])]
    (is (sequential? results))
    (is (pos? (count results)))
    (is (every? #(contains? % :name) results))))


(deftest search-with-kebab-args-test
  (let [results (client/search :user
                  {:-where {:name {:-eq "Alice"}}}
                  [:name :active])]
    (is (= 1 (count results)))
    (is (= "Alice" (:name (first results))))))


(deftest search-with-relation-shorthand-test
  (let [results (client/search :user
                  {:-where {:name {:-eq "Alice"}}}
                  {:name nil :roles [:name :active]})]
    (is (= 1 (count results)))
    ;; wire spec: an EMPTY relation is omitted from the row, so nil is valid
    (let [roles (:roles (first results))]
      (is (or (nil? roles) (sequential? roles))))))


(deftest search-mixed-vector-selection-test
  (let [results (client/search :user
                  {:-where {:name {:-eq "Alice"}}}
                  {:name nil :roles [:name :active {:users [:name]}]})]
    (is (= 1 (count results)))
    (let [roles (:roles (first results))]
      (is (or (nil? roles) (sequential? roles))))))


(deftest get-test
  (let [result (client/get :user
                 {:name "Alice"}
                 [:name :active])]
    (is (map? result))
    (is (= "Alice" (:name result)))))


(deftest search-kebab-output-test
  (let [results (client/search :dataset_version nil
                  [:name :deployed-on])]
    (is (sequential? results))
    (when (seq results)
      (testing "multi-word keys are kebab-case"
        (is (contains? (first results) :deployed-on))))))


;; ── Batch ────────────────────────────────────────────────────────────────────

(deftest batch-test
  (let [ops [(core/op-search :user {:-where {:name {:-eq "Alice"}}} [:name])
             (core/op-search :user_role nil [:name :active])
             (core/op-search :dataset_version nil [:name :deployed-on])]
        [users roles versions] (core/results->data (client/batch ops) ops)]
    (is (core/all-ok? [users roles versions]))
    (testing "first result — Alice"
      (is (= 1 (count users)))
      (is (= "Alice" (:name (first users)))))
    (testing "second result — roles"
      (is (sequential? roles)))
    (testing "third result — versions"
      (is (sequential? versions)))))


(deftest batch-mixed-success-failure-test
  (let [ops [(core/op-search :user nil [:name])
             (core/op-search :nonexistent_entity nil [:name])]
        [users bad] (core/results->data (client/batch ops) ops)]
    (is (not (core/all-ok? [users bad])))
    (testing "first succeeds"
      (is (core/ok? users))
      (is (sequential? users)))
    (testing "second is an exception"
      (is (not (core/ok? bad)))
      (is (instance? clojure.lang.ExceptionInfo bad))
      (is (= "UNKNOWN_ENTITY" (:code (ex-data bad))))
      (is (some? (:operation (ex-data bad)))))))


(deftest batch-all-ok-test
  (let [ops [(core/op-search :user {:-where {:name {:-eq "Alice"}}} [:name])
             (core/op-search :user_role nil [:name])]
        [users roles] (core/results->data (client/batch ops) ops)]
    (is (core/all-ok? [users roles]))
    (when (core/all-ok? [users roles])
      (is (pos? (count users)))
      (is (pos? (count roles))))))


(deftest batch-errors-test
  (let [ops [(core/op-search :user nil [:name])
             (core/op-search :nonexistent nil [:name])]
        [users bad] (core/results->data (client/batch ops) ops)
        errs (core/errors [users bad])]
    (is (= 1 (count errs)))
    (is (= "UNKNOWN_ENTITY" (:code (ex-data (first errs)))))))


;; ── Auth / token ─────────────────────────────────────────────────────────────

(deftest token-default-test
  (let [tok (client/token)]
    (is (string? tok))
    (is (pos? (count tok)))
    (testing "JWT shape"
      (is (= 3 (count (clojure.string/split tok #"\.")))))))


(deftest token-cache-hit-test
  ;; Second call returns the same token (cached) — no refetch.
  (let [t1 (client/token)
        t2 (client/token)]
    (is (= t1 t2))))


(deftest typo-catches-test
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"unknown option"
                        (core/create-client
                         {:endpoint endpoint
                          :clientid "oops"
                          :client-secret "x"}))))


(deftest missing-auth-test
  ;; Only meaningful with no SYNTHIGY_TOKEN/SYNTHIGY_SUPERVISED in the test
  ;; environment — both are env-level fallbacks.
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"no Synthigy token"
                        (core/create-client {:endpoint endpoint}))))


(deftest static-provider-test
  (let [tok (client/token)
        c2 (core/create-client {:endpoint endpoint :token tok :key-format "kebab"})
        results (binding [core/*client* c2]
                  (client/search :user {:-where {:name {:-eq "Alice"}}} [:name]))]
    (is (= 1 (count results)))
    (is (= "Alice" (:name (first results))))))


(deftest oauth-provider-explicit-test
  (let [prov (auth/oauth {:token-url (str endpoint "/oauth/token")
                          :client-id client-id
                          :client-secret client-secret})
        c (core/create-client (merge {:endpoint endpoint :key-format "kebab"} prov))]
    (is (fn? (:token-fn c)))
    (is (fn? (:invalidate-fn c)))
    (is (pos? (count (binding [core/*client* c]
                       (client/search :user {:-limit 1} [:name])))))))


(deftest byo-token-fn-test
  (let [cache (atom nil)
        seed-client (core/create-client
                      {:endpoint endpoint
                       :client-id client-id
                       :client-secret client-secret})
        token-fn (fn [] (or @cache (reset! cache (binding [core/*client* seed-client]
                                                    (client/token)))))
        c (core/create-client
            {:endpoint endpoint
             :key-format "kebab"
             :token-fn token-fn
             :invalidate-fn #(reset! cache nil)})]
    (is (pos? (count (binding [core/*client* c]
                       (client/search :user {:-limit 1} [:name])))))
    (is (some? @cache))))


;; ── Schema / count ───────────────────────────────────────────────────────────

(deftest schema-test
  (let [s (client/schema)]
    (is (map? s))
    (is (contains? s :entities))
    (is (pos? (count (:entities s))))))


(deftest schema-narrowed-test
  (let [s (client/schema [:user])]
    (is (map? s))
    (is (contains? s :entities))
    (is (= 1 (count (:entities s))))))


;; ── Subscriptions / listen (end-to-end SSE) ──────────────────────────────────

(defn- entity-subbed? [subs entity]
  (some #(and (= "entity" (:type %)) (some #{entity} (:entities %))) subs))

(deftest subscriptions-roundtrip-test
  (subs/clear-subscriptions)
  (testing "empty baseline"
    (is (= [] (:subscriptions (subs/subscriptions)))))
  (testing "subscribe-entity → visible"
    (subs/subscribe-entity ["user"])
    (is (entity-subbed? (:subscriptions (subs/subscriptions)) "user")))
  (testing "unsubscribe-entity → removed"
    (subs/unsubscribe-entity ["user"])
    (is (not (entity-subbed? (:subscriptions (subs/subscriptions)) "user")))))


;; The probe account this test writes, so the `finally` can purge it even when
;; the assertion above it fails. Left unpurged it accumulated 44 rows over two
;; months before anyone looked.
(def ^:private listen-probe (atom nil))

(deftest listen-receives-event-test
  (let [events (atom [])
        stop? (atom false)
        listener (future
                   (client/listen
                                  (fn [ev] (swap! events conj ev))
                                  {:stop? #(deref stop?)}))]
    (try
      (subs/subscribe-entity ["user"])
      ;; Give SSE time to establish.
      (Thread/sleep 500)
      (let [probe (str "listen-probe-" (System/currentTimeMillis))
            probe-xid (core/new-xid)
            _ (client/sync :user {:xid probe-xid :name probe :active true})
            ;; Poll up to 3s for the matching event.
            deadline (+ (System/currentTimeMillis) 3000)]
        (reset! listen-probe probe-xid)
        (while (and (< (System/currentTimeMillis) deadline)
                    (not-any? #(= "user" (:entity %)) @events))
          (Thread/sleep 100))
        (testing "received a user change event"
          (is (some #(= "user" (:entity %)) @events))))
      (finally
        (reset! stop? true)
        (subs/unsubscribe-entity ["user"])
        (future-cancel listener)
        (when-let [xid @listen-probe]
          (client/purge :user {:xid {:-eq xid}} [:xid])
          (reset! listen-probe nil))))))


;; ── XSQL string path (what generated code rides via run-xsql) ───────────────

(deftest xsql-query-string-test
  ;; :entity passed explicitly — the same wire shape generated run-xsql ops use
  ;; (deriving entity from the XSQL root server-side is grammar-transition WIP).
  (let [rows (client/query "user\n  name\n" nil :entity :user)]
    (is (sequential? rows))
    (is (every? #(contains? % :name) rows))))

;; ── Watch path (mux + SSE + notify-then-refetch, end to end) ────────────────

(deftest watch-query-live-refetch-test
  ;; The full live loop: watch-query registers with the mux → ONE SSE stream +
  ;; consolidated server subscription → a write fires an event → coalesced
  ;; refetch resets the atom. This is the path unit tests can't see and where
  ;; two real bugs hid (silent listener death, subscription clobber).
  (let [wq (client/watch-query :user nil [:name])
        n0 (count @wq)
        probe (str "wq-probe-" (System/currentTimeMillis))]
    (try
      ;; let the mux's SSE listener connect + assert its subscription
      (Thread/sleep 800)
      (client/sync :user {:name probe :active true})
      (let [deadline (+ (System/currentTimeMillis) 6000)]
        (while (and (< (System/currentTimeMillis) deadline)
                    (not-any? #(= probe (:name %)) @wq))
          (Thread/sleep 100)))
      (testing "write elsewhere → event → refetch shows the new row"
        (is (some #(= probe (:name %)) @wq))
        (is (> (count @wq) n0)))
      (finally
        (client/close-watch! wq)
        (client/purge :user {:-where {:name {:-eq probe}}} [:name])))))

(deftest disconnect-destroys-watch-machinery-test
  ;; the server-restart idiom: disconnect! (called by connect! on the previous
  ;; client) must stop the SSE listener and drop all watches — no zombies.
  (let [wq (client/watch-query :user nil [:name])
        mux (:mux (core/the-client))]
    (Thread/sleep 500)
    (is (some? (:fut @mux)) "listener running while a watch is open")
    (client/disconnect!)
    (is (nil? (:fut @mux)) "listener stopped")
    (is (empty? (:watches @mux)) "watches dropped")
    (is (some? @wq) "handle atom still readable (just dead)")))

(deftest watch-close-releases-subscription-test
  ;; closing the last watch empties the mux → the flushed server set is empty
  (let [wq (client/watch-query :user nil [:name])]
    (Thread/sleep 500)
    (client/close-watch! wq)
    (Thread/sleep 300)
    (is (empty? (:subscriptions (subs/subscriptions)))
        "server subscription set cleared after last watch closes")))

(deftest record-watch-event-shape-and-filtering-test
  ;; The record-scoped `watch` path — the surface that carried two real bugs:
  ;; the SSE frame parser clobbered the delta `:type` (record/update → "data"),
  ;; and event-matches? ignored :record-xid so every record delta was broadcast
  ;; to EVERY watcher. watch-query-live-refetch-test can't see this — it only
  ;; checks the atom refetched, which happened even with the broadcast bug.
  ;;
  ;; Two probes, two watches: a change to A must reach A's watcher as a shaped
  ;; record/update, and must NOT reach a watch on the unrelated record B.
  (let [stamp    (System/currentTimeMillis)
        ;; Writes are silent by default, so mint the ids up front rather than
        ;; reading them back out of an echo that isn't there.
        a-xid    (core/new-xid)
        b-xid    (core/new-xid)
        _        (client/sync :user {:xid a-xid :name (str "rw-a-" stamp) :active true})
        _        (client/sync :user {:xid b-xid :name (str "rw-b-" stamp) :active true})
        a-events (atom [])
        b-events (atom [])
        wa       (client/watch {:records [a-xid]} #(swap! a-events conj %))
        wb       (client/watch {:records [b-xid]} #(swap! b-events conj %))
        record-update? #(= "record/update" (:type %))]
    (try
      (Thread/sleep 800)                         ; SSE connect + subscription flush
      (client/sync :user {:xid a-xid :name (str "rw-a-" stamp "-edited") :active true})
      (let [deadline (+ (System/currentTimeMillis) 6000)]
        (while (and (< (System/currentTimeMillis) deadline)
                    (not-any? record-update? @a-events))
          (Thread/sleep 100)))
      (let [ev (some #(when (record-update? %) %) @a-events)]
        (testing "A's watcher received a shaped record/update — :type NOT clobbered to \"data\""
          (is (some? ev) "no record/update reached the record-scoped watcher within 6s")
          (is (= "record/update" (:type ev)))
          (is (= a-xid (:record-xid ev)))
          (is (map? (:after ev)) "carries the after attribute map"))
        (testing "B's watcher (unrelated record) received NO record/update for A"
          (is (not-any? record-update? @b-events))))
      (finally
        ((:close wa))
        ((:close wb))
        (client/purge :user {:-where {:xid {:-eq a-xid}}} [:xid])
        (client/purge :user {:-where {:xid {:-eq b-xid}}} [:xid])))))

;; ── Instrumentation hooks ────────────────────────────────────────────────────

(deftest hooks-fire-per-request-test
  (let [reqs (atom [])
        resps (atom [])
        c (core/create-client
            {:endpoint endpoint
             :client-id client-id
             :client-secret client-secret
             :key-format "kebab"
             :on-request (fn [r] (swap! reqs conj r))
             :on-response (fn [_ r] (swap! resps conj r))})]
    (binding [core/*client* c] (client/search :user {:-limit 1} [:name]))
    (testing "on-request fired with method/url"
      (is (pos? (count @reqs)))
      (let [r (last @reqs)]
        (is (= :post (:method r)))
        (is (string? (:url r)))
        (is (contains? (:headers r) "Authorization"))))
    (testing "on-response fired with elapsed-ms"
      (is (pos? (count @resps)))
      (let [r (last @resps)]
        (is (= 200 (:status r)))
        (is (number? (:elapsed-ms r)))))))


(deftest hooks-survive-exception-test
  ;; A throwing hook must not break the caller.
  (let [c (core/create-client
            {:endpoint endpoint
             :client-id client-id
             :client-secret client-secret
             :key-format "kebab"
             :on-request (fn [_] (throw (ex-info "hook boom" {})))})]
    (is (pos? (count (binding [core/*client* c] (client/search :user {:-limit 1} [:name])))))))


(deftest on-error-fires-on-transport-failure-test
  (let [errs (atom [])
        c (core/create-client
            {:endpoint "http://10.255.255.1"
             :token-fn (constantly "fake")
             :request-timeout 300
             :on-error (fn [_ e] (swap! errs conj e))})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (binding [core/*client* c] (client/search :user {:-limit 1} [:name]))))
    (is (pos? (count @errs)))
    (is (instance? Throwable (first @errs)))))


;; ── Retry helper ─────────────────────────────────────────────────────────────

(deftest retry-passes-through-success-test
  (let [calls (atom 0)]
    (is (= 42 (retry/with-retry (fn [] (swap! calls inc) 42))))
    (is (= 1 @calls))))


(deftest retry-on-transport-error-test
  (let [calls (atom 0)
        retries (atom [])
        c (core/create-client
            {:endpoint "http://10.255.255.1"
             :token-fn (constantly "fake")
             :request-timeout 200})]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Transport"
          (retry/with-retry
            (fn [] (swap! calls inc)
              (binding [core/*client* c] (client/search :user {:-limit 1} [:name])))
            {:max-attempts 3
             :backoff-ms 50
             :on-retry (fn [attempt _ delay]
                         (swap! retries conj [attempt delay]))})))
    (is (= 3 @calls))
    (is (= 2 (count @retries)))
    (testing "exponential backoff"
      (is (= [[1 50] [2 100]] @retries)))))


(deftest retry-does-not-retry-4xx-test
  (let [calls (atom 0)
        c (core/create-client
            {:endpoint endpoint
             :client-id client-id
             :client-secret client-secret
             :key-format "kebab"})]
    (is (thrown? clojure.lang.ExceptionInfo
          (retry/with-retry
            (fn [] (swap! calls inc)
              (binding [core/*client* c]
                (client/search :nonexistent_entity nil [:name])))
            {:max-attempts 3 :backoff-ms 10})))
    (testing "UNKNOWN_ENTITY is not retryable"
      (is (= 1 @calls)))))


(comment
  (require '[clojure.test :refer [run-tests]])
  (require '[synthigy.client.integration-test] :reload)
  (run-tests 'synthigy.client.integration-test))
