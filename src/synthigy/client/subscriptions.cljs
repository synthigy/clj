(ns synthigy.client.subscriptions
  "`subscriptions` reports the subscription set the engine holds for this
   client's watch stream (ClojureScript, promise-returning). Declare interest
   with `synthigy.client/watch`.

   The other fns are internal (^:no-doc): each replaces the client's whole
   subscription set behind the watch multiplexer's back."
  (:require
   [synthigy.client.core :as core]
   [synthigy.client.http :as http]
   [promesa.core :as p]))

(defn ^:no-doc subscribe
  "Subscribe to change notifications for record `xids`. Returns a Promise
   resolving to the handle string. Options: :operations :key."
  ([xids] (subscribe xids nil))
  ([xids {:keys [operations key]}]
   (let [client (core/the-client)
         d (core/normalize-descriptor {:records xids :operations operations})
         k (clojure.core/or key (core/descriptor-key d))]
     (swap! (:subs client) assoc-in [:data k] d)
     (p/then (http/flush-subs!) (constantly k)))))

(defn ^:no-doc unsubscribe
  "Remove a record subscription by `handle`. Returns a Promise."
  [handle]
  (let [client (core/the-client)]
    (if (contains? (:data @(:subs client)) handle)
      (do (swap! (:subs client) update :data dissoc handle)
          (http/flush-subs!))
      (p/resolved nil))))

(defn ^:no-doc subscribe-entity
  "Subscribe to ALL changes for whole `entities`. Returns a Promise."
  [entities]
  (let [client (core/the-client)]
    (swap! (:subs client) update :entities into (map name entities))
    (http/flush-subs!)))

(defn ^:no-doc unsubscribe-entity
  "Remove entity-level subscriptions for `entities`. Returns a Promise."
  [entities]
  (let [client (core/the-client)]
    (swap! (:subs client) update :entities #(reduce disj % (map name entities)))
    (http/flush-subs!)))

(defn ^:no-doc subscribe-model
  "Subscribe to model-deploy notifications. Returns a Promise."
  [& {:keys [raw]}]
  (let [client (core/the-client)]
    (swap! (:subs client) update :models conj (if raw "deployed-model" "runtime-model"))
    (http/flush-subs!)))

(defn ^:no-doc set-subscriptions
  "Replace the record-subscription set. Returns a Promise."
  [descriptors]
  (let [data (into {} (map (fn [m]
                             (let [d (core/normalize-descriptor m)]
                               [(core/descriptor-key d) d]))
                           descriptors))]
    (let [client (core/the-client)]
      (reset! (:subs client) {:data data :entities #{} :relations #{} :models #{}})
      (http/flush-subs!))))

(defn ^:no-doc clear-subscriptions
  "Remove all subscriptions for this session. Returns a Promise."
  []
  (let [client (core/the-client)]
    (reset! (:subs client) {:data {} :entities #{} :relations #{} :models #{}})
    (http/flush-subs!)))

(defn subscriptions
  "Return the server's view of this client's current subscriptions (the watch stream's set while one is open). Returns a Promise."
  []
  (let [stream (some-> (:mux (core/the-client)) deref :stream)]
    (http/get-json (str (http/base-url) "/data/subscription/status")
                   (cond-> {} stream (assoc :query-params {"stream" stream})))))

