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

(declare discovery!)

(defn reset-state! []
  (login/clear-timers!)
  (swap! login/state assoc :tokens nil :id-token-hint nil :user nil :expires-at nil :renewal nil :listeners {})
  (login/configure! {:endpoint "http://idp.test/" :client-id "c1" :redirect-uri "http://app.test/cb"
                     :audience "https://synthigy.com" :automatic-silent-renew? false})
  (discovery! {:issuer "http://idp.test"}))

(defn discovery! [document]
  (swap! login/state assoc :discovery {:endpoint "http://idp.test" :document (p/resolved document)}))


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


(defn rejects-with [promise code done]
  (-> promise
      (p/then (fn [v] (is false (str "expected " code ", resolved " (pr-str v)))))
      (p/catch (fn [e] (is (= code (:code (ex-data e))))))
      (p/finally (fn [_ _] (login/clear-timers!) (done)))))

(deftest callback-params-carries-iss
  (is (= "http://idp.test" (:iss (login/callback-params "http://a/cb?code=c&state=s&iss=http%3A%2F%2Fidp.test")))))

(deftest callback-at-matches-origin-and-path-only
  (is (= "c" (:code (login/callback-at "http://app.test/cb?code=c&state=s" "http://app.test/cb"))))
  (is (nil? (login/callback-at "http://app.test/other?code=c&state=s" "http://app.test/cb")))
  (is (nil? (login/callback-at "http://evil.test/cb?code=c&state=s" "http://app.test/cb")))
  (is (nil? (login/callback-at "about:blank" "http://app.test/cb")))
  (is (nil? (login/callback-at nil "http://app.test/cb"))))

(defn security-error [] (throw (js/Error. "SecurityError: cross-origin")))

(deftest poll-callback-swallows-security-error-until-callback
  (async done
    (let [reads (atom 0)]
      (p/let [params (login/poll-callback
                      {:read-href (fn []
                                    (if (< (swap! reads inc) 3)
                                      (security-error)
                                      "http://app.test/cb?code=c&state=s"))
                       :redirect-uri "http://app.test/cb"
                       :interval-ms 5
                       :timeout-ms 1000})]
        (is (= "c" (:code params)))
        (is (= 3 @reads))
        (done)))))

(deftest poll-callback-ignores-path-mismatch-then-times-out
  (async done
    (rejects-with (login/poll-callback {:read-href (constantly "http://app.test/other?code=c&state=s")
                                        :redirect-uri "http://app.test/cb"
                                        :interval-ms 5 :timeout-ms 40})
                  "LOGIN_TIMEOUT" done)))

(deftest poll-callback-reports-closed
  (async done
    (rejects-with (login/poll-callback {:read-href security-error
                                        :closed? (constantly true)
                                        :redirect-uri "http://app.test/cb"
                                        :interval-ms 5 :timeout-ms 1000})
                  "POPUP_CLOSED" done)))

(deftest poll-callback-skips-the-ignored-state
  (async done
    (let [href (atom "http://app.test/cb?error=login_required&state=old")]
      (js/setTimeout #(reset! href "http://app.test/cb?code=c&state=new") 20)
      (p/let [params (login/poll-callback {:read-href #(deref href)
                                           :redirect-uri "http://app.test/cb"
                                           :ignore-state "old"
                                           :interval-ms 5 :timeout-ms 1000})]
        (is (= "new" (:state params)))
        (done)))))


(defn fake-popup
  "A popup whose location answers `respond` with the authorize URL it was sent to; nil leaves it on the engine."
  [respond]
  (let [href (atom "about:blank")
        popup #js {:closed false :focus (fn []) :close (fn [] (this-as ^js w (set! (.-closed w) true)))}
        location (js-obj)]
    (js/Object.defineProperty location "href"
                              #js {:get (fn []
                                          (if (str/starts-with? @href "http://idp.test")
                                            (security-error)
                                            @href))
                                   :set (fn [url]
                                          (reset! href url)
                                          (js/setTimeout #(when-let [answer (respond (js/URL. url))]
                                                            (reset! href answer))
                                                         5))})
    (set! (.-location popup) location)
    popup))

