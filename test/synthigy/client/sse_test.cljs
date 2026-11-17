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
