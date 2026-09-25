(ns synthigy.client.error-test
  (:require [clojure.test :refer [deftest is testing]]
            [synthigy.client :as client]
            [synthigy.client.core :as core]
            [synthigy.client.error :as err]
            [synthigy.client.http :as http]))

(defn data-of
  [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest categories-match-the-other-sdks
  (is (= {:code "NO_TOKEN" :category "auth" :retryable false}
         (ex-data (err/ex-info "x" {:code "NO_TOKEN"}))))
  (is (= ["rate_limit" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "TIMEOUT"})))))
  (is (= ["network" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "TRANSPORT_ERROR"})))))
  (is (= ["auth" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "access_denied"})))))
  (testing "unknown and missing codes fall back to internal, retryable"
    (is (= ["internal" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "NEW_SERVER_CODE"})))))
    (is (= ["internal" true] ((juxt :category :retryable) (ex-data (err/ex-info "x" {}))))))
  (testing "cause is kept"
    (let [cause (Exception. "root")]
      (is (identical? cause (ex-cause (err/ex-info "x" {:code "NETWORK_ERROR"} cause)))))))

(deftest verbs-throw-typed-errors
  (testing "client-side guard"
    (binding [core/*client* nil]
      (is (= ["NOT_CONNECTED" "validation" false]
             ((juxt :code :category :retryable) (data-of #(client/search :user nil [:name])))))))
  (testing "server per-op error keeps the server's fields"
    (binding [core/*client* {:endpoint "http://stub" :key-format "kebab"}]
      (with-redefs [http/request (constantly {:results [{:ok false
                                                          :error {:code "ENTITY_FORBIDDEN"
                                                                  :message "no"
                                                                  :entity "user"}}]})]
        (is (= ["ENTITY_FORBIDDEN" "iam" false "user"]
               ((juxt :code :category :retryable :entity)
                (data-of #(client/search :user nil [:name]))))))))
  (testing "config errors"
    (is (= "validation"
           (:category (data-of #(core/create-client {:endpoint "http://x" :bogus 1}))))))
  (testing "watch-sql-template without :entities"
    (binding [core/*client* {:endpoint "http://stub"}]
      (is (= ["MISSING_ENTITIES" "validation"]
             ((juxt :code :category) (data-of #(client/watch-sql-template "SELECT 1" nil))))))))
