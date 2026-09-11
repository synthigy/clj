(ns build
  (:require
    [clojure.tools.build.api :as b]
    [deps-deploy.deps-deploy :as dd]))

(def lib 'com.synthigy/sdk)
(def version "0.1.0")
(def class-dir "target/classes")
(def jar-file (format "target/%s-%s.jar" (name lib) version))

(defn clean [_]
  (b/delete {:path "target"}))

(defn jar
  "Source jar for Clojars — .clj/.cljc/.cljs travel as source, no AOT."
  [_]
  (b/write-pom
    {:class-dir class-dir
     :lib lib
     :version version
     :basis (b/create-basis {:project "deps.edn"})
     :src-dirs ["src"]
     :pom-data
     [[:description
       "Thin Clojure/ClojureScript client for the Synthigy /data endpoint."]
      [:url "https://github.com/synthigy/synthigy"]
      [:licenses
       [:license
        [:name "MIT License"]
        [:url "https://opensource.org/licenses/MIT"]]]
      [:developers
       [:developer [:name "Robert Geršak"]]]
      [:scm
       [:url "https://github.com/synthigy/clj"]
       [:connection "scm:git:https://github.com/synthigy/clj.git"]
       [:developerConnection "scm:git:ssh://git@github.com/synthigy/clj.git"]
       [:tag (str "v" version)]]]})
  (b/copy-dir {:src-dirs ["src"] :target-dir class-dir})
  (b/copy-file {:src "LICENSE" :target (str class-dir "/META-INF/LICENSE")})
  (b/copy-file {:src "README.md" :target (str class-dir "/META-INF/README.md")})
  (b/jar {:class-dir class-dir :jar-file jar-file}))

(defn install [_]
  (jar nil)
  (b/install {:basis (b/create-basis {:project "deps.edn"})
              :lib lib :version version
              :jar-file jar-file :class-dir class-dir}))

(defn deploy
  "Push to Clojars. Needs CLOJARS_USERNAME + CLOJARS_PASSWORD (a deploy token)."
  [_]
  (jar nil)
  (dd/deploy {:installer :remote
              :artifact jar-file
              :pom-file (b/pom-path {:lib lib :class-dir class-dir})}))
