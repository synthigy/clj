(ns synthigy.client.files-test
  "synthigy.client.files against a JDK HttpServer: request shapes, bytes, error mapping."
  (:require
   [babashka.json :as json]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]]
   [synthigy.client.core :as core]
   [synthigy.client.files :as files])
  (:import
   [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
   [java.net InetSocketAddress]))

(def calls (atom []))

(defn reply [^HttpExchange ex status ^bytes body headers]
  (doseq [[k v] headers] (.add (.getResponseHeaders ex) k v))
  (.sendResponseHeaders ex status (if (zero? (alength body)) -1 (alength body)))
  (with-open [o (.getResponseBody ex)] (.write o body)))

(defn reply-json [ex status data]
  (reply ex status (.getBytes ^String (json/write-str data) "UTF-8") {"Content-Type" "application/json"}))

(defn handle [^HttpExchange ex]
  (let [method (.getRequestMethod ex)
        uri (.getRequestURI ex)
        path (.getPath uri)
        h (.getRequestHeaders ex)
        body (.readAllBytes (.getRequestBody ex))]
    (swap! calls conj {:method method :path path :body body
                       :query (not-empty (str/replace (str (.getRawQuery uri)) #"&?key_format=[a-z]+" ""))
                       :key-format (second (re-find #"key_format=([a-z]+)" (str (.getRawQuery uri))))
                       :type (.getFirst h "Content-Type") :if-match (.getFirst h "If-Match")
                       :range (.getFirst h "Range") :auth (.getFirst h "Authorization")})
    (cond
      (and (= path "/files") (= method "POST"))
      (reply-json ex 201 {:xid "f1" :name "a.txt" :size (alength body) :content_type "text/plain"
                          :uploaded_by {:xid "u1" :name "jane"} :folder nil})
      (and (= path "/files") (= method "PUT")) (reply-json ex 201 {:xid "f5" :name "logo.png"})
      (and (= path "/files") (= method "GET") (str/includes? (str (.getRawQuery uri)) "path="))
      (reply ex 200 (.getBytes "0123456789") {"ETag" "\"h1\""})
      (= path "/files") (reply-json ex 200 {:files [{:xid "f1" :uploaded_at "2026-10-29T17:40:12Z"}]})
      (str/starts-with? path "/files/slow")
      (do (Thread/sleep 600)
          (if (str/ends-with? path "/info")
            (reply-json ex 200 {:xid "slow"})
            (reply ex 200 (.getBytes "late bytes") {})))
      (and (= path "/files/zip") (= method "POST"))
      (if (str/includes? (String. ^bytes body "UTF-8") "/big/")
        (reply-json ex 413 {:error {:code "ZIP_TOO_LARGE" :message "too big"}})
        (reply ex 200 (.getBytes "PK") {"Content-Type" "application/zip"}))
      (and (= path "/files/f9/links") (= method "POST")) (reply-json ex 201 {:xid "l1" :file "f9" :max_downloads 2 :url "http://x/files/link/t"})
      (= path "/files/f9/links") (reply-json ex 200 {:file "f9" :links [{:xid "l1" :created_by "u1"}]})
      (= path "/files/f8/links") (reply-json ex 403 {:error {:code "LINKS_DISABLED" :message "off"}})
      (= path "/files/f7/links") (reply-json ex 400 {:error {:code "INVALID_EXPIRES" :message "bad"}})
      (= path "/files/links/l2") (reply-json ex 404 {:error {:code "LINK_NOT_FOUND" :message "gone"}})
      (= path "/files/search") (reply-json ex 200 {:results [{:xid "d1" :type "folder" :path "/Docs/"}] :truncated false})
      (= path "/files/info") (reply-json ex 200 {:xid "f9" :name "r.txt"})
      (and (= path "/files/folders") (= method "PUT")) (reply-json ex 200 {:xid "d3" :path "/a b/"})
      (and (= path "/files/f1") (= method "PATCH")) (reply-json ex 200 {:xid "f1"})
      (and (= path "/files/f1") (= method "PUT")) (reply-json ex 412 {:error {:code "FILE_CHANGED" :message "changed"}})
      (and (#{"/files/f1" "/files/f1/versions/7"} path) (= method "GET"))
      (cond
        (= "\"h1\"" (.getFirst h "If-None-Match")) (reply ex 304 (byte-array 0) {"ETag" "\"h1\""})
        (.getFirst h "Range") (reply ex 206 (.getBytes "234") {"ETag" "\"h1\""})
        :else (reply ex 200 (.getBytes "0123456789") {"ETag" "\"h1\""}))
      (= path "/files/f4/info") (reply-json ex 200 {:xid "f4" :name "a.txt" :hash "0123456789abcdef0123" :public false})
      (= path "/files/f2") (reply-json ex 404 {:error {:code "FILE_NOT_FOUND" :message "No such file"}})
      (= path "/files/f2/info") (reply-json ex 404 {:error {:code "FILE_NOT_FOUND" :message "No such file"}})
      (= path "/files/f3/info") (reply-json ex 503 {:error {:code "AUDIT_UNAVAILABLE" :message "down"}})
      (#{"/files/f1/versions" "/files/f9/versions"} path) (reply-json ex 200 {:xid "f1" :versions [{:id 7 :by "u1" :current true :content_type "a/b"}]})
      (str/ends-with? path "/restore") (reply-json ex 200 {:xid "f1"})
      (and (= path "/files/folders") (= method "GET")) (reply-json ex 200 {:parent "root" :folders [{:xid "d1" :name "Docs" :path "/Docs/"}]})
      (str/ends-with? path "/share") (reply-json ex 200 {:read {:users [{:xid "u2" :name "bob"}] :groups []}})
      (= method "DELETE") (reply ex 204 (byte-array 0) {})
      (str/starts-with? path "/files/folders") (reply-json ex 200 {:xid "d2" :parent "d1"})
      :else (reply ex 404 (.getBytes "Not Found") {}))))

(defn with-server [f]
  (let [srv (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext srv "/" (reify HttpHandler (handle [_ ex] (try (handle ex) (catch Throwable t (.printStackTrace t))))))
    (.start srv)
    (try
      (binding [core/*client* {:endpoint (str "http://127.0.0.1:" (.getPort (.getAddress srv)))
                               :key-format "kebab"
                               :token-fn (fn ([] "tok") ([_] "tok"))}]
        (reset! calls [])
        (f))
      (finally (.stop srv 0)))))

(use-fixtures :each with-server)

(defn last-call [] (peek @calls))

(defn sent [] (json/read-str (String. ^bytes (:body (last-call))) {:key-fn keyword}))

(defn code-of [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(deftest put
  (let [f (files/put {:name "a b.txt"} "hello")]
    (is (= ["f1" 5 "jane" "text/plain"] [(:xid f) (:size f) (get-in f [:uploaded-by :name]) (:content-type f)]))
    (is (= ["POST" "name=a%20b.txt" "text/plain; charset=utf-8" "Bearer tok"]
           ((juxt :method :query :type :auth) (last-call)))))
  (is (= "f5" (:xid (files/put "/site/img/logo.png" "x"))))
  (is (= ["PUT" "/files" "path=%2Fsite%2Fimg%2Flogo.png"] ((juxt :method :path :query) (last-call))))
  (let [tmp (doto (java.io.File/createTempFile "report" ".pdf") (.deleteOnExit))]
    (spit tmp "pdf bytes")
    (files/put "/r/report.pdf" tmp)
    (is (= ["application/octet-stream" "pdf bytes"] [(:type (last-call)) (String. ^bytes (:body (last-call)))])))
  (files/put {:name "s.bin"} (io/input-stream (.getBytes "streamed")) :content-type "application/x-thing")
  (is (= ["streamed" "application/x-thing"] [(String. ^bytes (:body (last-call))) (:type (last-call))]))
  (is (= "INVALID_PATH" (:code (code-of #(files/put {} "x")))))
  (is (= "UNSUPPORTED_MEDIA_TYPE" (:code (code-of #(files/put {:name "x"} "a=b" :content-type "application/x-www-form-urlencoded"))))))

(deftest replace-and-errors
  (let [e (code-of #(files/put "f1" "v2" :if-match {:hash "abc"}))]
    (is (= ["FILE_CHANGED" "conflict" 412] ((juxt :code :category :status) e)))
    (is (= ["PUT" "/files/f1" "\"abc\""] ((juxt :method :path :if-match) (last-call)))))
  (is (nil? (files/info "f2")))
  (is (= ["AUDIT_UNAVAILABLE" "not_found" false] ((juxt :code :category :retryable) (code-of #(files/info "f3")))))
  (is (= "FILE_NOT_FOUND" (:code (code-of #(files/download "f2")))) "a JSON refusal on a byte download keeps its code")
  (is (= "FILES_UNAVAILABLE" (:code (code-of #(files/versions "zz"))))))

(deftest download
  (is (= "0123456789" (String. ^bytes (files/download "f1"))))
  (is (= "234" (String. ^bytes (files/download "f1" :range [2 4]))))
  (is (= "bytes=2-4" (:range (last-call))))
  (files/download "f1" :range [nil 5])
  (is (= "bytes=-5" (:range (last-call))))
  (is (nil? (files/download "f1" :if-none-match "h1")))
  (is (= "0123456789" (files/download "f1" :as :text)))
  (with-open [in (files/download "f1" :version {:id 7} :as :stream)]
    (is (= "0123456789" (slurp in))))
  (is (= "/files/f1/versions/7" (:path (last-call)))))

(deftest timeout-spares-file-bytes
  (binding [core/*client* (assoc core/*client* :request-timeout 200)]
    (is (= "late bytes" (String. ^bytes (files/download "slow"))) "a download outlives the request timeout")
    (is (thrown? Exception (files/info "slow")) "a JSON call still honours it")))

(deftest zip
  (is (= "PK" (String. ^bytes (files/zip :files ["f1" "/a/r.txt" {:xid "f9"}] :folders ["/site/img/" {:xid "d1" :path "/b/"}]))))
  (let [{:keys [method path type body]} (last-call)]
    (is (= ["POST" "/files/zip" "application/json"] [method path type]))
    (is (= {:files ["f1" "/a/r.txt" "f9"] :folders ["/site/img/" "/b/"]} (json/read-str (String. ^bytes body "UTF-8") {:key-fn keyword}))))
  (with-open [in (files/zip :folders ["/x/"] :as :stream)]
    (is (= "PK" (slurp in))))
  (is (= "ZIP_TOO_LARGE" (:code (ex-data (try (files/zip :folders ["/big/"]) (catch clojure.lang.ExceptionInfo e e)))))))

(deftest list-search-versions
  (testing "a folder: files and subfolders"
    (let [{:keys [files folders]} (files/list "d1" :q "doc" :limit 5 :offset 10)]
      (is (= "2026-10-29T17:40:12Z" (:uploaded-at (first files))))
      (is (= ["/Docs/"] (mapv :path folders)) "folders filtered by the words of q")
      (is (= #{["/files" "q=doc&limit=5&offset=10&folder=d1"] ["/files/folders" "parent=d1"]}
             (set (map (juxt :path :query) (take-last 2 @calls))))))
    (is (empty? (:folders (files/list "d1" :q "zzz")))))
  (files/list nil :q "inv")
  (is (= "q=inv&folderless=true" (:query (last-call))))
  (is (= [] (:folders (files/list))))
  (is (nil? (:query (last-call))))
  (let [hits (files/search "site logo" :folder "/d1/" :limit 5)]
    (is (= ["/files/search" "q=site%20logo&folder=%2Fd1%2F&limit=5"] ((juxt :path :query) (last-call))))
    (is (= [["folder" "/Docs/"] false] [((juxt :type :path) (first (:results hits))) (:truncated hits)])))
  (let [[v] (files/versions "f1")]
    (is (= [7 true "a/b"] ((juxt :id :current :content-type) v)))
    (files/restore "f1" v)
    (is (= ["POST" "/files/f1/versions/7/restore"] ((juxt :method :path) (last-call)))))
  (is (nil? (files/delete "f1")))
  (is (= ["DELETE" "/files/f1"] ((juxt :method :path) (last-call)))))

(deftest folders
  (is (= "d3" (:xid (files/folder "/a b/"))))
  (is (= ["PUT" "/files/folders" "path=%2Fa%20b%2F" {}] [(:method (last-call)) (:path (last-call)) (:query (last-call)) (sent)]))
  (files/folder "/a b/" {:public true :cache nil})
  (is (= "{\"public\":true,\"cache\":null}" (String. ^bytes (:body (last-call)))) "nil clears a setting")
  (files/access "/a b/")
  (is (= ["/files/folders/access" "path=%2Fa%20b%2F"] ((juxt :path :query) (last-call))))
  (is (nil? (files/share "d2" :read {:users [{:xid "u2"}]})))
  (is (= {:access "read" :users ["u2"] :groups []} (sent)))
  (files/unshare {:xid "d2"} "write" {:groups ["g1"]})
  (is (= "/files/folders/d2/unshare" (:path (last-call))))
  (files/delete "/a b/")
  (is (= ["DELETE" "/files/folders" "path=%2Fa%20b%2F"] ((juxt :method :path :query) (last-call))))
  (files/delete {:xid "d2" :path "/x/"})
  (is (= ["/files/folders" "path=%2Fx%2F"] ((juxt :path :query) (last-call)))))

(deftest move
  (files/move "f1" "/site/brand/")
  (is (= ["PATCH" "/files/f1" {:folder "/site/brand/"}] [(:method (last-call)) (:path (last-call)) (sent)]))
  (files/move "f1" "/site/brand/new.png")
  (is (= {:folder "/site/brand/" :name "new.png"} (sent)))
  (files/move "/a/r.txt" "/b/")
  (is (= ["/files" "path=%2Fa%2Fr.txt"] ((juxt :path :query) (last-call))))
  (files/move "/site/img/" "/site/media/")
  (is (= ["/files/folders" "path=%2Fsite%2Fimg%2F" {:name "media" :parent "/site/"}] [(:path (last-call)) (:query (last-call)) (sent)]))
  (files/move {:xid "d2" :path "/x/"} "/top/")
  (is (= {:name "top" :parent "/"} (sent)))
  (is (= "INVALID_PATH" (:code (code-of #(files/move "f1" "nowhere"))))))

(deftest files-by-path
  (is (= "0123456789" (String. ^bytes (files/download "/a b/r.txt"))))
  (is (= ["/files" "path=%2Fa%20b%2Fr.txt"] ((juxt :path :query) (last-call))) "one request, the server resolves the path")
  (is (= "f9" (:xid (files/info "/a b/r.txt"))))
  (is (= ["/files/info" "path=%2Fa%20b%2Fr.txt"] ((juxt :path :query) (last-call))))
  (files/versions "/a b/r.txt")
  (is (= ["/files/info" "/files/f9/versions"] (mapv :path (take-last 2 @calls))) "versions resolve a path to its xid"))

(deftest links
  (let [base (:endpoint core/*client*)
        n (count @calls)]
    (is (= (str base "/files/path/a%20b/c%281%29%2A.txt") (files/url "/a b/c(1)*.txt")))
    (is (= (str base "/files/f1?disposition=inline") (files/url {:xid "f1"} :inline true)))
    (is (= n (count @calls)) "no request"))
  (is (= "INVALID_PATH" (:code (code-of #(files/url "/a/")))))
  (let [r (files/serve "/a/r.txt" {:request-method :get :headers {"range" "bytes=0-1"}} :inline true)]
    (is (= ["/files" "path=%2Fa%2Fr.txt&disposition=inline" "bytes=0-1"] ((juxt :path :query :range) (last-call))))
    (is (= [200 "\"h1\"" "0123456789"] [(:status r) (get-in r [:headers "etag"]) (slurp (:body r))]))))

(deftest download-links
  (is (= "http://x/files/link/t" (:url (files/link "/a/r.txt" :expires 600 :max-downloads 2 :inline true))))
  (is (= [["GET" "/files/info" "path=%2Fa%2Fr.txt"] ["POST" "/files/f9/links" nil]]
         (mapv (juxt :method :path :query) (take-last 2 @calls))))
  (is (= {:expires 600 :max_downloads 2 :inline true} (sent)))
  (files/link "f9" :inline false)
  (is (= {:inline false} (sent)) "only what is given")
  (is (= [["l1" "u1"]] (mapv (juxt :xid :created-by) (files/links {:xid "f9"}))))
  (is (= ["GET" "/files/f9/links"] ((juxt :method :path) (last-call))))
  (is (nil? (files/unlink {:xid "l1"})))
  (is (nil? (files/unlink "l1")))
  (is (= [["DELETE" "/files/links/l1"] ["DELETE" "/files/links/l1"]] (mapv (juxt :method :path) (take-last 2 @calls))))
  (is (= ["LINKS_DISABLED" "iam"] ((juxt :code :category) (code-of #(files/link "f8")))))
  (is (= ["INVALID_EXPIRES" "validation"] ((juxt :code :category) (code-of #(files/link "f7" :expires 0)))))
  (is (= ["LINK_NOT_FOUND" "not_found"] ((juxt :code :category) (code-of #(files/unlink "l2")))))
  (files/folder "/s/" {:can-link true :cache 60})
  (is (= {:can_link true :cache 60} (sent)))
  (files/folder "/s/" {:can_link nil})
  (is (= "{\"can_link\":null}" (String. ^bytes (:body (last-call)))) "nil inherits"))

(deftest key-format-reaches-files
  (files/info "f4")
  (is (= ["kebab" nil] ((juxt :key-format :query) (last-call))))
  (files/list nil :q "inv")
  (is (= ["kebab" "q=inv&folderless=true"] ((juxt :key-format :query) (last-call))))
  (binding [core/*client* (assoc core/*client* :key-format "camel")]
    (files/links "f9")
    (is (= "camel" (:key-format (last-call)))))
  (doseq [kf ["snake" nil]]
    (binding [core/*client* (assoc core/*client* :key-format kf)]
      (files/info "f4")
      (is (nil? (:key-format (last-call))) (str "none for " kf))))
  (files/url "f4")
  (files/serve "f4" {:request-method :get :headers {}})
  (is (nil? (:key-format (last-call))) "serve passes bytes, no key format"))