(defn answer
  "Callback href for the authorize URL `u`, with `params` merged over its state."
  [^js u params]
  (let [q (js/URLSearchParams.)]
    (.append q "state" (.get (.-searchParams u) "state"))
    (doseq [[k v] params] (.append q (name k) v))
    (str "http://app.test/cb?" (.toString q))))

(defn install-window! [popup]
  (set! js/globalThis.window #js {:open (fn [& _] popup)
                                  :addEventListener (fn [& _])
                                  :removeEventListener (fn [& _])}))

(defn with-exchange [f]
  (let [orig login/exchange]
    (set! login/exchange (fn [_cfg code request]
                           (p/resolved {:access_token (str "at-" code) :expires_in 300 :id_token "x"
                                        :nonce (:nonce request)})))
    (let [orig-accept login/accept!]
      (set! login/accept! (fn [tokens nonce]
                            (swap! login/state assoc :tokens tokens :user {:sub "u" :nonce nonce})
                            (p/resolved {:sub "u"})))
      (p/finally (f) (fn [_ _] (set! login/exchange orig) (set! login/accept! orig-accept))))))

(deftest login-popup-blocked
  (async done
    (reset-state!)
    (install-window! nil)
    (rejects-with (login/login-popup!) "POPUP_BLOCKED" done)))

(deftest login-popup-live-session-needs-one-leg
  (async done
    (reset-state!)
    (let [legs (atom [])
          popup (fake-popup (fn [u]
                              (swap! legs conj (.get (.-searchParams u) "prompt"))
                              (answer u {:code "c1" :iss "http://idp.test"})))]
      (install-window! popup)
      (with-exchange
        (fn []
          (p/let [a (login/login-popup!)]
            (is (= {:sub "u"} a))
            (is (= ["none"] @legs))
            (is (= "at-c1" (login/token)))
            (is (.-closed popup) "popup closed after success")
            (is (nil? (:popup @login/state)))
            (done)))))))

(deftest login-popup-login-required-moves-to-the-form
  (async done
    (reset-state!)
    (let [legs (atom [])
          popup (fake-popup (fn [u]
                              (let [prompt (.get (.-searchParams u) "prompt")]
                                (swap! legs conj prompt)
                                (if (= "none" prompt)
                                  (answer u {:error "login_required" :iss "http://idp.test"})
                                  (answer u {:code "c2" :iss "http://idp.test"})))))]
      (install-window! popup)
      (with-exchange
        (fn []
          (p/let [_ (login/login-popup!)]
            (is (= ["none" nil] @legs))
            (is (= "at-c2" (login/token)))
            (done)))))))

(deftest login-popup-is-single-flight
  (async done
    (reset-state!)
    (let [opens (atom 0)
          popup (fake-popup (fn [u] (answer u {:code "c"})))]
      (set! js/globalThis.window #js {:open (fn [& _] (swap! opens inc) popup)
                                      :addEventListener (fn [& _]) :removeEventListener (fn [& _])})
      (with-exchange
        (fn []
          (let [a (login/login-popup!)
                b (login/login-popup!)]
            (is (identical? a b))
            (p/let [_ a]
              (is (= 1 @opens))
              (done))))))))

(deftest login-popup-rejects-foreign-state
  (async done
    (reset-state!)
    (install-window! (fake-popup (fn [_] "http://app.test/cb?code=c&state=forged&iss=http%3A%2F%2Fidp.test")))
    (rejects-with (login/login-popup!) "LOGIN_STATE_UNKNOWN" done)))

(deftest login-popup-rejects-iss-mismatch
  (async done
    (reset-state!)
    (install-window! (fake-popup (fn [u] (answer u {:code "c" :iss "http://evil.test"}))))
    (rejects-with (login/login-popup!) "LOGIN_ISSUER_MISMATCH" done)))

