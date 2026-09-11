(ns synthigy.client.selection
  "Selection normalization for the Synthigy client.

  Converts user-friendly selection shorthand into the wire format
  expected by the /data endpoint."
  (:require
   [synthigy.client.key :as key]))

(defn normalize
  "Normalize a user-friendly selection into /data wire format.

  Normalizes keys to snake_case (kebab-case and camelCase accepted).

  Supports shorthand syntax:
  - nil / true              → nil (include field)
  - [:name :email]          → {:name nil :email nil}
  - {:roles {:name nil}}    → {:roles [{:selections {:name nil}}]}
  - {:roles [:name]}        → {:roles [{:selections {:name nil}}]}
  - [:name {:roles [:name]} :active] → mixed vectors
  - {:roles {:selections {:name nil} :args {...}}} → wraps in vector

  Existing internal format [{:selections {...}}] passes through unchanged.

  Join semantics are the SERVER's: absent `_join` is LEFT (a selection is
  a projection and never drops parents; relation args filter the related
  rows). The client injects nothing — pass `{:_join :inner}` explicitly
  when the relation's existence should scope its parent."
  [selection]
  (cond
    (or (nil? selection) (true? selection))
    nil

    ;; Vector of keywords — expand to map
    (and (vector? selection) (every? keyword? selection))
    (normalize (zipmap selection (repeat nil)))

    ;; Mixed vector — keywords and maps together
    (and (vector? selection)
         (every? #(or (keyword? %) (map? %)) selection))
    (normalize
     (reduce (fn [m item]
               (if (keyword? item)
                 (assoc m item nil)
                 (merge m item)))
             {}
             selection))

    (map? selection)
    (reduce-kv
     (fn [m k v]
       (let [k (key/->snake_case k)]
         (cond
           ;; Already internal format: vector of config maps
           (and (vector? v) (seq v) (map? (first v))
                (some #(contains? (first v) %) [:selections :args :alias]))
           (assoc m k (mapv (fn [cfg]
                              (cond-> cfg
                                (:selections cfg) (update :selections normalize)
                                (:args cfg) (update :args key/normalize-keys-deep)))
                            v))

           ;; nil / true — scalar field
           (or (nil? v) (true? v))
           (assoc m k nil)

           ;; Vector — nested selection shorthand (keywords, maps, or mixed)
           (and (vector? v)
                (every? #(or (keyword? %) (map? %)) v))
           (assoc m k [{:selections (normalize v)}])

           ;; Plain map without :selections — nested selection
           (and (map? v) (not (contains? v :selections)))
           (assoc m k [{:selections (normalize v)}])

           ;; Map with :selections — single relation config (unwrapped)
           (and (map? v) (contains? v :selections))
           (assoc m k [(cond-> v
                         (:selections v) (update :selections normalize)
                         (:args v) (update :args key/normalize-keys-deep))])

           :else
           (assoc m k v))))
     {}
     selection)

    :else selection))
