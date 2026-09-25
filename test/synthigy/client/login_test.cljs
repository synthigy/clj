(ns synthigy.client.login-test
  (:require [cljs.test :refer [deftest is testing async]]
            [clojure.string :as str]
            [promesa.core :as p]
            [synthigy.client.login :as login]))

(def node-crypto (js/require "crypto"))

(defn fake-storage []
  (let [m (atom {})]
    #js {:getItem (fn [k] (get @m k))
         :setItem (fn [k v] (swap! m assoc k v))
         :removeItem (fn [k] (swap! m dissoc k))}))

(defn jwt [claims]
  (str "e30." (login/b64url (.encode (js/TextEncoder.) (js/JSON.stringify (clj->js claims)))) ".sig"))

(defn install-browser! [href]
  (let [u (js/URL. href)]
    (set! js/globalThis.sessionStorage (fake-storage))
    (set! js/globalThis.location #js {:href href :pathname (.-pathname u) :search (.-search u) :hash (.-hash u)
                                      :assign (fn [_])})
    (set! js/globalThis.history #js {:replaceState (fn [_ _ url] (set! (.-href js/location) (str "http://app.test" url)))})))

(defn reset-state! []
  (login/clear-timers!)
  (swap! login/state assoc :tokens nil :user nil :expires-at nil :renewal nil :listeners {})
  (login/configure! {:endpoint "http://idp.test/" :client-id "c1" :redirect-uri "http://app.test/cb"
                     :audience "https://synthigy.com" :automatic-silent-renew? false}))


(deftest pkce-challenge-is-s256-of-verifier
  (async done
    (p/let [{:keys [code-verifier code-challenge]} (login/pkce)
            expected (-> (.createHash node-crypto "sha256") (.update code-verifier) (.digest "base64url"))]
      (is (= 43 (count code-verifier)))
      (is (= expected code-challenge))
      (done))))

(deftest jwt-payload-decodes-utf8
  (is (= {:name "Geršak" :nonce "n"} (into {} (login/decode-jwt-payload (jwt {:name "Geršak" :nonce "n"})))))
  (is (nil? (login/decode-jwt-payload "garbage"))))

(deftest authorize-url-carries-audience-and-omits-nil
  (reset-state!)
  (let [url (js/URL. (login/authorize-url (login/config)
                                          {:redirect-uri "http://app.test/cb" :state "s" :nonce "n"
                                           :code-challenge "cc"}))
        q (.-searchParams url)]
    (is (= "http://idp.test/oauth/authorize" (str (.-origin url) (.-pathname url))))
    (is (= "https://synthigy.com" (.get q "audience")))
    (is (= "S256" (.get q "code_challenge_method")))
    (is (= "openid profile" (.get q "scope")))
    (is (not (.has q "prompt")))))

(deftest callback-params-shapes
  (is (= "abc" (:code (login/callback-params "http://a/cb?code=abc&state=s"))))
  (is (= "login_required" (:error (login/callback-params "http://a/cb?error=login_required&state=s"))))
  (is (nil? (login/callback-params "http://a/cb?code=abc")))
  (is (nil? (login/callback-params "http://a/page"))))


(deftest login-complete-round-trip
  (async done
    (reset-state!)
    (let [orig login/exchange
          seen (atom nil)]
      (set! login/exchange (fn [_cfg code pending]
                             (reset! seen [code (:code-verifier pending)])
                             (p/resolved {:access_token "at" :expires_in 300
                                          :id_token (jwt {:sub "u" :nonce (:nonce pending)})})))
      (p/let [request (login/start-request "http://app.test/cb" nil)
              _ (install-browser! (str "http://app.test/cb?code=xyz&state=" (:state request)))
              _ (login/put-pending! request "/page")
              result (login/login-complete)]
        (is (= ["xyz" (:code-verifier request)] @seen))
        (is (= "/page" (:return-to result)))
        (is (= "u" (:sub (login/user))))
        (is (= "at" (login/token)))
        (is (not (str/includes? (.-href js/location) "code=")) "callback params stripped")
        (testing "state is one-shot"
          (set! (.-href js/location) (str "http://app.test/cb?code=xyz&state=" (:state request)))
          (-> (login/login-complete)
              (p/then (fn [_] (is false "second completion must fail")))
              (p/catch (fn [e] (is (= "LOGIN_STATE_UNKNOWN" (:code (ex-data e))))))
              (p/finally (fn [_ _] (set! login/exchange orig) (login/clear-timers!) (done)))))))))

(deftest login-complete-rejects-nonce-mismatch
  (async done
    (reset-state!)
    (let [orig login/exchange]
      (set! login/exchange (fn [_ _ _] (p/resolved {:access_token "at" :id_token (jwt {:nonce "other"})})))
      (p/let [request (login/start-request "http://app.test/cb" nil)]
        (install-browser! (str "http://app.test/cb?code=xyz&state=" (:state request)))
        (login/put-pending! request "/")
        (-> (login/login-complete)
            (p/then (fn [_] (is false "nonce mismatch must fail")))
            (p/catch (fn [e] (is (= "LOGIN_NONCE_MISMATCH" (:code (ex-data e))))))
            (p/finally (fn [_ _]
                         (is (nil? (login/token)))
                         (set! login/exchange orig)
                         (done))))))))

(deftest login-complete-surfaces-authorization-error
  (async done
    (reset-state!)
    (p/let [request (login/start-request "http://app.test/cb" nil)]
      (install-browser! (str "http://app.test/cb?error=access_denied&state=" (:state request)))
      (login/put-pending! request "/")
      (-> (login/login-complete)
          (p/then (fn [_] (is false)))
          (p/catch (fn [e] (is (= "access_denied" (:code (ex-data e))))))
          (p/finally (fn [_ _] (done)))))))


(deftest renew-is-single-flight-and-token-fn-waits-for-it
  (async done
    (reset-state!)
    (swap! login/state assoc :tokens {:access_token "stale"})
    (let [orig-frame login/silent-frame
          orig-exchange login/exchange
          frames (atom 0)
          errors (atom [])
          {:keys [token-fn invalidate-fn]} (login/provider)]
      (login/on :silent-renew-error #(swap! errors conj %))
      (set! login/exchange (fn [_ _ _]
                             (p/resolved {:access_token "fresh" :expires_in 300
                                          :id_token (jwt {:sub "u"
                                                          :nonce (get-in @login/state [:last-nonce])})})))
      (set! login/silent-frame (fn [_cfg request]
                                 (swap! frames inc)
                                 (swap! login/state assoc :last-nonce (:nonce request))
                                 (p/delay 20 {:code "c" :state (:state request)})))
      (invalidate-fn)
      (let [a (login/renew!)
            b (login/renew!)]
        (is (identical? a b) "second renew! joins the first")
        (p/let [t (token-fn)]
          (is (= "fresh" t) "token-fn awaited the renewal instead of returning the stale token")
          (is (= 1 @frames))
          (is (empty? @errors))
          (is (nil? (:renewal @login/state)))
          (set! login/silent-frame orig-frame)
          (set! login/exchange orig-exchange)
          (login/clear-timers!)
          (done))))))

(deftest renew-login-required-emits-and-rejects
  (async done
    (reset-state!)
    (let [orig login/silent-frame
          errors (atom [])]
      (login/on :silent-renew-error #(swap! errors conj (:code (ex-data %))))
      (set! login/silent-frame (fn [_ request] (p/resolved {:error "login_required" :state (:state request)})))
      (-> (login/renew!)
          (p/then (fn [_] (is false)))
          (p/catch (fn [e] (is (= "login_required" (:code (ex-data e))))))
          (p/finally (fn [_ _]
                       (is (= ["login_required"] @errors))
                       (is (nil? (:renewal @login/state)))
                       (set! login/silent-frame orig)
                       (done)))))))

(deftest user-info-merges-when-enabled
  (async done
    (reset-state!)
    (swap! login/state update :config assoc :load-user-info? true)
    (let [orig login/user-info]
      (set! login/user-info (fn [_ _] (p/resolved {:sub "u" :name "Robert"})))
      (p/let [user (login/accept! {:access_token "at" :id_token (jwt {:sub "u" :nonce "n"})} "n")]
        (is (= "Robert" (:name user)))
        (is (= "Robert" (:name (login/user))))
        (set! login/user-info (fn [_ _] (p/resolved {:sub "someone-else"})))
        (-> (login/accept! {:access_token "at" :id_token (jwt {:sub "u" :nonce "n"})} "n")
            (p/then (fn [_] (is false "sub mismatch must fail")))
            (p/catch (fn [e] (is (= "USERINFO_SUB_MISMATCH" (:code (ex-data e))))))
            (p/finally (fn [_ _]
                         (set! login/user-info orig)
                         (login/clear-timers!)
                         (done))))))))
