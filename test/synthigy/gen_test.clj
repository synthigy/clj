(ns synthigy.gen-test
  "Codegen naming tests. Guards the kebab-case converter used to name
   generated namespaces/functions from op/entity names — it MUST split
   camelCase the same way the Go/JS/Python SDK kebab does, or a camelCase
   op name would generate a mangled Clojure function name (musicalbumlist
   instead of music-album-list)."
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [synthigy.gen :as gen]))

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

(deftest warn-stale-schema-only-when-the-stamp-moved
  (let [server (com.sun.net.httpserver.HttpServer/create (java.net.InetSocketAddress. "127.0.0.1" 0) 0)
        body   (.getBytes "{\"datasets\":{\"A\":\"2026-09-15T09:47:15.698+02:00\",\"B\":\"2026-10-01T00:00:00Z\"}}")
        _      (.createContext server "/.well-known/synthigy"
                               (reify com.sun.net.httpserver.HttpHandler
                                 (handle [_ ex]
                                   (.sendResponseHeaders ex 200 (alength body))
                                   (with-open [o (.getResponseBody ex)] (.write o body)))))
        _      (.start server)
        endpoint (str "http://127.0.0.1:" (.getPort (.getAddress server)))
        dir    (str (java.nio.file.Files/createTempDirectory "synthigy-stale" (make-array java.nio.file.attribute.FileAttribute 0)))
        run    (fn [ep] (let [w (java.io.StringWriter.)]
                          (binding [*err* w] (gen/warn-stale-schema! {:dir dir :endpoint ep}))
                          (str w)))]
    (try
      (spit (str dir "/schema.json") "{\"datasets\":{\"A\":{\"deployed-at\":\"2026-09-15T07:47:15.698Z\"},\"B\":{\"deployed-at\":\"2026-10-01T00:00:00Z\"}}}")
      (is (= "" (run endpoint)))
      (spit (str dir "/schema.json") "{\"datasets\":{\"A\":{\"deployed-at\":\"2026-09-15T07:47:15.698Z\"}}}")
      (is (str/includes? (run endpoint) "stale"))
      (is (= "" (run "")))
      (finally (.stop server 0)))))

(deftest a-watch-variant-lists-every-watch-query-option
  (let [src (#'synthigy.gen/emit-fn {:name "listMovies" :op "search" :entity "Movie"
                                     :source "Movie {title}" :params [] :watch true})]
    (is (str/includes? src "(defn watch-list-movies"))
    (is (str/includes? src ":debounce-ms :on-event)") "the docstring names :on-event, which the variant forwards")))

(deftest a-describe-failure-names-the-position-hint-and-manual-section
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir") (str "gen-describe-" (System/nanoTime))) .mkdirs)]
    (spit (io/file dir "ops.xsql") "@search movies\nMovie { title }\n")
    (with-redefs [synthigy.gen/pull-client (constantly {})
                  synthigy.gen/describe! (fn [& _]
                                           (throw (ex-info "bad token" {:code "XSQL_PARSE_ERROR" :category "validation"
                                                                        :line 3 :col 5 :hint "close the brace"
                                                                        :doc "errors/xsql_parse"})))]
      (let [e (try (gen/load-ir {:dir (str dir) :endpoint "http://x:7887/"}) nil (catch Exception e e))]
        (is (= (str "describe failed: XSQL_PARSE_ERROR: bad token\n  at line 3, col 5\n  hint: close the brace\n"
                    "  docs: http://x:7887/docs/errors/xsql_parse")
               (ex-message e)))))))

(deftest claim-generates-and-release-is-skipped
  (let [{:keys [files skipped]} (gen/emit-files [{:name "grab" :op "claim" :entity "job" :source "@claim grab\njob (limit 1)\n  xid"}
                                                 {:name "free" :op "release" :entity "job" :source "@release free\n?ids:job[]"}]
                                                {})]
    (is (= ["free"] (map :name skipped)))
    (is (some #(str/includes? (:content %) "grab") (vals files)))))
