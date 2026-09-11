(ns synthigy.client.compose-test
  "Tests for compose-tree / compose-forest — pure helpers for nesting flat
   get-tree / search-tree results."
  (:require
   [clojure.test :refer [deftest is testing]]
   [synthigy.client.core :as client]))

;; Sample family used throughout — Howard Sr is root, 3 generations deep.
(def family
  [{:xid "howard" :first-name "Howard"  :father {}}
   {:xid "mary"   :first-name "Mary"    :father {:xid "howard"}}
   {:xid "rich"   :first-name "Richard" :father {:xid "howard"}}
   {:xid "ben"    :first-name "Ben"     :father {:xid "rich"}}
   {:xid "pete"   :first-name "Peter"   :father {:xid "rich"}}
   {:xid "may"    :first-name "May"     :father {:xid "rich"}}])

;;; compose-tree

(deftest nests-three-generations
  (let [t (client/compose-tree family {:on :father :root-id "howard"})]
    (is (= "howard" (:xid t)))
    (is (= 2 (count (:children t))))
    (let [richard (first (filter #(= "rich" (:xid %)) (:children t)))]
      (is (= 3 (count (:children richard))))
      (is (= #{"ben" "may" "pete"}
             (set (map :xid (:children richard))))))))

(deftest defaults-root-to-first-record
  (let [t (client/compose-tree family {:on :father})]
    (is (= "howard" (:xid t)))))

(deftest empty-input-returns-nil
  (is (nil? (client/compose-tree [] {:on :father})))
  (is (nil? (client/compose-tree nil {:on :father}))))

(deftest unknown-root-returns-nil
  (is (nil? (client/compose-tree family {:on :father :root-id "ghost"}))))

(deftest single-record-input
  (let [t (client/compose-tree
            [{:xid "alone" :first-name "Solo" :father {}}]
            {:on :father})]
    (is (= "alone" (:xid t)))
    (is (= [] (:children t)))))

(deftest accepts-bare-parent-id
  ;; Parent encoded as a string id directly, not a nested map
  (let [flat [{:xid "a" :father nil}
              {:xid "b" :father "a"}
              {:xid "c" :father "b"}]
        t (client/compose-tree flat {:on :father :root-id "a"})]
    (is (= "b" (get-in t [:children 0 :xid])))
    (is (= "c" (get-in t [:children 0 :children 0 :xid])))))

(deftest kebab-snake-variants-of-on
  ;; Ask with :parent_ref (snake), records carry :parent-ref (kebab)
  (let [flat [{:xid "a" :parent-ref nil}
              {:xid "b" :parent-ref {:xid "a"}}]
        t (client/compose-tree flat {:on :parent_ref :root-id "a"})]
    (is (= "b" (get-in t [:children 0 :xid])))))

(deftest custom-children-key
  (let [t (client/compose-tree family
            {:on :father :root-id "howard" :children-key :descendants})]
    (is (vector? (:descendants t)))
    (is (nil? (:children t)))))

(deftest direct-self-cycle-doesnt-loop
  ;; a.father = a is stored, but our linking step already filters
  ;; (id = parent-id) so `a` stays root; b hangs off normally.
  (let [flat [{:xid "a" :father {:xid "a"}}
              {:xid "b" :father {:xid "a"}}]
        t (client/compose-tree flat {:on :father :root-id "a"})]
    (is (= "a" (:xid t)))
    (is (= 1 (count (:children t))))
    (is (= "b" (-> t :children first :xid)))))

(deftest transitive-cycle-doesnt-loop
  ;; a → b → a — both think they have a parent; visited-set breaks recursion.
  (let [flat [{:xid "a" :father {:xid "b"}}
              {:xid "b" :father {:xid "a"}}]
        t (client/compose-tree flat {:on :father :root-id "a"})]
    (is (= "a" (:xid t)))
    (is (= 1 (count (:children t))))
    (is (= "b" (-> t :children first :xid)))
    (is (empty? (-> t :children first :children)))))

;;; compose-forest

(deftest forest-single-tree
  (let [forest (client/compose-forest family {:on :father})]
    (is (= 1 (count forest)))
    (is (= "howard" (-> forest first :xid)))))

(deftest forest-multiple-roots
  (let [flat [{:xid "r1" :first-name "Root1" :father nil}
              {:xid "r2" :first-name "Root2" :father nil}
              {:xid "c1" :first-name "Child1" :father {:xid "r1"}}
              {:xid "c2" :first-name "Child2" :father {:xid "r2"}}]
        forest (client/compose-forest flat {:on :father})]
    (is (= 2 (count forest)))
    (is (= #{"r1" "r2"} (set (map :xid forest))))
    (let [r1 (first (filter #(= "r1" (:xid %)) forest))]
      (is (= "c1" (-> r1 :children first :xid))))))

(deftest forest-treats-orphans-as-roots
  ;; :father points to an id not in the set — orphan, still a forest root
  (let [flat [{:xid "o" :father {:xid "missing-parent"}}
              {:xid "k" :father {:xid "o"}}]
        forest (client/compose-forest flat {:on :father})]
    (is (= 1 (count forest)))
    (is (= "o" (-> forest first :xid)))
    (is (= "k" (-> forest first :children first :xid)))))

(deftest forest-empty-input
  (is (nil? (client/compose-forest [] {:on :father}))))

(deftest forest-cycle-produces-no-roots
  ;; Both records' parents are in the set → neither qualifies as a root
  ;; under the "parent not in set" rule → empty forest.
  (let [flat [{:xid "a" :father {:xid "b"}}
              {:xid "b" :father {:xid "a"}}]
        forest (client/compose-forest flat {:on :father})]
    (is (= [] forest))))
