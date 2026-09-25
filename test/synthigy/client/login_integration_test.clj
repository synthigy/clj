(ns synthigy.client.login-integration-test
  "Live login against a running engine, the browser played headlessly.

   Needs a confidential client allowing authorization_code with
   SYNTHIGY_TEST_LOGIN_REDIRECT registered, and a password user:
   SYNTHIGY_TEST_LOGIN_CLIENT_ID/_SECRET/_USER/_PASSWORD.

   Run: clj -M:login-integration"
  (:require
   [babashka.http-client :as http]
   [clojure.string :as str]
   [clojure.test :refer [deftest is]]
   [synthigy.client :as client]
   [synthigy.client.core :as core]
   [synthigy.client.login :as login]
   [synthigy.client.login-test :refer [parse-query]])
  (:import [java.net URI]))

(def endpoint (or (System/getenv "SYNTHIGY_TEST_ENDPOINT") "http://localhost:7887"))
(def client-id (System/getenv "SYNTHIGY_TEST_LOGIN_CLIENT_ID"))
(def client-secret (System/getenv "SYNTHIGY_TEST_LOGIN_CLIENT_SECRET"))
(def username (System/getenv "SYNTHIGY_TEST_LOGIN_USER"))
(def password (System/getenv "SYNTHIGY_TEST_LOGIN_PASSWORD"))
(def redirect (or (System/getenv "SYNTHIGY_TEST_LOGIN_REDIRECT")
                  "http://127.0.0.1:9/py-sdk/callback"))

(def no-redirects (http/client {:follow-redirects :never}))

(defn location-query
  [resp]
  (parse-query (.getRawQuery (URI. (get-in resp [:headers "location"])))))

(defn browser-login
  "Play the browser: authorize → submit credentials → callback params."
  [authorize-url]
  (let [page (http/get authorize-url {:client no-redirects :throw false})
        callback (http/post (str endpoint "/oauth/login")
                            {:form-params {"username" username "password" password
                                           "state" ((location-query page) "state")}
                             :client no-redirects :throw false})]
    (location-query callback)))

(defn live-client
  [secret store]
  (core/create-client {:endpoint endpoint :client-id client-id :client-secret secret
                       :login-store store}))

(deftest live-login
  (if-not (and client-id client-secret username password)
    (println "skipped: set SYNTHIGY_TEST_LOGIN_CLIENT_ID/SECRET/USER/PASSWORD")
    (let [store (login/memory-login-store)]
      (binding [core/*client* (live-client client-secret store)]
        (let [xids (vec (for [_ (range 2)]
                          (let [cb (browser-login (:url (client/login-start redirect :return-to "/after"
                                                                            :scope "openid profile")))
                                out (client/login-complete (cb "code") (cb "state") redirect)]
                            (is (= username (get-in out [:user :name])))
                            (is (some #{"openid"} (get-in out [:user :scopes])))
                            (is (= "/after" (:return-to out)))
                            (is (not (str/blank? (get-in out [:tokens :access_token]))))
                            (get-in out [:user :xid]))))]
          (is (not (str/blank? (first xids))))
          (is (apply = xids) "same user, same xid across logins")))
      (let [cb (binding [core/*client* (live-client client-secret store)]
                 (browser-login (:url (client/login-start redirect))))
            data (binding [core/*client* (live-client "wrong" store)]
                   (ex-data (is (thrown? Exception
                                         (client/login-complete (cb "code") (cb "state") redirect)))))]
        (is (= "LOGIN_EXCHANGE_FAILED" (:code data)))
        (is (#{400 401} (:status data)))))))
