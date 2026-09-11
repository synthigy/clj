(ns synthigy.client.query-test
  "Wire-shape contract of `query` — STRICT: XSQL travels only as the
   `xsql` document op; bare bodies get a synthetic `@<op> _q` header."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [synthigy.client :as client]))

(deftest query-sends-xsql-document-op
  (let [captured (atom nil)]
    (with-redefs [client/single-result (fn [op _] (reset! captured op) nil)]
      (testing "bare body gets a synthetic @search _q header"
        (client/query "user\n  name\n" {:a 1})
        (is (= "xsql" (:op @captured)))
        (is (str/starts-with? (:xsql @captured) "@search _q\n"))
        (is (= {:a 1} (:params @captured)))
        (is (not (contains? @captured :selections)))
        (is (not (contains? @captured :entity))))
      (testing ":op picks the synthetic verb"
        (client/query "user (xid = ?x:string)\n  name\n" {:x "u1"} :op :get)
        (is (str/starts-with? (:xsql @captured) "@get _q\n")))
      (testing "@-document sources pass through verbatim"
        (client/query "@search list\nuser\n  name\n" nil)
        (is (= "@search list\nuser\n  name\n" (:xsql @captured)))))))
