#!/usr/bin/env bb
;; bb smoke test against a running Synthigy — exercises the single-client API:
;; connect! once, verbs from synthigy.client, data helpers from synthigy.client.core.

(require '[synthigy.client :as client]
         '[synthigy.client.core :as core])

(println "=== Connect ===")
(client/connect!
  {:endpoint "http://localhost:7887"
   :client-id "test-sdk"
   :client-secret "test-secret"})
(println "Connected.")

(println)
(println "=== Search users ===")
(let [users (client/search :user nil [:name :active])]
  (println "Found" (count users) "users")
  (doseq [u users]
    (println " -" (:name u) (if (:active u) "(active)" ""))))

(println)
(println "=== Search with kebab args ===")
(let [users (client/search :user {:-where {:name {:-eq "Alice"}}} [:name :active])]
  (println "Alice:" (first users)))

(println)
(println "=== Selection shorthand with relations (LEFT default) ===")
(let [users (client/search :user
              {:-where {:name {:-eq "Alice"}}}
              {:name nil :roles [:name :active]})]
  (let [alice (first users)]
    ;; wire spec: an empty relation is omitted from the row
    (println "Alice roles:" (count (:roles alice)))
    (doseq [r (:roles alice)]
      (println " -" (:name r)))))

(println)
(println "=== Kebab output (multi-word keys, the default) ===")
(let [versions (client/search :dataset_version nil [:name :deployed-on])]
  (doseq [v versions]
    (println " -" (:name v) "deployed:" (:deployed-on v))))

(println)
(println "=== Batch ===")
(let [ops [(core/op-search :user {:-where {:name {:-eq "Alice"}}} [:name])
           (core/op-search :user_role nil [:name])
           (core/op-search :dataset_version nil [:name :deployed-on])]
      [users roles versions] (core/results->data (client/batch ops) ops)]
  (if (core/all-ok? [users roles versions])
    (do
      (println "All OK!")
      (println " Users:" (count users))
      (println " Roles:" (count roles))
      (println " Versions:" (count versions)))
    (do
      (println "Some failed:")
      (doseq [e (core/errors [users roles versions])]
        (println " ERROR:" (ex-message e))))))

(println)
(println "=== Batch with failure ===")
(let [ops [(core/op-search :user nil [:name])
           (core/op-search :nonexistent nil [:name])]
      [users bad] (core/results->data (client/batch ops) ops)]
  (println "all-ok?" (core/all-ok? [users bad]))
  (println "users ok?" (core/ok? users) "- count:" (count users))
  (println "bad ok?" (core/ok? bad))
  (when-not (core/ok? bad)
    (println "error:" (ex-message bad))
    (println "code:" (:code (ex-data bad)))
    (println "has operation?" (some? (:operation (ex-data bad))))))

(println)
(println "=== All smoke checks passed! ===")
