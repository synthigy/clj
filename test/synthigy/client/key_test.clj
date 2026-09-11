(ns synthigy.client.key-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [synthigy.client.key :as key]))


(deftest ->snake_case-test
  (testing "kebab-case"
    (is (= :user_name (key/->snake_case :user-name)))
    (is (= :created_at (key/->snake_case :created-at))))
  (testing "camelCase"
    (is (= :user_name (key/->snake_case :userName)))
    (is (= :created_at (key/->snake_case :createdAt))))
  (testing "snake_case passthrough"
    (is (= :user_name (key/->snake_case :user_name))))
  (testing "single word"
    (is (= :name (key/->snake_case :name))))
  (testing "underscore prefix preserved"
    (is (= :_eq (key/->snake_case :_eq)))
    (is (= :_order_by (key/->snake_case :_order_by)))
    (is (= :__typename (key/->snake_case :__typename))))
  (testing "dash prefix → underscore"
    (is (= :_where (key/->snake_case :-where)))
    (is (= :_eq (key/->snake_case :-eq)))
    (is (= :_order_by (key/->snake_case :-order-by)))))


(deftest normalize-keys-deep-test
  (testing "kebab args"
    (is (= {:_where {:is_active {:_eq true}
                     :first_name {:_eq "alice"}}
            :_order_by {:created_at :desc}
            :_limit 10}
           (key/normalize-keys-deep
            {:-where {:is-active {:-eq true}
                      :first-name {:-eq "alice"}}
             :-order-by {:created-at :desc}
             :-limit 10}))))
  (testing "with arrays"
    (is (= {:_where {:_or [{:first_name {:_eq "alice"}}
                           {:last_name {:_eq "smith"}}]}}
           (key/normalize-keys-deep
            {:-where {:-or [{:first-name {:-eq "alice"}}
                            {:last-name {:-eq "smith"}}]}}))))
  (testing "nil passthrough"
    (is (nil? (key/normalize-keys-deep nil))))
  (testing "scalar passthrough"
    (is (= 42 (key/normalize-keys-deep 42)))))
