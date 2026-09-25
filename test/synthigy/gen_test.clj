(ns synthigy.gen-test
  "Codegen naming tests. Guards the kebab-case converter used to name
   generated namespaces/functions from op/entity names — it MUST split
   camelCase the same way the Go/JS/Python SDK kebab does, or a camelCase
   op name would generate a mangled Clojure function name (musicalbumlist
   instead of music-album-list)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [synthigy.gen]))

(def ^:private kebab @#'synthigy.gen/kebab)

(deftest kebab-splits-camelcase-matching-other-sdks
  (testing "camelCase / PascalCase split on the lower/digit→upper boundary"
    (is (= "music-album" (kebab "MusicAlbum")))
    (is (= "music-album" (kebab "musicAlbum")))
    (is (= "music-album-list" (kebab "musicAlbumList")))
    (is (= "user2-role" (kebab "user2Role")))
    (is (= "oauth2-client" (kebab "OAuth2Client"))))
  (testing "snake_case and spaces collapse to single dashes"
    (is (= "music-album" (kebab "music_album")))
    (is (= "music-album" (kebab "Music Album")))
    (is (= "a-b-c" (kebab "a__b   c"))))
  (testing "consecutive capitals (no lower/digit boundary) stay together"
    (is (= "httpserver" (kebab "HTTPServer")))
    (is (= "apikey" (kebab "APIKey"))))
  (testing "already-kebab is a fixpoint"
    (is (= "already-kebab" (kebab "already-kebab")))))

(defn- tmp-dir []
  (doto (java.io.File/createTempFile "synthigy-gen" "")
    (.delete)
    (.mkdirs)))

(deftest source-hash-matches-the-other-sdks
  (is (= "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
         (synthigy.gen/source-hash "abc"))))

(deftest load-ir-is-offline-while-the-saved-ir-matches
  (let [dir (tmp-dir)
        src "@search list\nmovie\n  xid"]
    (spit (io/file dir "movies.xsql") src)
    (spit (io/file dir "ops.ir.json")
          (json/generate-string {:sourceHash (synthigy.gen/source-hash src)
                                 :operations [{:name "list" :op "search" :entity "movie"}]}))
    (is (= {:operations [{:name "list" :op "search" :entity "movie"}] :pulled? false}
           (synthigy.gen/load-ir {:dir (str dir)})))))

(deftest load-ir-never-returns-a-stale-ir
  (let [dir (tmp-dir)]
    (spit (io/file dir "movies.xsql") "edited")
    (spit (io/file dir "ops.ir.json")
          (json/generate-string {:sourceHash "deadbeef" :operations []}))
    (let [e (try (synthigy.gen/load-ir {:dir (str dir) :endpoint "http://127.0.0.1:9"})
                 nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (re-find #"changed since the last pull" (ex-message e))))))
