(ns synthigy.client.ops-test
  "Pure-logic tests for the operation builders (op-*) and batch result
   helpers (results->data / ok? / all-ok? / errors) — no server needed.
   These lock down the wire shapes every batch op serializes to and the
   error-vs-data discrimination consumers destructure on."
  (:require [clojure.test :refer [deftest is testing]]
            [synthigy.client.core :as core]))

;; ── op builders: wire shape + key normalization ───────────────────────────

(deftest op-search-shape
  (testing "search carries entity (name-coerced), RAW args, normalized selections"
    (let [op (core/op-search :music-album {:-where {:plays {:-gt 1000}}} [:title :plays])]
      (is (= "search" (:op op)))
      (is (= "music-album" (:entity op)))
      ;; args pass through raw — the server normalizes top-level where keys
      ;; itself (dash-aware). See the KEY NORMALIZATION note in core.cljc.
      (is (= {:-where {:plays {:-gt 1000}}} (:args op)))
      (is (contains? op :selections))))

  (testing "write-op :data passes through RAW so opaque jsonb value keys survive"
    (let [op (core/op-sync :repo {:name "r" :config {:defaultBranch "main"
                                                     :nested {:maxRetries 3}}})]
      ;; NOTHING is snake-cased/split — the jsonb value keys defaultBranch /
      ;; maxRetries reach the server intact (were mangled by the old deep
      ;; normalize). Top-level model keys the server normalizes on its side.
      (is (= {:name "r" :config {:defaultBranch "main" :nested {:maxRetries 3}}}
             (:data op)))))
  (testing "keyword entity name is coerced via (name …)"
    (is (= "user" (:entity (core/op-search :user nil [:name])))))
  (testing "nil args stays nil (not coerced to {})"
    (is (nil? (:args (core/op-search :user nil [:name]))))))

(deftest op-get-shape
  (let [op (core/op-get :user {:xid "u-1"} [:name])]
    (is (= "get" (:op op)))
    (is (= "user" (:entity op)))))

(deftest write-op-shapes
  (testing "sync / stack / delete carry :data, not :selections"
    (doseq [[f verb] [[core/op-sync "sync"] [core/op-stack "stack"] [core/op-delete "delete"]]]
      (let [op (f :user {:xid "u-1" :name "A"})]
        (is (= verb (:op op)))
        (is (= "user" (:entity op)))
        (is (contains? op :data))
        (is (not (contains? op :selections)))))))

(deftest op-slice-and-purge-shape
  (is (= "slice" (:op (core/op-slice :user {:xid "u-1"} [:roles]))))
  (is (= "purge" (:op (core/op-purge :user {:active {:-eq false}} [:xid])))))

(deftest ops-never-inject-join
  ;; FLAT-LEFT DECREE: join semantics are the SERVER's (absent :_join =
  ;; LEFT, every op). The client injects nothing — a selection travels
  ;; exactly as the caller wrote it, for reads and destroys alike.
  (doseq [[label sel] [["slice" (:selections (core/op-slice :music-album {:xid "a"}
                                                            {:genres {:xid nil}}))]
                       ["purge" (:selections (core/op-purge :music-album {:active {:-eq false}}
                                                            {:genres {:xid nil}}))]
                       ["search" (:selections (core/op-search :music-album nil
                                                              {:genres {:xid nil}}))]]]
    (let [genres-cfg (first (:genres sel))]
      (is (= {:xid nil} (:selections genres-cfg)) label)
      (is (not (contains? (:args genres-cfg) :_join))
          (str label " selection must NOT carry an injected :_join")))))

(deftest op-sql-template-shape
  (testing "sql-template defaults :cached true"
    (let [op (core/op-sql-template "SELECT count(*) AS n FROM {user}" nil)]
      (is (= "sql-template" (:op op)))
      (is (= true (:cached op)))))
  (testing ":cached can be disabled"
    (is (= false (:cached (core/op-sql-template "SELECT 1" nil :cached false))))))

(deftest tree-op-shapes
  (let [st (core/op-search-tree :human :parent {} [:xid])]
    (is (= "search-tree" (:op st)))
    (is (= "parent" (:on st))))
  (let [gt (core/op-get-tree :human "r" :parent [:xid])]
    (is (= "get-tree" (:op gt)))
    (is (= "r" (:root gt)))
    (is (= "parent" (:on gt)))))

(deftest model-op-shapes
  (is (= {:op "deployed-model"} (core/op-deployed-model)))
  (is (= {:op "runtime-model"} (core/op-runtime-model))))

(deftest op-xsql-shape
  (testing "xsql read → :selections + :entity ride, :op preserved"
    (let [op (core/op-xsql {:op "search" :source "movie\n  title" :entity "movie"}
                           {:y 1990})]
      (is (= "search" (:op op)))
      (is (= "movie\n  title" (:selections op)))
      (is (= "movie" (:entity op)))
      (is (= {:y 1990} (:params op)))))
  (testing "xsql sql-template → template path, no :entity/:selections"
    (let [op (core/op-xsql {:op "sql-template" :source "SELECT 1"} nil)]
      (is (= "sql-template" (:op op)))
      (is (= "SELECT 1" (:template op)))
      (is (not (contains? op :selections)))))
  (testing "empty params omitted"
    (is (not (contains? (core/op-xsql {:op "search" :source "x" :entity "e"} nil)
                        :params)))))

;; ── result helpers: data-vs-error discrimination ──────────────────────────

(deftest results->data-extracts-and-wraps
  (testing "ok result → its data; failed result → ExceptionInfo carrying error"
    (let [results [{:ok true :data [{:xid "a"}]}
                   {:ok false :error {:message "nope" :code "UNKNOWN_ENTITY"}}]
          [good bad] (core/results->data results)]
      (is (= [{:xid "a"}] good))
      (is (core/ok? good))
      (is (not (core/ok? bad)))
      (is (= "UNKNOWN_ENTITY" (:code (ex-data bad))))
      (is (= "nope" (ex-message bad)))))
  (testing "with operations, the failing op is attached under :operation"
    (let [ops [(core/op-sync :user {}) (core/op-sync :user {:xid "b"})]
          results [{:ok false :error {:message "x" :code "FK_VIOLATION"}}
                   {:ok true :data {:xid "b"}}]
          [bad good] (core/results->data results ops)]
      (is (= (first ops) (:operation (ex-data bad))))
      (is (= {:xid "b"} good)))))

(deftest ok-all-ok-errors
  (let [good {:xid "a"}
        bad  (ex-info "boom" {:code "X"})]
    (is (true? (core/ok? good)))
    (is (false? (core/ok? bad)))
    (is (true? (core/all-ok? [good good])))
    (is (false? (core/all-ok? [good bad])))
    (is (= [bad] (core/errors [good bad good])))
    (is (= [] (core/errors [good good])))))
