(ns synthigy.client.files
  "Files: the bytes behind /files, each also a File record on /data.

    (require '[synthigy.client.files :as files])

    (files/folder \"/site/img/\")                         ; the folder, and any missing above it
    (def f (files/put \"/site/img/logo.png\" (io/file \"logo.png\")))  ; again: a new version
    (files/download \"/site/img/logo.png\")               ; bytes; :as :stream :text
    (files/move f \"/site/brand/\")

  A file is a path (/a/b/name), an xid or the file itself; a folder is a path
  ending in /, an xid or the folder itself. Response keys follow the client's
  :key-format: kebab-case by default, wire snake_case otherwise."
  (:refer-clojure :exclude [list])
  (:require
   [babashka.json :as json]
   [clojure.string :as str]
   [synthigy.client.core :as core]
   [synthigy.client.error :as err]
   [synthigy.client.http :as http])
  (:import
   [java.io File InputStream]
   [java.net URLEncoder]
   [java.nio.charset StandardCharsets]))

(defn enc [x]
  (str/replace (URLEncoder/encode (str x) StandardCharsets/UTF_8) "+" "%20"))

(defn enc-part
  "One path segment percent-encoded but for RFC 3986 unreserved characters, as the server builds links."
  [s]
  (-> (enc s) (str/replace "*" "%2A") (str/replace "%7E" "~")))

(defn xid-of [x] (if (map? x) (:xid x) x))

(defn path? [x] (and (string? x) (str/starts-with? x "/")))

(defn folder-ref [x] (if (map? x) (or (:path x) (:xid x)) x))

(defn folder? [x]
  (let [r (if (map? x) (:path x) x)]
    (and (path? r) (str/ends-with? r "/"))))

(defn endpoint-url [path query]
  (let [q (keep (fn [[k v]] (when (and (some? v) (not= "" v)) (str (name k) "=" (enc v)))) query)]
    (str (http/base-url) "/files" path (when (seq q) (str "?" (str/join "&" q))))))

(defn kebab [data]
  (cond
    (map? data) (reduce-kv (fn [m k v] (assoc m (keyword (str/replace (name k) "_" "-")) (kebab v))) {} data)
    (sequential? data) (mapv kebab data)
    :else data))

(defn shape [data]
  (if (= "kebab" (some-> (core/the-client) :key-format name)) (kebab data) data))

(defn refusal
  "The ex-info for a refused /files call."
  [{:keys [status body headers]}]
  (let [body (cond
               (instance? InputStream body) (slurp body)
               (bytes? body) (String. ^bytes body StandardCharsets/UTF_8)
               :else body)
        parsed (try (json/read-str body {:key-fn keyword}) (catch Exception _ nil))
        error (when (map? (:error parsed)) (:error parsed))
        request-id (or (get headers "x-request-id") (get headers "X-Request-Id"))]
    (cond
      (= 416 status)
      (err/ex-info "Range not satisfiable" {:code "RANGE_NOT_SATISFIABLE" :status 416 :request-id request-id})

      (or (= "NOT_FOUND" (:code error)) (and (nil? error) (= 404 status)))
      (err/ex-info "Files are not available on this server — is the files module running?"
                   {:code "FILES_UNAVAILABLE" :status status :request-id request-id})

      error
      (err/ex-info (or (:message error) (str "Files request failed: HTTP " status))
                   (assoc error :status status :request-id request-id))

      :else
      (err/ex-info (str "Files request failed: HTTP " status)
                   {:code "HTTP_ERROR" :status status :body body :request-id request-id}))))

(defn send!
  [method u opts]
  (let [resp (http/authed method u opts)]
    (if (or (<= 200 (:status resp) 299) (= 304 (:status resp)))
      resp
      (throw (refusal resp)))))

(defn json-call
  ([method path] (json-call method path nil {}))
  ([method path payload] (json-call method path payload {}))
  ([method path payload query]
   (let [{:keys [body]} (send! method (endpoint-url path query)
                               (cond-> {}
                                 (some? payload) (assoc :body (json/write-str payload)
                                                        :headers {"Content-Type" "application/json"})))]
     (when-not (str/blank? body)
       (shape (json/read-str body {:key-fn keyword}))))))

(defn seg [x] (str "/" (enc (xid-of x))))

(defn file-at
  "Path and query of one file: /<xid><tail>, or <tail>?path= for a path."
  ([ref] (file-at ref ""))
  ([ref tail]
   (if (path? ref) [tail {:path ref}] [(str (seg ref) tail) nil])))

(defn folder-at
  "Path and query of one folder: /folders/<xid><tail>, or /folders<tail>?path= for a path."
  ([ref] (folder-at ref ""))
  ([ref tail]
   (let [r (folder-ref ref)]
     (if (path? r) [(str "/folders" tail) {:path r}] [(str "/folders" (seg r) tail) nil]))))

(declare info)

(defn xid-at
  "The xid of file `ref`; a path costs one info call."
  [ref]
  (if-not (path? ref)
    (xid-of ref)
    (or (:xid (info ref))
        (throw (err/ex-info (str "No such file: " ref) {:code "FILE_NOT_FOUND" :status 404})))))

(defn version-id [v] (if (map? v) (:id v) v))

(defn body+type
  "Bytes to send and their content type; a stream is read into memory so the 401 retry can resend it."
  [data content-type]
  (cond
    (string? data) [(.getBytes ^String data StandardCharsets/UTF_8) (or content-type "text/plain; charset=utf-8")]
    (bytes? data) [data (or content-type "application/octet-stream")]
    (instance? File data) [data (or content-type "application/octet-stream")]
    (instance? InputStream data) [(.readAllBytes ^InputStream data) (or content-type "application/octet-stream")]
    :else (throw (err/ex-info "put takes bytes, a string, a java.io.File or an InputStream" {:code "INVALID_BODY"}))))

(defn put
  "Store bytes: a path (/a/b/name, the folder must exist) creates or replaces that file, an xid or the file replaces it, {:name n} makes a new file in no folder. Options: :content-type :if-match (a hash, or the file)"
  [target data & {:keys [content-type if-match]}]
  (let [[body ctype] (body+type data content-type)
        expected (if (map? if-match) (:hash if-match) if-match)
        [method path query] (cond
                              (path? target) [:put "" {:path target}]
                              (xid-of target) [:put (seg target) nil]
                              (:name target) [:post "" {:name (:name target)}]
                              :else (throw (err/ex-info "put takes a path, an xid or file, or {:name n} for a file in no folder"
                                                        {:code "INVALID_PATH"})))]
    (when (str/starts-with? (str/lower-case ctype) "application/x-www-form-urlencoded")
      (throw (err/ex-info "a form-encoded body is parsed as a form, not stored — send the bytes with their own type"
                          {:code "UNSUPPORTED_MEDIA_TYPE"})))
    (-> (send! method (endpoint-url path query)
               {:body body
                :headers (cond-> {"Content-Type" ctype}
                           expected (assoc "If-Match" (str "\"" expected "\"")))})
        :body
        (json/read-str {:key-fn keyword})
        shape)))

(defn download
  "The bytes of file `ref`, or nil while :if-none-match still matches. Options: :as (:bytes default, :text, :stream to close yourself) :range [start end] (inclusive, nil leaves a side open) :version :if-none-match"
  [ref & {:keys [as range version if-none-match] :or {as :bytes}}]
  (let [[start end] range
        [path query] (if (some? version)
                       [(str (seg (xid-at ref)) "/versions/" (enc (version-id version))) nil]
                       (file-at ref))
        resp (send! :get (endpoint-url path query)
                    (cond-> {:headers (cond-> {}
                                        range (assoc "Range" (str "bytes=" start "-" end))
                                        if-none-match (assoc "If-None-Match" (str "\"" if-none-match "\"")))}
                      (not= :text as) (assoc :as (if (= :stream as) :stream :bytes))))]
    (when-not (= 304 (:status resp))
      (:body resp))))

(defn info
  "File `ref`, or nil when it is not there or you cannot see it."
  [ref]
  (try
    (let [[path query] (file-at ref "/info")] (json-call :get path nil query))
    (catch clojure.lang.ExceptionInfo e
      (when-not (#{"FILE_NOT_FOUND" "FOLDER_NOT_FOUND"} (:code (ex-data e))) (throw e)))))

(defn holds-words?
  "Whether `s` holds the words of `q` in order, ignoring case."
  [q s]
  (let [words (remove str/blank? (str/split (str/trim (str q)) #"\s+"))]
    (or (empty? words)
        (boolean (re-find (re-pattern (str "(?i)" (str/join ".*" (map #(java.util.regex.Pattern/quote %) words))))
                          (str s))))))

(defn list
  "{:files :folders}: a folder's files and subfolders; nil is the files in no folder, :all (or no folder) every file you can see, newest first. Options: :q :limit :offset"
  ([] (list :all))
  ([folder & {:keys [q limit offset]}]
   (let [page {:q q :limit limit :offset offset}
         files #(:files (json-call :get "" nil %))]
     (case folder
       :all {:files (files page) :folders []}
       nil {:files (files (assoc page :folderless "true")) :folders []}
       (let [ref (folder-ref folder)
             fs (future (files (assoc page :folder ref)))
             folders (:folders (json-call :get "/folders" nil {:parent ref}))]
         {:files @fs :folders (filterv #(holds-words? q (:name %)) folders)})))))

(defn search
  "Folders and files whose paths hold the words of `q` in order, shortest first: {:results :truncated}, each result with :type. Options: :folder :limit :offset"
  [q & {:keys [folder limit offset]}]
  (json-call :get "/search" nil {:q q :folder (some-> folder folder-ref) :limit limit :offset offset}))

(defn delete
  "Delete a file, or an empty folder (a path ending in /, or the folder itself)."
  [ref]
  (let [[path query] (if (folder? ref) (folder-at ref) (file-at ref))]
    (send! :delete (endpoint-url path query) {})
    nil))

(defn url
  "Where file `ref` loads from, without a request: /files/<xid>, or /files/path/… for a path. Option :inline opens it in the browser instead."
  [ref & {:keys [inline]}]
  (let [u (if (path? ref)
            (if (str/ends-with? ref "/")
              (throw (err/ex-info "url takes a file, not a folder" {:code "INVALID_PATH"}))
              (str (http/base-url) "/files/path" (str/join "/" (map enc-part (str/split ref #"/" -1)))))
            (str (http/base-url) "/files/" (enc (xid-of ref))))]
    (if inline (str u "?disposition=inline") u)))

(defn move-body
  "PATCH body for moving `from` to path `to`."
  [from to]
  (when-not (path? to)
    (throw (err/ex-info "move takes the new path, for example /site/brand/logo.png" {:code "INVALID_PATH"})))
  (if (folder? from)
    (let [parts (remove str/blank? (str/split to #"/"))
          name (last parts)]
      (when-not name
        (throw (err/ex-info "a folder moves to a path like /archive/2026/" {:code "INVALID_PATH"})))
      {:name name :parent (if (next parts) (str "/" (str/join "/" (butlast parts)) "/") "/")})
    (if (str/ends-with? to "/")
      {:folder to}
      (let [cut (inc (str/last-index-of to "/"))]
        {:folder (subs to 0 cut) :name (subs to cut)}))))

(defn move
  "Rename or move: a file goes to `to` (/a/new.png), or into a folder when `to` ends in / (the name stays); a folder's `to` is its new path."
  [from to]
  (let [body (move-body from to)
        [path query] (if (folder? from) (folder-at from) (file-at from))]
    (json-call :patch path body query)))

(defn folder
  "The folder at `path`, made with every missing folder above it; `settings` {:public :cache} sets its own values (nil inherits)."
  ([path] (folder path {}))
  ([path settings] (json-call :put "/folders" settings {:path path})))

(defn grantees [{:keys [users groups]}]
  {:users (mapv xid-of users) :groups (mapv xid-of groups)})

(defn share
  "Give {:users :groups} (xids or records) `access` (read, write or delete) on `folder`; writers of the folder may."
  [folder access to]
  (let [[path query] (folder-at folder "/share")]
    (json-call :post path (assoc (grantees to) :access (name access)) query)
    nil))

(defn unshare
  "Take `access` on `folder` away from {:users :groups}."
  [folder access from]
  (let [[path query] (folder-at folder "/unshare")]
    (json-call :post path (assoc (grantees from) :access (name access)) query)
    nil))

(defn access
  "Who holds read, write and delete on `folder`."
  [folder]
  (let [[path query] (folder-at folder "/access")] (json-call :get path nil query)))

(defn versions
  "The kept versions of file `ref` (the last ten), newest first."
  [ref]
  (:versions (json-call :get (str (seg (xid-at ref)) "/versions"))))

(defn restore
  "Make kept `version` (its :id, or the version) of file `ref` current again; no bytes are copied."
  [ref version]
  (json-call :post (str (seg (xid-at ref)) "/versions/" (enc (version-id version)) "/restore")))

(def served-headers
  ["content-type" "content-length" "content-range" "accept-ranges" "etag"
   "content-disposition" "cache-control" "x-content-type-options" "content-security-policy"])

(defn serve
  "Ring: answer `request` with file `ref` as a ring response (body an InputStream); Range and If-None-Match pass through, a refusal is the engine's own answer. Option :inline"
  [ref request & {:keys [inline]}]
  (let [[path query] (file-at ref)
        in (:headers request)
        resp (http/authed (if (= :head (:request-method request)) :head :get)
                          (endpoint-url path (cond-> query inline (assoc :disposition "inline")))
                          {:as :stream
                           :headers (into {} (keep (fn [k] (when-let [v (get in k)] [k v]))) ["range" "if-none-match" "if-range"])})
        out (:headers resp)]
    {:status (:status resp)
     :headers (into {} (keep (fn [k] (when-let [v (get out k)] [k v]))) served-headers)
     :body (:body resp)}))
