(ns synthigy.client.key
  "Key normalization for the Synthigy client.

  Converts kebab-case and camelCase keys to snake_case
  for the /data endpoint wire format."
  (:require
   [clojure.string :as str]))


(defn ->snake_case
  "Convert a keyword or string to snake_case keyword.
  Preserves leading underscore/dash prefixes (normalized to _)."
  [k]
  (let [s (name k)
        [_ prefix base] (re-matches #"([_\-]+)(.*)" s)]
    (if prefix
      (keyword
       (str (str/replace prefix "-" "_")
            (-> base
                (str/replace #"([a-z])([A-Z])" "$1_$2")
                (str/replace #"[-\s]+" "_")
                str/lower-case)))
      (keyword
       (-> s
           (str/replace #"([a-z])([A-Z])" "$1_$2")
           (str/replace #"[-\s]+" "_")
           str/lower-case)))))


(defn normalize-keys-deep
  "Recursively normalize all map keys to snake_case."
  [data]
  (cond
    (map? data)
    (reduce-kv
     (fn [m k v]
       (if-not k m
               (assoc m (->snake_case k) (normalize-keys-deep v))))
     {}
     data)

    (vector? data)
    (mapv normalize-keys-deep data)

    :else data))
