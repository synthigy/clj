(ns synthigy.client.key-ping-test
  (:require [cljs.test :refer [deftest is]]
            [synthigy.client.key :as key]))

(deftest snake-case-roundtrips
  (is (= :hello_world (key/->snake_case "hello-world")))
  (is (= :user_name (key/->snake_case "userName"))))
