(ns synthigy.client.sse-test
  (:require [cljs.test :refer [deftest is async]]
            [synthigy.client.core :as core]
            [synthigy.client.http :as http]
            [synthigy.client.sse :as sse]))

(deftest a-refused-stream-stops-on-the-servers-code
  (async done
    (let [orig http/authed
          calls (atom 0)
          body (js/JSON.stringify (clj->js {:error {:code "FORBIDDEN" :message "no"}}))
          _ (set! http/authed (fn [_ _ _]
                                (swap! calls inc)
                                (js/Promise.resolve {:status 403 :stream (.-body (js/Response. body))})))
          stop (binding [core/*client* (core/create-client {:endpoint "http://x" :token "t"})]
                 (sse/listen (fn [_]) {}))]
      (js/setTimeout (fn []
                       (stop)
                       (set! http/authed orig)
                       (is (= 1 @calls) "the engine's FORBIDDEN in the body ends the stream instead of a retry")
                       (done))
                     1500))))

(deftest any-403-ends-the-stream-and-reaches-on-error
  (async done
    (let [orig http/authed
          calls (atom 0)
          errors (atom [])
          body (js/JSON.stringify (clj->js {:error {:code "ENTITY_FORBIDDEN" :message "no events"}}))
          _ (set! http/authed (fn [_ _ _]
                                (swap! calls inc)
                                (js/Promise.resolve {:status 403 :stream (.-body (js/Response. body))})))
          stop (binding [core/*client* (core/create-client {:endpoint "http://x" :token "t"})]
                 (sse/listen (fn [_]) {:on-error #(swap! errors conj %)}))]
      (js/setTimeout (fn []
                       (stop)
                       (set! http/authed orig)
                       (is (= 1 @calls) "a 403 with any code ends the stream")
                       (is (= ["ENTITY_FORBIDDEN"] (map (comp :code ex-data) @errors)))
                       (done))
                     1500))))

(deftest each-connect-mints-its-own-stream-id
  (async done
    (let [orig http/authed
          urls (atom [])
          opened (atom [])
          _ (set! http/authed (fn [_ url _]
                                (swap! urls conj url)
                                (js/Promise.resolve {:status 200 :stream (.-body (js/Response. ""))})))
          stop (binding [core/*client* (core/create-client {:endpoint "http://x" :token "t"})]
                 (sse/listen (fn [_]) {:on-connect #(swap! opened conj %)}))]
      (js/setTimeout (fn []
                       (stop)
                       (set! http/authed orig)
                       (is (= 2 (count (set @opened))) "a fresh id per connection")
                       (is (every? #(re-matches #"[A-Za-z0-9_-]{8,64}" %) @opened))
                       (is (= (map #(str "http://x/data/events?stream=" %) @opened) @urls))
                       (done))
                     1500))))
