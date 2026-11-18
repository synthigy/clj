(ns synthigy.client-test
  (:require [cljs.test :refer [deftest is testing async]]
            [promesa.core :as p]
            [synthigy.client :as client]
   [synthigy.client.core :as core]
            [synthigy.client.auth :as auth]
            [synthigy.client.http :as http]))


(deftest op-builders
  (testing "op-search normalizes args and selection"
    (let [op (core/op-search :user {:active true} [:name :email])]
      (is (= "search" (:op op)))
      (is (= "user" (:entity op)))
      (is (= {:active true} (:args op)))
      (is (= {:name nil :email nil} (:selections op)))))

  (testing "op-sync passes :data through RAW — the server normalizes top-level
            model keys itself (dash-aware), and NOT normalizing client-side is
            what preserves opaque jsonb value keys (someKey stays someKey)."
    (let [op (core/op-sync :user_role {:role-name "admin"
                                       :config {:someKey 1 :nested {:otherKey 2}}})]
      (is (= "sync" (:op op)))
      (is (= {:role-name "admin" :config {:someKey 1 :nested {:otherKey 2}}}
             (:data op))
          "keys are untouched at every depth — jsonb values must survive intact")))

  (testing "op-get-tree shape"
    (let [op (core/op-get-tree :folder "abc" :parent [:name])]
      (is (= "get-tree" (:op op)))
      (is (= "abc" (:root op)))
      (is (= "parent" (:on op))))))


(deftest create-client-static-token
  (testing "static token path builds a provider"
    (let [c (core/create-client
             {:endpoint "http://localhost:7887"
              :token "test-token"})]
      ;; :endpoint is stored as the server BASE URL; /data is appended by
      ;; the transport's `request`, sibling paths hang off the base.
      (is (= "http://localhost:7887" (:endpoint c)))
      (is (fn? (:token-fn c)))
      (is (= "test-token" ((:token-fn c)))))))


(deftest create-client-token-fn
  (testing ":token-fn inversion accepted as-is"
    (let [c (core/create-client
             {:endpoint "http://x"
              :token-fn (constantly "abc")})]
      (is (= "abc" ((:token-fn c)))))))


(deftest create-client-missing-endpoint-throws
  (is (thrown? js/Error
        (core/create-client {:token "x"}))))


(deftest create-client-missing-auth-throws
  (is (thrown? js/Error
        (core/create-client {:endpoint "http://x"}))))


(deftest auth-static-returns-token
  (let [p (auth/static "abc")]
    (is (= "abc" ((:token-fn p))))))


(deftest result-helpers
  (testing "ok? and all-ok?"
    (is (core/ok? {:foo 1}))
    (is (not (core/ok? (js/Error. "fail"))))
    (is (core/all-ok? [{:a 1} {:b 2}]))
    (is (not (core/all-ok? [{:a 1} (js/Error. "fail")])))))


(deftest compose-tree-flat-to-nested
  (let [records [{:xid "root" :name "A"}
                 {:xid "c1" :name "B" :parent {:xid "root"}}
                 {:xid "c2" :name "C" :parent {:xid "c1"}}]
        tree (core/compose-tree records {:on :parent :root-id "root"})]
    (is (= "A" (:name tree)))
    (is (= 1 (count (:children tree))))
    (is (= "B" (-> tree :children first :name)))
    (is (= "C" (-> tree :children first :children first :name)))))


;; ── reconnect race: a slow disconnect! must not clobber a newer connect! ───
;;
;; connect! calls disconnect! WITHOUT awaiting its returned promise (it's
;; synchronous — every existing caller relies on "connect! returns the
;; client immediately"). disconnect!'s own teardown (flush-subs! — a real
;; POST) is async. If that POST is still in flight when ANOTHER connect!
;; happens (e.g. a host tokenResolver override reconnecting the shared
;; client, or a silent-renew-driven reconnect), the FIRST disconnect!'s
;; late-arriving `(set! core/*client* nil)` must not wipe out the SECOND,
;; now-current client. Found live: frontend/modeling's log-cockpit hit
;; NOT_CONNECTED moments after a legitimate reconnect, exactly this race.

(defn- stub-post-json [delay-ms]
  (fn [_url _body] (p/then (p/delay delay-ms) (fn [_] {}))))

