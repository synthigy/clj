(ns synthigy.client.verbs-test
  "Wire-shape + result-unwrap contract of the CRUD verbs (search/get/sync/
   stack/delete/slice/purge/sql-template) and the request-body builder.
   No server: `http/request` is redef'd to capture the outgoing body and
   return a canned {:results …}, exercising the full verb →
   single-result → build-request path incl. error mapping."
  (:require [clojure.test :refer [deftest is testing]]
            [synthigy.client :as client]
            [synthigy.client.core :as core]
            [synthigy.client.http :as http]))

;; A minimal fake client — build-request only reads :key-format.
(def ^:private fake-client {:endpoint "http://stub" :key-format "kebab"})

(defn- with-capture
  "Run body with http/request stubbed to capture the wire body and return
   `resp`. Returns the captured request body."
  [resp f]
  (let [captured (atom nil)]
    (binding [core/*client* fake-client]
      (with-redefs [http/request (fn [body] (reset! captured body) resp)]
        (f)))
    @captured))

;; ── build-request (pure) ──────────────────────────────────────────────────

(deftest build-request-key-format-precedence
  (testing "per-call :key-format wins over the client default"
    (let [b (core/build-request {:key-format "kebab"} [{:op "search"}]
                                {:key-format :camel})]
      (is (= "camel" (:key_format b)))))
  (testing "client default used when no per-call override"
    (let [b (core/build-request {:key-format "snake"} [{:op "search"}] {})]
      (is (= "snake" (:key_format b)))))
  (testing "acting-as only present when set"
    (let [b (core/build-request {} [{:op "search"}] {:acting-as "u-1"})]
      (is (= "u-1" (:acting_as b))))
    (is (not (contains? (core/build-request {} [{:op "search"}] {}) :acting_as)))))

;; ── read verbs ─────────────────────────────────────────────────────────────

(deftest search-wire-shape-and-empty
  (let [body (with-capture {:results [{:ok true :data nil}]}
               ;; Clojure nil-punning: an empty search returns nil, NOT [] —
               ;; a deliberate divergence from the Go/JS/Py SDKs. (count nil)=0,
               ;; (seq nil)=nil, (map f nil)=() all work, so nil is idiomatic.
               ;; Pinned here so nobody "fixes" it to [].
               #(is (nil? (client/search :user {:-where {:active {:-eq true}}} [:name]))))
        op   (first (:operations body))]
    (is (= "search" (:op op)))
    (is (= "user" (:entity op)))
    (is (contains? op :selections)))
  (testing "non-empty search returns the data vector"
    (with-capture {:results [{:ok true :data [{:name "Alice"}]}]}
      #(is (= [{:name "Alice"}] (client/search :user nil [:name]))))))

(deftest get-returns-data-or-nil
  (testing "ok result unwraps :data"
    (with-capture {:results [{:ok true :data {:name "Alice"}}]}
      #(is (= {:name "Alice"} (client/get :user {:xid "u-1"} [:name])))))
  (testing "null data → nil"
    (with-capture {:results [{:ok true :data nil}]}
      #(is (nil? (client/get :user {:xid "nope"} [:name]))))))

;; ── write verbs ────────────────────────────────────────────────────────────

(deftest write-verb-op-shapes
  (doseq [[verb-fn args verb] [[#(client/sync :user {:xid "u" :name "A"}) nil "sync"]
                               [#(client/stack :user {:xid "u"}) nil "stack"]
                               [#(client/delete :user {:xid "u"}) nil "delete"]]]
    (let [body (with-capture {:results [{:ok true :data {:xid "u"}}]} verb-fn)
          op   (first (:operations body))]
      (is (= verb (:op op)) (str verb " op shape"))
      (is (= "user" (:entity op)))
      (is (contains? op :data))
      (is (not (contains? op :selections))))))

(deftest sync-stack-returning-flag
  (testing "DEFAULT is silent — returning:false on the wire, {:count n} back"
    (let [body (with-capture {:results [{:ok true :data {:count 3}}]}
                 #(is (= {:count 3}
                         (client/sync :user [{:name "A"} {:name "B"} {:name "C"}]))))
          op   (first (:operations body))]
      (is (false? (:returning op)))))
  (testing ":returning true opts back into the echo"
    (let [body (with-capture {:results [{:ok true :data {:xid "u" :name "A"}}]}
                 #(is (= {:xid "u" :name "A"}
                         (client/sync :user {:xid "u" :name "A"} :returning true))))
          op   (first (:operations body))]
      (is (true? (:returning op)))))
  (testing "stack carries the same flag, silent by default"
    (let [sbody (with-capture {:results [{:ok true :data {:count 1}}]}
                  #(client/stack :user {:xid "u"}))
          tbody (with-capture {:results [{:ok true :data {:xid "u"}}]}
                  #(client/stack :user {:xid "u"} :returning true))]
      (is (false? (:returning (first (:operations sbody)))))
      (is (true? (:returning (first (:operations tbody))))))))

(deftest new-xid-shape
  (testing "22-char Base58, no collisions across 500 calls"
    (let [xids (repeatedly 500 core/new-xid)]
      (is (every? #(and (= 22 (count %))
                        (re-matches #"[123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz]{22}" %))
                  xids))
      (is (= 500 (count (distinct xids)))))))

(deftest slice-and-purge-shapes
  (let [sbody (with-capture {:results [{:ok true :data {:roles true}}]}
                #(client/slice :user {:xid "u"} [:roles]))
        pbody (with-capture {:results [{:ok true :data [{:xid "u"}]}]}
                #(client/purge :user {:-where {:active {:-eq false}}} [:xid]))]
    (is (= "slice" (:op (first (:operations sbody)))))
    (is (= "purge" (:op (first (:operations pbody)))))))

(deftest sql-template-shape
  (let [body (with-capture {:results [{:ok true :data [{:n 2}]}]}
               #(is (= [{:n 2}] (client/sql-template "SELECT count(*) AS n FROM {user}" nil))))
        op   (first (:operations body))]
    (is (= "sql-template" (:op op)))
    (is (= true (:cached op)))))

;; ── error mapping ──────────────────────────────────────────────────────────

(deftest per-op-error-throws-ex-info
  (binding [core/*client* fake-client]
    (with-redefs [http/request (fn [_] {:results [{:ok false
                                                   :error {:message "no such entity"
                                                           :code "UNKNOWN_ENTITY"}}]})]
      (let [e (try (client/search :nope nil [:x]) (catch clojure.lang.ExceptionInfo e e))]
        (is (instance? clojure.lang.ExceptionInfo e))
        (is (= "UNKNOWN_ENTITY" (:code (ex-data e))))
        (is (= "no such entity" (ex-message e)))
        ;; the failing op is attached for debugging
        (is (= "search" (:op (:operation (ex-data e)))))))))

(deftest acting-as-forwarded-on-the-wire
  (let [body (with-capture {:results [{:ok true :data []}]}
               #(client/search :user nil [:name] :acting-as "u-42"))]
    (is (= "u-42" (:acting_as body)))))

;; ── deploy / destroy ─────────────────────────────────────────────────────

(deftest deploy-posts-export-contents-verbatim
  (let [body (with-capture {:results [{:ok true
                                       :data {:deployed true :version "0.3"
                                              :dataset "ds-1"}}]}
               #(is (= {:deployed true :version "0.3" :dataset "ds-1"}
                       (client/deploy "{\"~:xid\":\"v-1\"}"))))
        op   (first (:operations body))]
    (is (= "deploy" (:op op)))
    (is (= "{\"~:xid\":\"v-1\"}" (:data op)))
    (is (not (contains? op :entity)))))

(deftest destroy-is-delete-on-dataset-by-xid
  (let [body (with-capture {:results [{:ok true :data true}]}
               #(is (true? (client/destroy "ds-1"))))
        op   (first (:operations body))]
    (is (= "delete" (:op op)))
    (is (= "dataset" (:entity op)))
    (is (= {:xid "ds-1"} (:data op)))))

(deftest sync-and-stack-take-a-vector-in-one-operation
  (let [body (with-capture {:results [{:ok true :data {:count 2}}]}
               #(is (= {:count 2} (client/sync :movie [{:title "A"} {:title "B"}]))))
        ops  (:operations body)]
    (is (= 1 (count ops)))
    (is (= [{:title "A"} {:title "B"}] (:data (first ops)))))
  (let [op (first (:operations (with-capture {:results [{:ok true :data [{:xid "a"}]}]}
                                  #(client/stack :movie [{:xid "a"}] :returning true))))]
    (is (= ["stack" true] ((juxt :op :returning) op)))))
