(ns synthigy.client.watch-acting-integration-test
  "Live: a trusted client's live XSQL list acting as a user sees that user's rows only (RLS),
   on the snapshot and on every refetch, with the options generated watch-<name> fns pass.

   Needs the guarded Project Management model (examples/datasets/project-management@0.1.2 + seed/)
   and a trusted superuser client. Env: SYNTHIGY_RLS_TEST_ENDPOINT,
   SYNTHIGY_RLS_TEST_CLIENT_{ID,SECRET}, SYNTHIGY_RLS_TEST_{ALICE,BOB} (user xids).

   Run: clj -M:watch-acting-integration"
  (:require
   [clojure.test :refer [deftest is testing]]
   [synthigy.client :as client]
   [synthigy.client.core :as core]))

(def endpoint (System/getenv "SYNTHIGY_RLS_TEST_ENDPOINT"))
(def alice (System/getenv "SYNTHIGY_RLS_TEST_ALICE"))
(def bob (System/getenv "SYNTHIGY_RLS_TEST_BOB"))
(def op {:op "search" :source "@search list\nproject\n  xid\n  name\n  description" :entity "project"})

(defn names [rows] (sort (map :name rows)))
(defn find-named [rows n] (first (filter #(= n (:name %)) rows)))

(defn until [pred ms]
  (loop [left ms]
    (cond (pred) true
          (neg? left) false
          :else (do (Thread/sleep 100) (recur (- left 100))))))

(deftest live-query-acting-as-a-user
  (if-not endpoint
    (println "SKIP watch-acting-integration: SYNTHIGY_RLS_TEST_ENDPOINT is unset")
    (binding [core/*client* (core/create-client {:endpoint endpoint
                                                 :client-id (System/getenv "SYNTHIGY_RLS_TEST_CLIENT_ID")
                                                 :client-secret (System/getenv "SYNTHIGY_RLS_TEST_CLIENT_SECRET")})]
      (let [plain (client/run-xsql op {} {:acting-as alice})
            alpha (find-named plain "Project Alpha")
            beta (find-named (client/run-xsql op {} {:acting-as bob}) "Project Beta")
            run (str "clj-" (System/currentTimeMillis))
            w (client/watch-xsql op {} {:acting-as alice})]
        (try
          (testing "RLS: alice sees her project, not bob's"
            (is alpha)
            (is (nil? (find-named plain "Project Beta")))
            (is beta "bob sees his own project"))
          (testing "the watch snapshot is the same rows as the plain query"
            (is (= (names plain) (names @w))))
          (client/sync "project" {:xid (:xid alpha) :description (str "alice " run)} :acting-as alice)
          (client/sync "project" {:xid (:xid beta) :description (str "bob " run)} :acting-as bob)
          (testing "alice's change reaches her watch, refetched as her"
            (is (until #(= (str "alice " run) (:description (find-named @w "Project Alpha"))) 10000)))
          (testing "the refetch is still exactly alice's rows"
            (is (= (names plain) (names @w)))
            (is (nil? (find-named @w "Project Beta"))))
          (finally (client/close-watch! w)))))))