(deftest reconnect-does-not-race-a-slow-prior-disconnect
  (testing "client B survives a client A disconnect! that resolves AFTER connect! B already ran"
    (async done
      (let [orig-post http/post-json]
        (set! http/post-json (stub-post-json 30))
        (client/connect! {:endpoint "http://a" :token "a-token"})
        (let [client-a core/*client*]
          ;; Reconnect to B WHILE A's disconnect! (triggered by THIS
          ;; connect! call) is still in flight.
          (client/connect! {:endpoint "http://b" :token "b-token"})
          (let [client-b core/*client*]
            (is (not (identical? client-a client-b)) "sanity: B really is a different client")
            (js/setTimeout
             (fn []
               (is (identical? client-b core/*client*)
                   "client B must still be connected after A's slow disconnect! resolves")
               (set! http/post-json orig-post)
               (client/disconnect!)
               (done))
             80)))))))


(def ruled {:rule "relation_data" :attribute "file" :doc "errors/relation_data"})

(defn json-response [status data]
  (js/Response. (js/JSON.stringify (clj->js data))
                #js {:status status :headers #js {"Content-Type" "application/json"}}))

(defn answering
  [responses]
  (let [fetch js/globalThis.fetch
        client core/*client*
        queue (atom responses)]
    (set! js/globalThis.fetch (fn [u _]
                                (if (.endsWith u "/data")
                                  (let [r (first @queue)] (swap! queue rest) (p/resolved r))
                                  (p/resolved (json-response 200 {})))))
    (set! core/*client* (core/create-client {:endpoint "http://stub" :token "tok"}))
    (fn [] (set! js/globalThis.fetch fetch) (set! core/*client* client))))

(defn refused [pr]
  (p/catch (p/then pr (fn [v] {:resolved v})) ex-data))

(deftest server-errors-keep-rule-attribute-doc
  (async done
    (let [error (merge {:code "BAD_DATA_SHAPE" :message "bad"} ruled)
          restore (answering [(json-response 200 {:results [{:ok false :error error}]})
                              (json-response 400 {:error error})
                              (json-response 200 {:results [{:ok false :error {:code "FK_VIOLATION" :message "x"}}]})])]
      (-> (p/let [per-op (refused (client/stack :document {:file "f-1"}))
                  top (refused (client/search :user nil [:name]))
                  bare (refused (client/search :user nil [:name]))]
            (is (= ruled (select-keys per-op (keys ruled))))
            (is (= ruled (select-keys top (keys ruled))))
            (is (= ["BAD_DATA_SHAPE" 400 "bad"] ((juxt :code :status :message) top)))
            (is (= {} (select-keys bare [:hint :rule :attribute :doc]))))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (restore) (done)))))))

(deftest a-busy-server-is-resent-then-a-retryable-rate-limit
  (async done
    (let [busy (fn [code] (json-response 503 {:error {:code code :message "busy" :retryable true}}))
          saved core/busy-delay-ms
          _ (set! core/busy-delay-ms (constantly 0))
          restore (answering [(busy "SERVER_BUSY") (busy "SERVER_BUSY") (json-response 200 {:results [{:ok true :data []}]})
                              (busy "SERVER_BUSY") (busy "SERVER_BUSY") (busy "SERVER_BUSY")
                              (busy "MODULE_NOT_LOADED") (json-response 200 {:results [{:ok true :data []}]})])]
      (-> (p/let [got-in (client/search :movie nil [:title])
                  gave-up (refused (client/search :movie nil [:title]))
                  other (refused (client/search :movie nil [:title]))
                  after (client/search :movie nil [:title])]
            (is (= [] got-in) "resent until it gets in")
            (is (= {:code "SERVER_BUSY" :category "rate_limit" :retryable true :status 503}
                   (select-keys gave-up [:code :category :retryable :status])) "three attempts, then the refusal")
            (is (= "MODULE_NOT_LOADED" (:code other)) "other refusals go once")
            (is (= [] after) "nothing extra was consumed"))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (set! core/busy-delay-ms saved) (restore) (done)))))))

(deftest a-busy-resend-goes-out-as-the-same-client
  (async done
    (let [fetch js/globalThis.fetch
          root core/*client*
          saved core/busy-delay-ms
          seen (atom [])
          replies (atom [(json-response 503 {:error {:code "SERVER_BUSY" :message "busy"}})
                         (json-response 200 {:results [{:ok true :data []}]})])]
      (set! core/busy-delay-ms (constantly 50))
      (set! core/*client* (core/create-client {:endpoint "http://first" :token "first-token"}))
      (set! js/globalThis.fetch
            (fn [u opts]
              (swap! seen conj [u (aget (.-headers opts) "Authorization")])
              (let [r (first @replies)] (swap! replies rest) (p/resolved r))))
      (js/setTimeout #(set! core/*client* (core/create-client {:endpoint "http://other" :token "other-token"})) 20)
      (-> (client/search :movie nil [:title])
          (p/then (fn [r]
                    (is (= [] r))
                    (is (= [["http://first/data" "Bearer first-token"] ["http://first/data" "Bearer first-token"]] @seen)
                        "the resend went out as the client that sent the first attempt")))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (set! js/globalThis.fetch fetch) (set! core/*client* root)
                       (set! core/busy-delay-ms saved) (done)))))))

(deftest the-request-timeout-bounds-the-busy-wait
  (async done
    (let [saved core/busy-delay-ms
          _ (set! core/busy-delay-ms (constantly 60000))
          restore (answering [(json-response 503 {:error {:code "SERVER_BUSY" :message "busy"}})
                              (json-response 200 {:results [{:ok true :data []}]})])
          client core/*client*]
      (set! core/*client* (assoc client :request-timeout 200))
      (-> (p/let [e (refused (client/search :movie nil [:title]))
                  after (client/search :movie nil [:title])]
            (is (= "SERVER_BUSY" (:code e)))
            (is (= [] after) "only one attempt was spent"))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (set! core/busy-delay-ms saved) (restore) (done)))))))

(deftest a-busy-resend-gets-only-the-time-left
  (async done
    (let [fetch js/globalThis.fetch
          root core/*client*
          saved core/busy-delay-ms
          calls (atom 0)
          t0 (js/Date.now)]
      (set! core/busy-delay-ms (constantly 200))
      (set! core/*client* (core/create-client {:endpoint "http://stub" :token "tok" :request-timeout 400}))
      (set! js/globalThis.fetch
            (fn [_ opts]
              (if (= 1 (swap! calls inc))
                (p/resolved (json-response 503 {:error {:code "SERVER_BUSY" :message "busy"}}))
                (js/Promise. (fn [_ reject]
                               (.addEventListener (.-signal opts) "abort" #(reject (js/Error. "aborted"))))))))
      (-> (p/let [e (refused (client/search :movie nil [:title]))]
            (is (= 2 @calls))
            (is (some? (:code e)))
            (is (< (- (js/Date.now) t0) 550) "the resend was aborted when the client's timeout ran out"))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (set! js/globalThis.fetch fetch) (set! core/*client* root)
                       (set! core/busy-delay-ms saved) (done)))))))

(deftest the-busy-wait-follows-retry-after
  (dotimes [_ 200]
    (is (<= 1000 (core/busy-delay-ms "2") 3000))
    (is (<= 500 (core/busy-delay-ms nil) 1500))
    (is (<= 5000 (core/busy-delay-ms "3600") 15000))))

(deftest destroy-resolves-true-on-nil-data
  (async done
    (let [restore (answering [(json-response 200 {:results [{:ok true :data nil}]})])]
      (-> (p/let [r (client/destroy "ds-1")]
            (is (true? r)))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (restore) (done)))))))

(defn answering-paths
  [routes]
  (let [fetch js/globalThis.fetch
        client core/*client*
        queues (atom routes)]
    (set! js/globalThis.fetch
          (fn [u _]
            (if-let [k (some #(when (.endsWith u %) %) (keys @queues))]
              (let [r (first (get @queues k))] (swap! queues update k rest) (p/resolved r))
              (p/resolved (json-response 200 {})))))
    (set! core/*client* (core/create-client {:endpoint "http://stub" :token "tok"}))
    (fn [] (set! js/globalThis.fetch fetch) (set! core/*client* client))))

(deftest onboard-keeps-both-error-shapes
  (async done
    (let [server {:error (merge {:code "MODULE_NOT_LOADED" :message "oauth is not loaded" :hint "h"} ruled)}
          restore (answering-paths
                   {"/oauth/onboard" [(json-response 403 {:error "provision_forbidden"}) (json-response 503 server)]
                    "/oauth/onboard/complete" [(json-response 400 {:error "claim_invalid"}) (json-response 503 server)]})]
      (-> (p/let [s1 (refused (client/onboard "user-xid-1"))
                  m1 (refused (client/onboard "user-xid-1"))
                  s2 (refused (client/onboard-complete "t"))
                  m2 (refused (client/onboard-complete "t"))]
            (is (= ["PROVISION_FORBIDDEN" 403] ((juxt :code :status) s1)))
            (is (= ["CLAIM_INVALID" 400] ((juxt :code :status) s2)))
            (doseq [m [m1 m2]]
              (is (= (merge {:code "MODULE_NOT_LOADED" :status 503 :hint "h"} ruled)
                     (select-keys m [:code :status :hint :rule :attribute :doc])))))
          (p/catch (fn [e] (is (nil? e) (str "unexpected: " (ex-message e)))))
          (p/finally (fn [_ _] (restore) (done)))))))

(deftest a-top-level-error-on-a-200-rejects
  (async done
    (let [orig http/request]
      (set! http/request (fn stub
                           ([body] (stub body nil))
                           ([_ _] (p/resolved {:error {:code "NO_OPERATIONS" :message "No operations provided"}}))))
      (set! core/*client* (core/create-client {:endpoint "http://x" :token "t"}))
      (-> (client/search :user nil [:name])
          (p/then (fn [_] (is false "resolved")))
          (p/catch (fn [e] (is (= ["NO_OPERATIONS" 200] ((juxt :code :status) (ex-data e))))))
          (p/finally (fn [_ _]
                       (set! http/request orig)
                       (set! core/*client* nil)
                       (done)))))))
