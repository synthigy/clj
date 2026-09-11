(ns synthigy.client.filter
  "Filter-condition helpers — mirror the Go (Eq/Neq/…) and JS (eq/neq/…)
   SDKs for parity. In Clojure these are thin sugar over plain maps; you
   can always write the literal (`{:_eq v}`) directly. The combinators
   and/or/not are where they earn their keep.

   Alias as `f` and place conditions in a where map keyed by field:

     (require '[synthigy.client.filter :as f])
     (client/search c :user {:-where {:active (f/eq true)
                                      :age    (f/gt 18)}} [:name])
     (client/search c :user {:-where (f/or {:role (f/eq \"admin\")}
                                           {:role (f/eq \"owner\")})} [:name])"
  (:refer-clojure :exclude [and or not in]))

(defn eq          [v] {:_eq v})
(defn neq         [v] {:_neq v})
(defn gt          [v] {:_gt v})
(defn ge          [v] {:_ge v})
(defn lt          [v] {:_lt v})
(defn le          [v] {:_le v})
(defn like        [p] {:_like p})
(defn ilike       [p] {:_ilike p})
(defn is-null     []  {:_is_null true})
(defn is-not-null []  {:_is_not_null true})

;; in/nin flatten one arg-level so (in [1 2]) and (in 1 2) both work.
(defn in  [& vs] {:_in  (vec (flatten vs))})
(defn nin [& vs] {:_nin (vec (flatten vs))})

(defn and [& clauses] {:_and (vec clauses)})
(defn or  [& clauses] {:_or  (vec clauses)})
(defn not [clause]    {:_not clause})
