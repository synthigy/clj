(ns synthigy.client.filter-test
  (:require [clojure.test :refer [deftest is]]
            [synthigy.client.filter :as f]))

(deftest scalar-conditions
  (is (= {:_eq 1} (f/eq 1)))
  (is (= {:_neq 1} (f/neq 1)))
  (is (= {:_gt 1} (f/gt 1)))
  (is (= {:_ge 1} (f/ge 1)))
  (is (= {:_lt 1} (f/lt 1)))
  (is (= {:_le 1} (f/le 1)))
  (is (= {:_like "a%"} (f/like "a%")))
  (is (= {:_ilike "a%"} (f/ilike "a%")))
  (is (= {:_is_null true} (f/is-null)))
  (is (= {:_is_not_null true} (f/is-not-null))))

(deftest in-flattens-one-level
  (is (= {:_in [1 2 3]} (f/in 1 2 3)))
  (is (= {:_in [1 2 3]} (f/in [1 2 3])))
  (is (= {:_nin ["a" "b"]} (f/nin ["a" "b"]))))

(deftest combinators
  (is (= {:_and [{:a {:_eq 1}} {:b {:_gt 2}}]}
         (f/and {:a (f/eq 1)} {:b (f/gt 2)})))
  (is (= {:_or [{:a {:_eq 1}} {:a {:_eq 2}}]}
         (f/or {:a (f/eq 1)} {:a (f/eq 2)})))
  (is (= {:_not {:a {:_eq 1}}}
         (f/not {:a (f/eq 1)}))))