(deftest login-popup-blank-iss-only-fails-when-advertised
  (async done
    (reset-state!)
    (install-window! (fake-popup (fn [u] (answer u {:code "c" :iss ""}))))
    (with-exchange
      (fn []
        (p/let [user (login/login-popup!)]
          (is (= "u" (:sub user)) "released engines send iss=\"\" without the flag")
          (discovery! {:issuer "http://idp.test" :authorization_response_iss_parameter_supported true})
          (install-window! (fake-popup (fn [u] (answer u {:code "c" :iss ""}))))
          (rejects-with (login/login-popup!) "LOGIN_ISSUER_MISMATCH" done))))))

(deftest login-popup-surfaces-access-denied
  (async done
    (reset-state!)
    (install-window! (fake-popup (fn [u] (answer u {:error "access_denied"}))))
    (rejects-with (login/login-popup!) "access_denied" done)))

(deftest login-popup-closed-tries-renew-once
  (async done
    (reset-state!)
    (let [popup (fake-popup (constantly nil))
          renews (atom 0)
          orig login/renew!]
      (install-window! popup)
      (set! login/renew! (fn [] (swap! renews inc) (p/rejected (ex-info "no" {:code "login_required"}))))
      (js/setTimeout #(set! (.-closed popup) true) 20)
      (-> (login/login-popup!)
          (p/then (fn [_] (is false)))
          (p/catch (fn [e] (is (= "POPUP_CLOSED" (:code (ex-data e))))))
          (p/finally (fn [_ _]
                       (is (= 1 @renews))
                       (set! login/renew! orig)
                       (done)))))))


(defn install-document!
  "A fake DOM whose iframes land on `respond`'s answer to their src."
  [respond]
  (let [frames (atom [])]
    (set! js/globalThis.window #js {:addEventListener (fn [& _]) :removeEventListener (fn [& _])})
    (set! js/globalThis.document
          #js {:createElement
               (fn [_]
                 (let [win #js {:location #js {:href "about:blank"}}
                       frame #js {:style #js {} :contentWindow win :remove (fn [] (swap! frames conj :removed))}]
                   (js/Object.defineProperty frame "src"
                                             #js {:set (fn [url]
                                                         (js/setTimeout
                                                          #(set! (.. win -location -href) (respond (js/URL. url)))
                                                          10))})
                   frame))
               :body #js {:appendChild (fn [_] (swap! frames conj :added))}})
    frames))

(deftest renew-through-polling-alone
  (async done
    (reset-state!)
    (let [frames (install-document! (fn [u] (answer u {:code "r" :iss "http://idp.test"})))]
      (with-exchange
        (fn []
          (p/let [user (login/renew!)]
            (is (= "u" (:sub user)))
            (is (= "at-r" (login/token)))
            (is (= [:added :removed] @frames))
            (done)))))))

(deftest renew-rejects-iss-mismatch
  (async done
    (reset-state!)
    (install-document! (fn [u] (answer u {:code "r" :iss "http://evil.test"})))
    (rejects-with (login/renew!) "LOGIN_ISSUER_MISMATCH" done)))


(deftest logout-background-posts-hint-and-client
  (async done
    (reset-state!)
    (swap! login/state assoc :tokens {:access_token "at" :id_token "idt"} :user {:sub "u"})
    (let [orig js/globalThis.fetch
          seen (atom nil)]
      (set! js/globalThis.fetch (fn [url opts]
                                  (reset! seen [url (.-method opts) (.-credentials opts)
                                                (str (js/URLSearchParams. (.-body opts)))])
                                  (p/resolved #js {:ok true :status 200})))
      (p/let [_ (login/logout-background!)]
        (is (= ["http://idp.test/oauth/logout" "POST" "omit" "client_id=c1&id_token_hint=idt"] @seen))
        (is (nil? (login/token)))
        (is (nil? (login/user)))
        (set! js/globalThis.fetch orig)
        (done)))))

(defn fake-fetch! [seen resp]
  (let [orig js/globalThis.fetch]
    (set! js/globalThis.fetch (fn [url opts]
                                (swap! seen conj [url (str (js/URLSearchParams. (.-body opts)))])
                                (if (fn? resp) (resp) (p/resolved resp))))
    #(set! js/globalThis.fetch orig)))

(deftest logout-hint-outlives-an-expiry
  (async done
    (reset-state!)
    (swap! login/state assoc :tokens {:access_token "at" :id_token "idt"} :id-token-hint "idt" :user {:sub "u"})
    (login/remove-user!)
    (is (nil? (login/token)))
    (is (= "idt" (login/id-token-hint)) "an expiry forgets the tokens, not the hint")
    (let [seen (atom [])
          restore (fake-fetch! seen #js {:ok true :status 200})]
      (p/let [_ (login/logout-background!)]
        (is (= [["http://idp.test/oauth/logout" "client_id=c1&id_token_hint=idt"]] @seen))
        (is (nil? (login/id-token-hint)) "a logout the engine answered drops the hint")
        (restore)
        (done)))))

(deftest logout-without-a-hint-rejects-and-sends-nothing
  (async done
    (reset-state!)
    (swap! login/state assoc :tokens {:access_token "at"} :user {:sub "u"})
    (let [seen (atom [])
          restore (fake-fetch! seen #js {:ok true :status 200})]
      (-> (login/logout-background!)
          (p/then (fn [_] (is false "should reject")))
          (p/catch (fn [e]
                     (is (= "LOGOUT_NO_HINT" (:code (ex-data e))))
                     (is (empty? @seen))
                     (is (nil? (login/token)) "the tokens go either way")))
          (p/finally (fn [_ _] (restore) (done)))))))

(deftest a-failed-logout-keeps-the-hint-for-a-retry
  (async done
    (reset-state!)
    (swap! login/state assoc :tokens {:access_token "at" :id_token "idt"} :id-token-hint "idt" :user {:sub "u"})
    (let [seen (atom [])
          restore (fake-fetch! seen #js {:ok false :status 400})]
      (-> (login/logout-background!)
          (p/then (fn [_] (is false "should reject")))
          (p/catch (fn [e]
                     (is (= "LOGOUT_FAILED" (:code (ex-data e))))
                     (is (= 400 (:status (ex-data e))))
                     (is (nil? (login/token)))
                     (is (= "idt" (login/id-token-hint)))))
          (p/finally (fn [_ _] (restore) (done)))))))

(deftest a-network-error-keeps-the-hint-too
  (async done
    (reset-state!)
    (swap! login/state assoc :tokens {:access_token "at" :id_token "idt"} :id-token-hint "idt" :user {:sub "u"})
    (let [seen (atom [])
          restore (fake-fetch! seen #(p/rejected (js/TypeError. "Failed to fetch")))]
      (-> (login/logout-background!)
          (p/then (fn [_] (is false "should reject")))
          (p/catch (fn [_] (is (= "idt" (login/id-token-hint)))))
          (p/finally (fn [_ _] (restore) (done)))))))

(deftest accept-replaces-the-hint
  (async done
    (reset-state!)
    (swap! login/state assoc :id-token-hint "old")
    (let [idt (jwt {:sub "u2" :nonce "n"})]
      (p/let [_ (login/accept! {:access_token "at" :id_token idt :expires_in 300} "n")]
        (is (= idt (login/id-token-hint)))
        (done)))))

(deftest a-401-renews-only-while-signed-in
  (async done
    (reset-state!)
    (install-document! (fn [u] (answer u {:error "login_required"})))
    (let [{:keys [invalidate-fn]} (login/provider)]
      (invalidate-fn)
      (js/setTimeout
       (fn []
         (is (nil? (login/renewal)) "signed out: a 401 must not start a renewal")
         (swap! login/state assoc :tokens {:access_token "at"})
         (invalidate-fn)
         (js/setTimeout
          (fn []
            (is (some? (login/renewal)) "signed in: it renews")
            (p/finally (or (login/renewal) (p/resolved nil)) (fn [_ _] (done))))
          0))
       0))))
