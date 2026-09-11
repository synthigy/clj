(ns synthigy.client-test
  (:require [cljs.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [synthigy.client :as client]
   [synthigy.client.core :as core]
            [synthigy.client.auth :as auth]
            [synthigy.client.http :as http]))


(deftest op-builders
  (testing "op-search normalizes args and selection"
    (let [op (core/op-search :user {:active true} [:name :email])]
      (is (= "search" (:op op)))
      (is (= "user" (:entity op)))
      (is (= {:active true} (:args op)))
      (is (= {:name nil :email nil} (:selections op)))))

  (testing "op-sync passes :data through RAW — the server normalizes top-level
            model keys itself (dash-aware), and NOT normalizing client-side is
            what preserves opaque jsonb value keys (someKey stays someKey)."
    (let [op (core/op-sync :user_role {:role-name "admin"
                                       :config {:someKey 1 :nested {:otherKey 2}}})]
      (is (= "sync" (:op op)))
      (is (= {:role-name "admin" :config {:someKey 1 :nested {:otherKey 2}}}
             (:data op))
          "keys are untouched at every depth — jsonb values must survive intact")))

  (testing "op-get-tree shape"
    (let [op (core/op-get-tree :folder "abc" :parent [:name])]
      (is (= "get-tree" (:op op)))
      (is (= "abc" (:root op)))
      (is (= "parent" (:on op))))))


(deftest create-client-static-token
  (testing "static token path builds a provider"
    (let [c (core/create-client
             {:endpoint "http://localhost:7887"
              :token "test-token"})]
      ;; :endpoint is stored as the server BASE URL; /data is appended by
      ;; the transport's `request`, sibling paths hang off the base.
      (is (= "http://localhost:7887" (:endpoint c)))
      (is (fn? (:token-fn c)))
      (is (= "test-token" ((:token-fn c)))))))


(deftest create-client-token-fn
  (testing ":token-fn inversion accepted as-is"
    (let [c (core/create-client
             {:endpoint "http://x"
              :token-fn (constantly "abc")})]
      (is (= "abc" ((:token-fn c)))))))


(deftest create-client-missing-endpoint-throws
  (is (thrown? js/Error
        (core/create-client {:token "x"}))))


(deftest create-client-missing-auth-throws
  (is (thrown? js/Error
        (core/create-client {:endpoint "http://x"}))))


(deftest auth-static-returns-token
  (let [p (auth/static "abc")]
    (is (= "abc" ((:token-fn p))))))


(deftest result-helpers
  (testing "ok? and all-ok?"
    (is (core/ok? {:foo 1}))
    (is (not (core/ok? (js/Error. "fail"))))
    (is (core/all-ok? [{:a 1} {:b 2}]))
    (is (not (core/all-ok? [{:a 1} (js/Error. "fail")])))))


(deftest compose-tree-flat-to-nested
  (let [records [{:xid "root" :name "A"}
                 {:xid "c1" :name "B" :parent {:xid "root"}}
                 {:xid "c2" :name "C" :parent {:xid "c1"}}]
        tree (core/compose-tree records {:on :parent :root-id "root"})]
    (is (= "A" (:name tree)))
    (is (= 1 (count (:children tree))))
    (is (= "B" (-> tree :children first :name)))
    (is (= "C" (-> tree :children first :children first :name)))))


;; ── reconnect race: a slow disconnect! must not clobber a newer connect! ───
;;
;; connect! calls disconnect! WITHOUT awaiting its returned promise (it's
;; synchronous — every existing caller relies on "connect! returns the
;; client immediately"). disconnect!'s own teardown (flush-subs! — a real
;; POST) is async. If that POST is still in flight when ANOTHER connect!
;; happens (e.g. a host tokenResolver override reconnecting the shared
;; client, or a silent-renew-driven reconnect), the FIRST disconnect!'s
;; late-arriving `(set! core/*client* nil)` must not wipe out the SECOND,
;; now-current client. Found live: frontend/modeling's log-cockpit hit
;; NOT_CONNECTED moments after a legitimate reconnect, exactly this race.

(defn- stub-post-json [delay-ms]
  (fn [_url _body] (p/then (p/delay delay-ms) (fn [_] {}))))

(deftest reconnect-does-not-race-a-slow-prior-disconnect
  (testing "client B survives a client A disconnect! that resolves AFTER connect! B already ran"
    (async done
      (let [orig-post http/post-json]
        (set! http/post-json (stub-post-json 30))
        (client/connect! {:endpoint "http://a" :token "a-token"})
        (let [client-a core/*client*]
          ;; Reconnect to B WHILE A's disconnect! (triggered by THIS
          ;; connect! call) is still in flight.
          (client/connect! {:endpoint "http://b" :token "b-token"})
          (let [client-b core/*client*]
            (is (not (identical? client-a client-b)) "sanity: B really is a different client")
            (js/setTimeout
             (fn []
               (is (identical? client-b core/*client*)
                   "client B must still be connected after A's slow disconnect! resolves")
               (set! http/post-json orig-post)
               (client/disconnect!)
               (done))
             80)))))))
