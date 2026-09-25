(ns synthigy.client.onboard-integration-test
  "Onboard is gated on the client's principal administering the account —
   RBAC update on User plus the row inside its owner-group write scope, the
   shape the shipped User Provisioner role grants. The shared `test-sdk`
   integration client (integration_test.clj) is ROOT, so this suite runs on
   its OWN dedicated env pair to exercise the real, scoped principal.

     SYNTHIGY_TEST_ENDPOINT                (default http://localhost:7887)
     SYNTHIGY_PROVISION_CLIENT_ID          \\ required, or the suite self-skips
     SYNTHIGY_PROVISION_CLIENT_SECRET      /

   Creating the dedicated provisioning client (one-time, via nREPL):

     (require '[synthigy.iam :as iam] '[synthigy.dataset :as dataset]
              '[synthigy.dataset.id :as id] '[synthigy.iam.access :as access])
     (access/with-principal nil
       (let [id \"synthigy-clj-sdk-provisioner\"
             role (dataset/get-entity :iam/user-role {:name \"User Provisioner\"} {(id/key) nil})
             owners (dataset/sync-entity :iam/user-group {:name (str id \"-owners\")})
             client (iam/add-client {:id id :name \"Synthigy Clojure SDK provisioner\"
                                     :type :confidential
                                     :settings {\"allowed-grants\" [\"client_credentials\"]}})]
         (dataset/stack-entity :iam/user {:name id
                                          :roles [{(id/key) (id/extract role)}]
                                          :groups [{(id/key) (id/extract owners)}]})
         (access/load-rules)
         client))
     ;; -> {:secret \"<copy into SYNTHIGY_PROVISION_CLIENT_SECRET>\"}

   Onboarding does not create accounts — each test
   creates its own account over `/data` first, with a client-minted xid and
   stamped with the client's own owner group so it lands inside the
   principal's write scope, then mints a ticket for it.

   Run: clj -M:onboard-integration"
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [synthigy.client :as client]
   [synthigy.client.core :as core]))


(def endpoint (or (System/getenv "SYNTHIGY_TEST_ENDPOINT") "http://localhost:7887"))
(def client-id (System/getenv "SYNTHIGY_PROVISION_CLIENT_ID"))
(def client-secret (System/getenv "SYNTHIGY_PROVISION_CLIENT_SECRET"))

(def ^:private test-client
  (when (and client-id client-secret)
    (core/create-client {:endpoint endpoint :client-id client-id :client-secret client-secret})))

(def ^:private created (atom []))

(def ^:private root-client
  "Cleanup identity. User Provisioner grants create/read/update on User but
   deliberately NOT delete, so the provisioner cannot purge what it made."
  (let [id (System/getenv "SYNTHIGY_TEST_CLIENT_ID")
        secret (System/getenv "SYNTHIGY_TEST_CLIENT_SECRET")]
    (when (and id secret)
      (core/create-client {:endpoint endpoint :client-id id :client-secret secret}))))

;; These tests ran as skips for their whole life, so nothing ever noticed them
;; piling up accounts on the dev tenant.
(use-fixtures :once
  (fn [f]
    (binding [core/*client* test-client]
      (try (f)
           (finally
             (when root-client
               (binding [core/*client* root-client]
                 (doseq [xid @created]
                   (client/purge :iam/user {:xid {:-eq xid}} [:xid])))))))))

(def ^:private owner-group
  "The client's own first group — the partition its accounts must be stamped
   into. nil for an unscoped (superuser) client, where no stamp is needed."
  (delay (some-> (client/get :iam/user {:name client-id} {:groups [:xid]})
                 :groups first :xid)))

(defn- create-account! [username]
  (let [xid (core/new-xid)]
    (client/sync :iam/user (cond-> {:xid xid :name username :active false}
                             @owner-group (assoc :owner-group {:xid @owner-group})))
    (swap! created conj xid)
    xid))


(deftest onboard-mint-and-reset-test
  (if-not test-client
    (println "SKIP onboard-integration: set SYNTHIGY_PROVISION_CLIENT_ID/SECRET"
             "(a confidential client holding User Provisioner) to run")
    (let [username (str "sdk-onboard-test-" (System/currentTimeMillis) "@example.com")
          xid      (create-account! username)]
      (testing "mint"
        (let [out (client/onboard xid :methods ["password"])]
          (is (some-> (:onboard_url out) (str/includes? "/oauth/claim?token=")))
          (is (int? (:expires_at out)))
          (is (= xid (get-in out [:user :xid])))))
      (testing "reset re-mints the same account without touching active"
        (let [out (client/onboard xid :reset true :methods ["password"])]
          (is (some-> (:onboard_url out) (str/includes? "/oauth/claim?token=")))
          (is (= xid (get-in out [:user :xid])))))
      (testing "an unknown xid reports user_not_found"
        (try
          (client/onboard (core/new-xid))
          (is false "expected an exception")
          (catch clojure.lang.ExceptionInfo e
            (is (= "USER_NOT_FOUND" (:code (ex-data e))))))))))

(deftest onboard-complete-test
  (if-not test-client
    (println "SKIP onboard-integration: set SYNTHIGY_PROVISION_CLIENT_ID/SECRET"
             "(a confidential client holding User Provisioner) to run")
    (let [username (str "sdk-onboard-complete-test-" (System/currentTimeMillis) "@example.com")
          xid      (create-account! username)
          out      (client/onboard xid :methods ["password"])
          ticket   (some-> (re-find #"token=([^&]+)" (:onboard_url out))
                            second
                            (java.net.URLDecoder/decode "UTF-8"))]
      (testing "redeems its own ticket indirectly, without ever setting a credential"
        (let [result (client/onboard-complete ticket)]
          (is (true? (:active result)))
          (is (= xid (get-in result [:user :xid]))))))))
