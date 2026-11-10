(ns synthigy.client.error-test
  (:require [babashka.http-client]
            [clojure.test :refer [deftest is testing]]
            [synthigy.client :as client]
            [synthigy.client.auth :as auth]
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
  (testing "unknown and missing codes fall back to internal, not retryable"
    (is (= ["internal" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code "NEW_SERVER_CODE"})))))
    (is (= ["internal" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {}))))))
  (testing "the server's :retryable wins"
    (is (true? (:retryable (ex-data (err/ex-info "x" {:code "DB_UNAVAILABLE" :retryable true})))))
    (is (false? (:retryable (ex-data (err/ex-info "x" {:code "INTERNAL_ERROR" :retryable false}))))))
  (testing "permission denials are iam"
    (doseq [code ["RELATION_FORBIDDEN" "ATTRIBUTE_FORBIDDEN" "ROW_FORBIDDEN"
                  "CREATE_FORBIDDEN" "DELETE_FORBIDDEN" "SLOT_OCCUPIED"]]
      (is (= ["iam" false] ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code code})))) code)))
  (testing "file link codes"
    (is (= [["iam" false] ["not_found" false] ["validation" false]]
           (for [code ["LINKS_DISABLED" "LINK_NOT_FOUND" "INVALID_EXPIRES"]]
             ((juxt :category :retryable) (ex-data (err/ex-info "x" {:code code})))))))
  (testing "cause is kept"
    (let [cause (Exception. "root")]
      (is (identical? cause (ex-cause (err/ex-info "x" {:code "NETWORK_ERROR"} cause)))))))

(deftest refused-token-mint-is-typed
  (doseq [[status body code message]
          [[401 "{\"error\":\"invalid_client\",\"error_description\":\"Client authentication failed\"}"
            "INVALID_CLIENT" "Token request refused (401): invalid_client — Client authentication failed"]
           [400 "{\"error\":\"invalid_scope\"}" "INVALID_SCOPE" "Token request refused (400): invalid_scope"]
           [400 "{\"error\":\"invalid_target\",\"error_description\":\"Client is not authorized for audience x\"}"
            "INVALID_AUDIENCE" "Token request refused (400): invalid_target — Client is not authorized for audience x"]
           [400 "{\"error\":\"unauthorized_client\"}" "TOKEN_ERROR" "Token request refused (400): unauthorized_client"]
           [502 "Bad Gateway" "TOKEN_ERROR" "Token request failed: HTTP 502"]]]
    (with-redefs [babashka.http-client/post (constantly {:status status :body body})]
      (let [e (try ((:token-fn (auth/oauth {:token-url "http://stub/oauth/token"
                                            :client-id "id" :client-secret "wrong"})))
                   nil
                   (catch clojure.lang.ExceptionInfo e e))]
        (is (= [code "auth" false status message]
               ((juxt :code :category :retryable :status (constantly (ex-message e))) (ex-data e)))
            code)
        (is (= (= code "INVALID_CLIENT") (some? (:hint (ex-data e)))) code)))))

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
