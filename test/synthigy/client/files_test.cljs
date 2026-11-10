(ns synthigy.client.files-test
  "synthigy.client.files against a stubbed fetch: request shapes, bytes, error mapping."
  (:require
   [cljs.test :refer [deftest is testing async use-fixtures]]
   [clojure.string :as str]
   [promesa.core :as p]
   [synthigy.client.core :as core]
   [synthigy.client.files :as files]))

(def calls (atom []))

(defn json-resp [status data]
  (js/Response. (js/JSON.stringify (clj->js data)) #js {:status status :headers #js {"Content-Type" "application/json" "X-Request-Id" "rq1"}}))

(defn bytes-resp [status body]
  (js/Response. body #js {:status status :headers #js {"ETag" "\"h1\"" "Content-Type" "application/octet-stream"}}))

(defn route [{:keys [method path query headers] :as call}]
  (let [h #(aget headers %)]
    (cond
      (and (= path "/files") (= method "POST"))
      (json-resp 201 {:xid "f1" :name "a.txt" :size 5 :content_type "text/plain"
                      :uploaded_by {:xid "u1" :name "jane"} :folder nil})
      (and (= path "/files") (= method "PUT")) (json-resp 201 {:xid "f5" :name "logo.png"})
      (and (= path "/files") (= method "GET") (str/includes? (str query) "path="))
      (bytes-resp 200 (js/Uint8Array. #js [0 1 255 254]))
      (= path "/files") (json-resp 200 {:files [{:xid "f1" :uploaded_at "2026-10-29T17:40:12Z"}]})
      (= path "/files/info") (json-resp 200 {:xid "f9" :name "r.txt"})
      (and (= path "/files/folders") (= method "PUT")) (json-resp 200 {:xid "d3" :path "/a b/"})
      (and (= path "/files/f1") (= method "PATCH")) (json-resp 200 {:xid "f1"})
      (and (= path "/files/zip") (= method "POST"))
      (if (str/includes? (str (:body call)) "/big/")
        (json-resp 413 {:error {:code "ZIP_TOO_LARGE" :message "too big"}})
        (bytes-resp 200 (js/Uint8Array. #js [80 75])))
      (and (= path "/files/f9/links") (= method "POST")) (json-resp 201 {:xid "l1" :file "f9" :max_downloads 2 :url "http://x/files/link/t"})
      (= path "/files/f9/links") (json-resp 200 {:file "f9" :links [{:xid "l1" :created_by "u1"}]})
      (= path "/files/f8/links") (json-resp 403 {:error {:code "LINKS_DISABLED" :message "off"}})
      (= path "/files/f7/links") (json-resp 400 {:error {:code "INVALID_EXPIRES" :message "bad"}})
      (= path "/files/links/l2") (json-resp 404 {:error {:code "LINK_NOT_FOUND" :message "gone"}})
      (= path "/files/search") (json-resp 200 {:results [{:xid "d1" :type "folder" :path "/Docs/"}] :truncated false})
      (and (= path "/files/f1") (= method "PUT")) (json-resp 412 {:error {:code "FILE_CHANGED" :message "changed"}})
      (and (#{"/files/f1" "/files/f1/versions/7"} path) (= method "GET"))
      (cond
        (= "\"h1\"" (h "If-None-Match")) (js/Response. nil #js {:status 304})
        (h "Range") (bytes-resp 206 (js/Uint8Array. #js [2 0 255]))
        :else (bytes-resp 200 (js/Uint8Array. #js [0 1 255 254])))
      (= path "/files/f4/info") (json-resp 200 {:xid "f4" :name "a.txt" :hash "0123456789abcdef0123" :public false})
      (= path "/files/f2") (json-resp 404 {:error {:code "FILE_NOT_FOUND" :message "No such file"}})
      (= path "/files/f2/info") (json-resp 404 {:error {:code "FILE_NOT_FOUND" :message "No such file"}})
      (= path "/files/f3/info") (json-resp 503 {:error {:code "AUDIT_UNAVAILABLE" :message "down"}})
      (#{"/files/f1/versions" "/files/f9/versions"} path) (json-resp 200 {:xid "f1" :versions [{:id 7 :by "u1" :current true :content_type "a/b"}]})
      (str/ends-with? path "/restore") (json-resp 200 {:xid "f1"})
      (and (= path "/files/folders") (= method "GET")) (json-resp 200 {:parent "root" :folders [{:xid "d1" :name "Docs" :path "/Docs/"}]})
      (str/ends-with? path "/share") (json-resp 200 {:read {:users [{:xid "u2" :name "bob"}] :groups []}})
      (= method "DELETE") (js/Response. nil #js {:status 204})
      (str/starts-with? path "/files/folders") (json-resp 200 {:xid "d2" :parent "d1"})
      :else (js/Response. "Not Found" #js {:status 404}))))

(defn fake-fetch [u opts]
  (let [parsed (js/URL. u)
        search (subs (.-search parsed) 1)
        call {:method (.-method opts) :path (.-pathname parsed)
              :query (not-empty (str/replace search #"&?key_format=[a-z]+" ""))
              :key-format (second (re-find #"key_format=([a-z]+)" search))
              :headers (.-headers opts) :body (.-body opts)}]
    (swap! calls conj call)
    (p/resolved (route call))))

(def saved (atom nil))

(use-fixtures :each
  {:before (fn []
             (reset! calls [])
             (reset! saved [js/globalThis.fetch core/*client*])
             (set! js/globalThis.fetch fake-fetch)
             (set! core/*client* (core/create-client {:endpoint "http://files.test" :token "tok"})))
   :after (fn []
            (let [[f c] @saved]
              (set! js/globalThis.fetch f)
              (set! core/*client* c)))})

(defn last-call [] (peek @calls))

(defn header [call k] (aget (:headers call) k))

(defn guard [pr done] (p/catch pr (fn [e] (is (nil? e) (str "unexpected: " (ex-message e) " " (pr-str (ex-data e)))) (done))))

(defn refused [pr]
  (p/catch (p/then pr (fn [v] {:resolved v})) ex-data))

(defn bytes-vec [u8] (vec (js/Array.from u8)))

(deftest put
  (async done
    (p/let [f (files/put {:name "a b.txt"} "hello")
            _ (is (= ["f1" 5 "jane" "text/plain"] [(:xid f) (:size f) (get-in f [:uploaded-by :name]) (:content-type f)]))
            _ (is (= ["POST" "name=a%20b.txt" "text/plain; charset=utf-8" "Bearer tok" "hello"]
                     [(:method (last-call)) (:query (last-call)) (header (last-call) "Content-Type")
                      (header (last-call) "Authorization") (:body (last-call))]))
            _ (files/put "/site/img/logo.png" "x")
            _ (is (= ["PUT" "/files" "path=%2Fsite%2Fimg%2Flogo.png"] ((juxt :method :path :query) (last-call))))
            bin (js/Uint8Array. #js [0 255])
            _ (files/put {:name "b.bin"} bin)
            _ (is (identical? bin (:body (last-call))) "bytes go to fetch as they are")
            _ (is (= "application/octet-stream" (header (last-call) "Content-Type")))
            _ (files/put {:name "c.bin"} (.-buffer bin) :content-type "application/x-thing")
            _ (is (= "application/x-thing" (header (last-call) "Content-Type")))
            _ (files/put {:name "d.json"} (js/Blob. #js ["{}"] #js {:type "application/json"}))
            _ (is (= "application/json" (header (last-call) "Content-Type")) "a Blob's own type")
            e1 (refused (files/put {} "x"))
            e2 (refused (files/put {:name "x"} "a=b" :content-type "application/x-www-form-urlencoded"))
            e3 (refused (files/put {:name "x"} {:not "bytes"}))]
      (is (= ["INVALID_PATH" "UNSUPPORTED_MEDIA_TYPE" "INVALID_BODY"] (mapv :code [e1 e2 e3])))
      (is (= 5 (count (filter #(str/starts-with? (:path %) "/files") @calls))) "refused bodies never reach the server")
      (done))))

(deftest replace-and-errors
  (async done
    (guard (p/let [e (refused (files/put "f1" "v2" :if-match {:hash "abc"}))
            _ (is (= ["PUT" "/files/f1"] ((juxt :method :path) (last-call))))
            _ (is (= ["FILE_CHANGED" "conflict" 412 "rq1"] ((juxt :code :category :status :request-id) e)))
            _ (is (= "\"abc\"" (header (last-call) "If-Match")))
            missing (files/info "f2")
            _ (is (nil? missing))
            e3 (refused (files/info "f3"))
            _ (is (= ["AUDIT_UNAVAILABLE" "not_found" false] ((juxt :code :category :retryable) e3)))
            e4 (refused (files/download "f2"))
            _ (is (= "FILE_NOT_FOUND" (:code e4)) "a JSON refusal on a byte download keeps its code")
            e5 (refused (files/versions "zz"))]
      (is (= "FILES_UNAVAILABLE" (:code e5)))
      (done)) done)))

(deftest zip
  (async done
    (guard (p/let [out (files/zip :files ["f1" {:xid "f9"}] :folders ["/site/img/" {:xid "d1" :path "/b/"}])
                   _ (is (= [80 75] (bytes-vec out)))
                   call (last-call)
                   _ (is (= ["POST" "/files/zip"] ((juxt :method :path) call)))
                   _ (is (= {:files ["f1" "f9"] :folders ["/site/img/" "/b/"]} (js->clj (js/JSON.parse (:body call)) :keywordize-keys true)))
                   e (refused (files/zip :folders ["/big/"]))]
             (is (= ["ZIP_TOO_LARGE" "validation"] ((juxt :code :category) e)))
             (done)) done)))

(deftest download
  (async done
    (p/let [all (files/download "f1")
            _ (is (instance? js/Uint8Array all))
            _ (is (= [0 1 255 254] (bytes-vec all)) "binary arrives byte-exact")
            part (files/download "f1" :range [2 4])
            _ (is (= [2 0 255] (bytes-vec part)))
            _ (is (= "bytes=2-4" (header (last-call) "Range")))
            _ (files/download "f1" :range [nil 5])
            _ (is (= "bytes=-5" (header (last-call) "Range")))
            unchanged (files/download "f1" :if-none-match "h1")
            _ (is (nil? unchanged))
            blob (files/download "f1" :version {:id 7} :as :blob)
            _ (is (instance? js/Blob blob))
            _ (is (= "/files/f1/versions/7" (:path (last-call))))
            buf (.arrayBuffer blob)
            _ (is (= [0 1 255 254] (bytes-vec (js/Uint8Array. buf))))
            ab (files/download "f1" :as :array-buffer)
            _ (is (= [0 1 255 254] (bytes-vec (js/Uint8Array. ab))))
            r (files/download "f1" :as :response)]
      (is (= 200 (.-status r)))
      (done))))

(deftest list-search-versions
  (async done
    (p/let [{:keys [files folders]} (files/list "d1" :q "doc" :limit 5 :offset 10)
            _ (testing "a folder: files and subfolders"
                (is (= "2026-10-29T17:40:12Z" (:uploaded-at (first files))))
                (is (= ["/Docs/"] (mapv :path folders)) "folders filtered by the words of q")
                (is (= #{["/files" "q=doc&limit=5&offset=10&folder=d1"] ["/files/folders" "parent=d1"]}
                       (set (map (juxt :path :query) (take-last 2 @calls))))))
            {:keys [folders]} (files/list "d1" :q "zzz")
            _ (is (empty? folders))
            _ (files/list nil :q "inv")
            _ (is (= "q=inv&folderless=true" (:query (last-call))))
            all (files/list)
            _ (is (= [] (:folders all)))
            _ (is (nil? (:query (last-call))))
            hits (files/search "site logo" :folder "/d1/" :limit 5)
            _ (testing "search"
                (is (= ["/files/search" "q=site%20logo&folder=%2Fd1%2F&limit=5"] ((juxt :path :query) (last-call))))
                (is (= [["folder" "/Docs/"] false] [((juxt :type :path) (first (:results hits))) (:truncated hits)])))
            [v] (files/versions "f1")
            _ (is (= [7 true "a/b"] ((juxt :id :current :content-type) v)))
            _ (files/restore "f1" v)
            _ (is (= ["POST" "/files/f1/versions/7/restore"] ((juxt :method :path) (last-call))))
            deleted (files/delete "f1")]
      (is (nil? deleted))
      (is (= ["DELETE" "/files/f1"] ((juxt :method :path) (last-call))))
      (done))))

(deftest folders
  (async done
    (p/let [f (files/folder "/a b/")
            _ (is (= "d3" (:xid f)))
            _ (is (= ["PUT" "/files/folders" "path=%2Fa%20b%2F" "{}"] ((juxt :method :path :query :body) (last-call))))
            _ (files/folder "/a b/" {:public true :cache nil})
            _ (is (= "{\"public\":true,\"cache\":null}" (:body (last-call))) "nil clears a setting")
            _ (files/access "/a b/")
            _ (is (= ["/files/folders/access" "path=%2Fa%20b%2F"] ((juxt :path :query) (last-call))))
            shared (files/share "d2" :read {:users [{:xid "u2"}]})
            _ (is (nil? shared))
            _ (is (= {:access "read" :users ["u2"] :groups []} (js->clj (js/JSON.parse (:body (last-call))) :keywordize-keys true)))
            _ (files/unshare {:xid "d2"} "write" {:groups ["g1"]})
            _ (is (= "/files/folders/d2/unshare" (:path (last-call))))
            _ (files/delete "/a b/")
            _ (is (= ["DELETE" "/files/folders" "path=%2Fa%20b%2F"] ((juxt :method :path :query) (last-call))))
            _ (files/delete {:xid "d2" :path "/x/"})]
      (is (= ["/files/folders" "path=%2Fx%2F"] ((juxt :path :query) (last-call))))
      (done))))

(deftest move
  (async done
    (p/let [_ (files/move "f1" "/site/brand/")
            _ (is (= ["PATCH" "/files/f1" "{\"folder\":\"/site/brand/\"}"] ((juxt :method :path :body) (last-call))))
            _ (files/move "f1" "/site/brand/new.png")
            _ (is (= {:folder "/site/brand/" :name "new.png"} (js->clj (js/JSON.parse (:body (last-call))) :keywordize-keys true)))
            _ (files/move "/a/r.txt" "/b/")
            _ (is (= ["/files" "path=%2Fa%2Fr.txt"] ((juxt :path :query) (last-call))))
            _ (files/move "/site/img/" "/site/media/")
            _ (is (= ["/files/folders" "path=%2Fsite%2Fimg%2F" {:name "media" :parent "/site/"}]
                     [(:path (last-call)) (:query (last-call)) (js->clj (js/JSON.parse (:body (last-call))) :keywordize-keys true)]))
            _ (files/move {:xid "d2" :path "/x/"} "/top/")
            _ (is (= {:name "top" :parent "/"} (js->clj (js/JSON.parse (:body (last-call))) :keywordize-keys true)))
            e (refused (files/move "f1" "nowhere"))]
      (is (= "INVALID_PATH" (:code e)))
      (done))))

(deftest files-by-path
  (async done
    (guard (p/let [b (files/download "/a b/r.txt")
            _ (is (= [0 1 255 254] (bytes-vec b)))
            _ (is (= ["/files" "path=%2Fa%20b%2Fr.txt"] ((juxt :path :query) (last-call))) "one request, the server resolves the path")
            i (files/info "/a b/r.txt")
            _ (is (= "f9" (:xid i)))
            _ (is (= ["/files/info" "path=%2Fa%20b%2Fr.txt"] ((juxt :path :query) (last-call))))
            _ (files/versions "/a b/r.txt")]
      (is (= ["/files/info" "/files/f9/versions"] (mapv :path (take-last 2 @calls))) "versions resolve a path to its xid")
      (done)) done)))

(deftest links
  (async done
    (p/let [_ (is (= "http://files.test/files/path/a%20b/c%281%29%2A.txt" (files/url "/a b/c(1)*.txt")))
            _ (is (= "http://files.test/files/f1?disposition=inline" (files/url {:xid "f1"} :inline true)))
            _ (is (empty? @calls) "no request")
            e (refused (p/do (files/url "/a/")))
            r (files/serve "/a/r.txt" {:method "GET" :headers {"range" "bytes=0-1"}} :inline true)]
      (is (= "INVALID_PATH" (:code e)))
      (is (= ["/files" "path=%2Fa%2Fr.txt&disposition=inline" "bytes=0-1"]
             [(:path (last-call)) (:query (last-call)) (header (last-call) "range")]))
      (is (= [200 "\"h1\""] [(.-status r) (.. r -headers (get "etag"))]))
      (done))))



(defn body-of [call] (js->clj (js/JSON.parse (:body call)) :keywordize-keys true))

(deftest download-links
  (async done
    (guard (p/let [l (files/link "/a b/r.txt" :expires 600 :max-downloads 2 :inline true)
                   _ (is (= "http://x/files/link/t" (:url l)))
                   _ (is (= [["GET" "/files/info" "path=%2Fa%20b%2Fr.txt"] ["POST" "/files/f9/links" nil]]
                            (mapv (juxt :method :path :query) (take-last 2 @calls))))
                   _ (is (= {:expires 600 :max_downloads 2 :inline true} (body-of (last-call))))
                   _ (files/link "f9" :inline false)
                   _ (is (= {:inline false} (body-of (last-call))) "only what is given")
                   ls (files/links {:xid "f9"})
                   _ (is (= [["l1" "u1"]] (mapv (juxt :xid :created-by) ls)))
                   u1 (files/unlink {:xid "l1"})
                   u2 (files/unlink "l1")
                   _ (is (= [nil nil] [u1 u2]))
                   _ (is (= [["DELETE" "/files/links/l1"] ["DELETE" "/files/links/l1"]] (mapv (juxt :method :path) (take-last 2 @calls))))
                   e1 (refused (files/link "f8"))
                   e2 (refused (files/link "f7" :expires 0))
                   e3 (refused (files/unlink "l2"))
                   _ (is (= [["LINKS_DISABLED" "iam"] ["INVALID_EXPIRES" "validation"] ["LINK_NOT_FOUND" "not_found"]]
                            (mapv (juxt :code :category) [e1 e2 e3])))
                   _ (files/folder "/s/" {:can-link true :cache 60})
                   _ (is (= {:can_link true :cache 60} (body-of (last-call))))
                   _ (files/folder "/s/" {:can_link nil})]
             (is (= "{\"can_link\":null}" (:body (last-call))) "nil inherits")
             (done)) done)))

(deftest key-format-reaches-files
  (async done
    (guard (p/let [_ (files/info "f4")
                   _ (is (= ["kebab" nil] ((juxt :key-format :query) (last-call))))
                   _ (files/list nil :q "inv")
                   _ (is (= ["kebab" "q=inv&folderless=true"] ((juxt :key-format :query) (last-call))))
                   _ (set! core/*client* (assoc core/*client* :key-format "camel"))
                   _ (files/links "f9")
                   _ (is (= "camel" (:key-format (last-call))))
                   _ (set! core/*client* (assoc core/*client* :key-format "snake"))
                   _ (files/info "f4")
                   _ (is (nil? (:key-format (last-call))))
                   _ (set! core/*client* (assoc core/*client* :key-format nil))
                   _ (files/info "f4")
                   _ (is (nil? (:key-format (last-call))))
                   _ (files/serve "f4" {:method "GET" :headers {}})]
             (is (nil? (:key-format (last-call))) "serve passes bytes, no key format")
             (done)) done)))
