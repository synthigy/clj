(ns synthigy.client.watch-acting-live-test
  "Live: a trusted client's live XSQL list acting as a user sees that user's rows only (RLS),
  on the snapshot and on every refetch, with the options generated watch-<name> fns pass.

  Run with `npm run test:files`; skips unless SYNTHIGY_RLS_TEST_ENDPOINT,
  SYNTHIGY_RLS_TEST_CLIENT_{ID,SECRET} (trusted superuser) and SYNTHIGY_RLS_TEST_{ALICE,BOB}
  are set, against the guarded Project Management model (examples/datasets/project-management@0.1.2 + seed/)."
  (:require
   [cljs.test :refer [deftest is async]]
   [promesa.core :as p]
   [synthigy.client :as client]
   [synthigy.client.core :as core]))

(def env (if (exists? js/process) (.-env js/process) #js {}))
(defn env-of [k] (aget env k))
(def endpoint (env-of "SYNTHIGY_RLS_TEST_ENDPOINT"))
(def alice (env-of "SYNTHIGY_RLS_TEST_ALICE"))
(def bob (env-of "SYNTHIGY_RLS_TEST_BOB"))
(def op {:op "search" :source "@search list\nproject\n  xid\n  name\n  description" :entity "project"})

(defn names [rows] (sort (map :name rows)))
(defn find-named [rows n] (first (filter #(= n (:name %)) rows)))

(defn until [pred ms]
  (p/loop [left ms]
    (cond (pred) true
          (neg? left) false
          :else (p/let [_ (p/delay 100)] (p/recur (- left 100))))))

(deftest live-query-acting-as-a-user
  (if-not endpoint
    (do (println "SKIP watch-acting-live: set SYNTHIGY_RLS_TEST_ENDPOINT, the client and the ALICE/BOB xids")
        (is true))
    (async done
      (set! core/*client* (core/create-client {:endpoint endpoint
                                               :client-id (env-of "SYNTHIGY_RLS_TEST_CLIENT_ID")
                                               :client-secret (env-of "SYNTHIGY_RLS_TEST_CLIENT_SECRET")}))
      (let [run (str "cljs-" (js/Date.now))
            w* (atom nil)]
        (-> (p/let [plain (client/run-xsql op {} {:acting-as alice})
                    alpha (find-named plain "Project Alpha")
                    _ (is alpha)
                    _ (is (nil? (find-named plain "Project Beta")) "RLS hides bob's project from alice")
                    bobs (client/run-xsql op {} {:acting-as bob})
                    beta (find-named bobs "Project Beta")
                    _ (is beta "bob sees his own project")
                    w (client/watch-xsql op {} {:acting-as alice})
                    _ (reset! w* w)
                    _ (is (= (names plain) (names @w)) "the watch snapshot is the same rows as the plain query")
                    _ (client/sync "project" {:xid (:xid alpha) :description (str "alice " run)} :acting-as alice)
                    _ (client/sync "project" {:xid (:xid beta) :description (str "bob " run)} :acting-as bob)
                    seen (until #(= (str "alice " run) (:description (find-named @w "Project Alpha"))) 10000)]
              (is seen "alice's change reaches her watch, refetched as her")
              (is (= (names plain) (names @w)) "the refetch is still exactly alice's rows")
              (is (nil? (find-named @w "Project Beta"))))
            (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e) " " (pr-str (ex-data e))))))
            (p/finally (fn [_ _] (some-> @w* client/close-watch!) (done))))))))
