(ns synthigy.client.subscriptions-test
  "Pure-logic tests for the subscription set mirror — no server needed."
  (:require [clojure.test :refer [deftest is testing]]
            [synthigy.client.core :as client]))

(def build client/build-subscriptions-body)
(def norm  client/normalize-descriptor)
(def dkey  client/descriptor-key)

(defn- empty-mirror [] {:data {} :entities #{} :relations #{} :models #{}})

(deftest descriptor-validation
  (is (thrown? clojure.lang.ExceptionInfo (norm {:records []})))
  (is (thrown? clojure.lang.ExceptionInfo (norm {:records nil})))
  (testing "records coerced to a string set; operations to names"
    (is (= {:records #{"a" "b"}} (norm {:records ["a" "b"]})))
    (is (= {:records #{"a"} :operations #{"update" "link"}}
           (norm {:records ["a"] :operations [:update :link]})))))

(deftest descriptor-key-is-order-stable
  (is (= (dkey (norm {:records ["b" "a"]}))
         (dkey (norm {:records ["a" "b"]})))))

(deftest body-shape
  (testing "empty"
    (is (= {:subscriptions []} (build (empty-mirror)))))
  (testing "data sub — records sorted"
    (is (= {:subscriptions [{:type "data" :records ["a" "b"]}]}
           (build (assoc (empty-mirror) :data {"k" {:records #{"b" "a"}}})))))
  (testing "data sub with operations"
    (is (= {:subscriptions [{:type "data" :records ["a"] :operations ["link" "update"]}]}
           (build (assoc (empty-mirror)
                         :data {"k" {:records #{"a"} :operations #{"update" "link"}}})))))
  (testing "entity / relation / model tracks"
    (is (= {:subscriptions [{:type "entity" :entities ["user"]}]}
           (build (assoc (empty-mirror) :entities #{"user"}))))
    (is (= {:subscriptions [{:type "relation" :relations ["roles"]}]}
           (build (assoc (empty-mirror) :relations #{"roles"}))))
    (is (= {:subscriptions [{:type "runtime-model"}]}
           (build (assoc (empty-mirror) :models #{"runtime-model"}))))))

(deftest mirror-mutation
  ;; create-client needs no network; we only inspect the atom, never flush.
  (let [c (client/create-client {:endpoint "http://x" :token "t"})]
    (swap! (:subs c) update :entities conj "user")
    (is (= #{"user"} (:entities @(:subs c))))
    (swap! (:subs c) update :entities #(reduce disj % ["user"]))
    (is (empty? (:entities @(:subs c))))))
