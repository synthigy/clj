(ns synthigy.client.selection-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [synthigy.client.selection :as selection]))


(deftest normalize-nil-test
  (testing "nil returns nil"
    (is (nil? (selection/normalize nil))))
  (testing "true returns nil"
    (is (nil? (selection/normalize true)))))


(deftest normalize-vector-of-keywords-test
  (testing "expands to map with nil values"
    (is (= {:name nil :email nil :active nil}
           (selection/normalize [:name :email :active]))))
  (testing "single keyword"
    (is (= {:name nil}
           (selection/normalize [:name])))))


(deftest normalize-scalar-fields-test
  (testing "nil values pass through"
    (is (= {:name nil :email nil}
           (selection/normalize {:name nil :email nil}))))
  (testing "true values become nil"
    (is (= {:name nil :email nil}
           (selection/normalize {:name true :email true})))))


(deftest normalize-relation-as-map-test
  (testing "plain map becomes [{:selections ...}]"
    (is (= {:name nil
            :roles [{:selections {:name nil :active nil}}]}
           (selection/normalize {:name nil
                                 :roles {:name nil :active nil}})))))


(deftest normalize-relation-as-vector-test
  (testing "vector of keywords becomes [{:selections {k nil ...}}]"
    (is (= {:name nil
            :roles [{:selections {:name nil :active nil}}]}
           (selection/normalize {:name nil
                                 :roles [:name :active]})))))


(deftest normalize-relation-with-args-test
  (testing "map with :selections wraps in vector"
    (is (= {:name nil
            :roles [{:selections {:name nil}
                     :args {:_where {:active {:_eq true}}}}]}
           (selection/normalize
            {:name nil
             :roles {:selections {:name nil}
                     :args {:_where {:active {:_eq true}}}}})))))


(deftest normalize-mixed-vector-test
  (testing "mixed keywords and maps in vector value"
    (is (= {:name nil
            :groups [{:selections {:name nil :active nil
                                   :created_by [{:selections {:name nil}}]
                                   :created_on nil}}]}
           (selection/normalize
            {:name nil
             :groups [:name :active {:created-by {:name nil}} :created-on]}))))
  (testing "top-level mixed vector"
    (is (= {:name nil :active nil
            :created_by [{:selections {:name nil}}]
            :created_on nil}
           (selection/normalize
            [:name :active {:created-by {:name nil}} :created-on]))))
  (testing "deeply nested mixed"
    (is (= {:name nil
            :roles [{:selections {:name nil :active nil
                                  :scopes [{:selections {:name nil}}]
                                  :description nil}}]}
           (selection/normalize
            {:name nil
             :roles [:name :active {:scopes [:name]} :description]})))))


(deftest normalize-internal-format-passthrough-test
  (testing "already-internal format passes through untouched"
    (is (= {:name nil
            :roles [{:selections {:name nil}
                     :args {:_where {:active {:_eq true}}}}]}
           (selection/normalize
            {:name nil
             :roles [{:selections {:name nil}
                      :args {:_where {:active {:_eq true}}}}]}))))
  (testing "an explicit :_join passes through"
    (is (= {:roles [{:selections {:name nil} :args {:_join "inner"}}]}
           (selection/normalize
            {:roles [{:selections {:name nil} :args {:_join "inner"}}]})))))


(deftest normalize-deep-nesting-test
  (testing "multi-level nesting"
    (is (= {:name nil
            :roles [{:selections
                     {:name nil
                      :scopes [{:selections {:name nil}}]}}]}
           (selection/normalize
            {:name nil
             :roles {:name nil
                     :scopes [:name]}}))))

  (testing "three levels deep"
    (is (= {:name nil
            :groups [{:selections
                      {:name nil
                       :users [{:selections
                                {:name nil
                                 :roles [{:selections {:name nil}}]}}]}}]}
           (selection/normalize
            {:name nil
             :groups {:name nil
                      :users {:name nil
                              :roles [:name]}}})))))


(deftest normalize-mixed-test
  (testing "scalars, shorthand relations, and full relations mixed"
    (is (= {:name nil
            :email nil
            :roles [{:selections {:name nil :active nil}}]
            :groups [{:selections {:name nil}
                      :args {:_limit 5}}]}
           (selection/normalize
            {:name nil
             :email true
             :roles [:name :active]
             :groups {:selections {:name nil}
                      :args {:_limit 5}}})))))


(deftest normalize-alias-test
  (testing "internal format with alias passes through"
    (is (= {:name nil
            :roles [{:selections {:name nil}
                     :alias "my_roles"}]}
           (selection/normalize
            {:name nil
             :roles [{:selections {:name nil}
                      :alias "my_roles"}]})))))


(deftest normalize-key-normalization-test
  (testing "kebab-case keys normalized to snake_case"
    (is (= {:first_name nil :last_name nil}
           (selection/normalize {:first-name nil :last-name nil}))))
  (testing "camelCase keys normalized"
    (is (= {:first_name nil}
           (selection/normalize {:firstName nil}))))
  (testing "nested relation keys normalized"
    (is (= {:user_name nil
            :user_roles [{:selections {:role_name nil}}]}
           (selection/normalize
            {:user-name nil :user-roles {:role-name nil}}))))
  (testing "args inside relations get normalized"
    (is (= {:roles [{:selections {:name nil}
                     :args {:_where {:is_active {:_eq true}}}}]}
           (selection/normalize
            {:roles {:selections {:name nil}
                     :args {:-where {:is-active {:-eq true}}}}})))))


(deftest normalize-idempotent-test
  (testing "normalizing twice gives same result"
    (let [input {:name nil :roles [:name :active] :groups {:name nil}}
          once (selection/normalize input)
          twice (selection/normalize once)]
      (is (= once twice)))))

(deftest normalize-never-injects-join-test
  (testing "the client injects NO join — absent `_join` is the server's
            flat-LEFT default; `{:_join :inner}` is the caller's explicit
            way to scope parents"
    (let [out (selection/normalize {:genres {:xid nil}})]
      (is (= {:genres [{:selections {:xid nil}}]} out)
          "no :args, no :_join — the selection travels as written"))
    (let [out (selection/normalize {:_count {:actors nil}})]
      (is (not (contains? (:args (first (:_count out))) :_join))
          "_count subtrees carry no join either"))))
