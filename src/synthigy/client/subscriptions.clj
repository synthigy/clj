(ns synthigy.client.subscriptions
  "RAW server-side subscription-set management — ADVANCED / low-level.

   Prefer `synthigy.client/watch` and `synthigy.client/watch-query`: the watch
   multiplexer manages this same server-side set automatically (union of all
   watch interests, re-asserted on every SSE reconnect).

   WARNING: the server keeps ONE subscription set per client identity and
   `/data/subscription/set` is FULL-REPLACE. Calling these fns while any
   watch/watch-query is active on the same client CLOBBERS the multiplexer's
   set (and vice versa) — live updates silently stop. Use this namespace only
   when you consume `/data/events` yourself via `synthigy.client/listen` and
   no watches are open."
  (:require
   [synthigy.client.core :as core]
   [synthigy.client.http :as http]))

(defn subscribe
  "Subscribe to change notifications for specific record `xids`.

   Records-scoped per the wire contract. Returns a handle (string) to
   pass to `unsubscribe`. Re-subscribing the same records is idempotent.

   Options:
     :operations — seq narrowing to an op subset (e.g. [:update :link])
     :key        — custom handle (default: a stable hash of the records)

   Subscriptions are mirrored client-side and persist on the server per
   client identity, surviving SSE reconnects."
  ([xids] (subscribe xids nil))
  ([xids {:keys [operations key]}]
   (let [client (core/the-client)
         d (core/normalize-descriptor {:records xids :operations operations})
         k (clojure.core/or key (core/descriptor-key d))]
     (swap! (:subs client) assoc-in [:data k] d)
     (http/flush-subs!)
     k)))

(defn unsubscribe
  "Remove a record subscription by its `handle` (from `subscribe`).
   Unknown handles are a no-op."
  [handle]
  (let [client (core/the-client)]
    (when (contains? (:data @(:subs client)) handle)
      (swap! (:subs client) update :data dissoc handle)
      (http/flush-subs!)))
  nil)

(defn subscribe-entity
  "Subscribe to ALL change notifications for whole `entities` (seq of
   entity names). Use this to tail an entity without knowing record ids
   up front."
  [entities]
  (let [client (core/the-client)]
    (swap! (:subs client) update :entities into (map name entities))
    (http/flush-subs!)))

(defn unsubscribe-entity
  "Remove entity-level subscriptions for `entities`."
  [entities]
  (let [client (core/the-client)]
    (swap! (:subs client) update :entities #(reduce disj % (map name entities)))
    (http/flush-subs!)))

(defn subscribe-model
  "Subscribe to model-deploy notifications. `:raw true` watches the raw
   deployed model; default watches the runtime model."
  [& {:keys [raw]}]
  (let [client (core/the-client)]
    (swap! (:subs client) update :models conj (if raw "deployed-model" "runtime-model"))
    (http/flush-subs!)))

(defn set-subscriptions
  "Replace the entire record-subscription set in one call. `descriptors`
   is a seq of {:records [...] :operations [...]?} maps. Clears any
   entity/relation/model subs."
  [descriptors]
  (let [data (into {} (map (fn [m]
                             (let [d (core/normalize-descriptor m)]
                               [(core/descriptor-key d) d]))
                           descriptors))]
    (let [client (core/the-client)]
      (reset! (:subs client) {:data data :entities #{} :relations #{} :models #{}})
      (http/flush-subs!))))

(defn clear-subscriptions
  "Remove all subscriptions for this session."
  []
  (let [client (core/the-client)]
    (reset! (:subs client) {:data {} :entities #{} :relations #{} :models #{}})
    (http/flush-subs!)))

(defn subscriptions
  "Return the server's view of this client's current subscriptions."
  []
  (http/get-json (str (http/base-url) "/data/subscription/status") {}))

