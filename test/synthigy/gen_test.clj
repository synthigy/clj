(ns synthigy.gen-test
  "Codegen naming tests. Guards the kebab-case converter used to name
   generated namespaces/functions from op/entity names — it MUST split
   camelCase the same way the Go/JS/Python SDK kebab does, or a camelCase
   op name would generate a mangled Clojure function name (musicalbumlist
   instead of music-album-list)."
  (:require [clojure.test :refer [deftest is testing]]
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
