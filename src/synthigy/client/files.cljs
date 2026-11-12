(ns synthigy.client.files
  "Files: the bytes behind /files, each also a File record on /data. Every call returns a Promise.

    (require '[synthigy.client.files :as files])

    (p/let [_ (files/folder \"/site/img/\")                 ; the folder, and any missing above it
            f (files/put \"/site/img/logo.png\" blob)       ; creates it; again: a new version
            bytes (files/download \"/site/img/logo.png\")]  ; Uint8Array; :as :blob :text …
      (files/move f \"/site/brand/\"))

  A file is a path (/a/b/name), an xid or the file itself; a folder is a path
  ending in /, an xid or the folder itself. Response keys follow the client's
  :key-format, as /data results do: kebab-case by default."
  (:refer-clojure :exclude [list])
  (:require
   [clojure.set :as set]
   [clojure.string :as str]
   [promesa.core :as p]
   [synthigy.client.core :as core]
   [synthigy.client.error :as err]
   [synthigy.client.http :as http]))

(defn enc [x] (js/encodeURIComponent (str x)))

(defn enc-part
  "One path segment percent-encoded but for RFC 3986 unreserved characters, as the server builds links."
  [s]
  (str/replace (enc s) #"[!'()*]" #(str "%" (.toUpperCase (.toString (.charCodeAt % 0) 16)))))

(defn xid-of [x] (if (map? x) (:xid x) x))

(defn path? [x] (and (string? x) (str/starts-with? x "/")))

(defn file-ref [x] (if (map? x) (or (:xid x) (:path x)) x))

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

(defn shape [client data]
  (if (= "kebab" (some-> client :key-format name)) (kebab data) data))

(defn parse-json [text]
  (when-not (str/blank? text)
    (try (js->clj (js/JSON.parse text) :keywordize-keys true) (catch :default _ nil))))

(defn refusal
  "The ex-info for a refused /files call."
  [{:keys [status body-text headers]}]
  (let [parsed (parse-json body-text)
        error (when (map? (:error parsed)) (:error parsed))
        request-id (some-> headers (.get "x-request-id"))]
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
                   {:code "HTTP_ERROR" :status status :body body-text :request-id request-id}))))

(defn cased
  "`u` with the client's key format as ?key_format=, unless it is snake or unset."
  [u]
  (let [kf (some-> (core/the-client) :key-format name)]
    (if (or (nil? kf) (= "snake" kf)) u (str u (if (str/includes? u "?") "&" "?") "key_format=" kf))))

(defn send!
  [method u {:keys [acting-as] :as opts}]
  (p/let [resp (http/authed method (cased u) (cond-> (dissoc opts :acting-as)
                                               acting-as (assoc-in [:headers "X-Acting-As"] acting-as)))]
    (cond
      (or (<= 200 (:status resp) 299) (= 304 (:status resp))) resp
      (:response resp) (p/let [text (.text ^js (:response resp))] (throw (refusal (assoc resp :body-text text))))
      :else (throw (refusal resp)))))

(defn json-call
  ([method path] (json-call method path nil {}))
  ([method path payload] (json-call method path payload {}))
  ([method path payload query] (json-call method path payload query nil))
  ([method path payload query acting-as]
   (let [client (core/the-client)]
     (p/let [{:keys [body-text]} (send! method (endpoint-url path query)
                                        (cond-> {:timeout-ms (:request-timeout client) :acting-as acting-as}
                                          (some? payload) (assoc :body (js/JSON.stringify (clj->js payload))
                                                                 :headers {"Content-Type" "application/json"})))]
       (some->> (parse-json body-text) (shape client))))))

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
  "Promise of the xid of file `ref`; a path costs one info call."
  ([ref] (xid-at ref nil))
  ([ref acting-as]
   (if-not (path? ref)
     (p/resolved (xid-of ref))
     (p/let [f (info ref :acting-as acting-as)]
       (when-not f (throw (err/ex-info (str "No such file: " ref) {:code "FILE_NOT_FOUND" :status 404})))
       (:xid f)))))

(defn version-id [v] (if (map? v) (:id v) v))

(defn blob? [x] (and (exists? js/Blob) (instance? js/Blob x)))

(defn body+type
  "Body to send and its content type."
  [data content-type]
  (cond
    (string? data) [data (or content-type "text/plain; charset=utf-8")]
    (blob? data) [data (or content-type (not-empty (.-type data)) "application/octet-stream")]
    (or (instance? js/ArrayBuffer data) (js/ArrayBuffer.isView data)) [data (or content-type "application/octet-stream")]
    :else (throw (err/ex-info "put takes a Blob, an ArrayBuffer, a Uint8Array or a string" {:code "INVALID_BODY"}))))

(defn put
  "Store bytes: a path (/a/b/name, the folder must exist) creates or replaces that file, an xid or the file replaces it, {:name n} makes a new file in no folder. Options: :content-type :if-match (a hash, or the file)"
  [target data & {:keys [content-type if-match acting-as]}]
  (let [client (core/the-client)]
    (try
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
        (p/let [{:keys [body-text]} (send! method (endpoint-url path query)
                                           {:body body
                                            :acting-as acting-as
                                            :headers (cond-> {"Content-Type" ctype}
                                                       expected (assoc "If-Match" (str "\"" expected "\"")))})]
          (shape client (parse-json body-text))))
      (catch :default e (p/rejected e)))))

(defn download
  "The bytes of file `ref`, or nil while :if-none-match still matches. Options: :as (:bytes default, :blob :text :array-buffer :stream :response) :range [start end] (inclusive, nil leaves a side open) :version :if-none-match"
  [ref & {:keys [as range version if-none-match acting-as] :or {as :bytes}}]
  (let [[start end] range]
    (p/let [[path query] (if (some? version)
                           (p/let [x (xid-at ref acting-as)] [(str (seg x) "/versions/" (enc (version-id version))) nil])
                           (file-at ref))
            resp (send! :get (endpoint-url path query)
                        {:as (case as (:bytes :blob) as :response)
                         :acting-as acting-as
                         :headers (cond-> {}
                                    range (assoc "Range" (str "bytes=" start "-" end))
                                    if-none-match (assoc "If-None-Match" (str "\"" if-none-match "\"")))})]
      (when-not (= 304 (:status resp))
        (let [^js r (:response resp)]
          (case as
            (:bytes :blob) (:body resp)
            :text (.text r)
            :array-buffer (.arrayBuffer r)
            :stream (.-body r)
            r))))))

(defn zip
  "One zip of :files and of everything inside :folders (xids, paths or maps), read as you; what you cannot read is listed in its _skipped.txt. :as as in download (:bytes default)."
  [& {:keys [files folders as acting-as] :or {as :bytes}}]
  (p/let [resp (send! :post (endpoint-url "/zip" nil)
                      {:as (case as (:bytes :blob) as :response)
                       :acting-as acting-as
                       :headers {"Content-Type" "application/json"}
                       :body (js/JSON.stringify (clj->js {:files (mapv file-ref files) :folders (mapv folder-ref folders)}))})]
    (let [^js r (:response resp)]
      (case as
        (:bytes :blob) (:body resp)
        :text (.text r)
        :array-buffer (.arrayBuffer r)
        :stream (.-body r)
        r))))

(defn info
  "File `ref`, or nil when it is not there or you cannot see it."
  [ref & {:keys [acting-as]}]
  (p/catch (let [[path query] (file-at ref "/info")] (json-call :get path nil query acting-as))
           (fn [e]
             (when-not (#{"FILE_NOT_FOUND" "FOLDER_NOT_FOUND"} (:code (ex-data e))) (throw e)))))

(defn holds-words?
  "Whether `s` holds the words of `q` in order, ignoring case."
  [q s]
  (let [words (remove str/blank? (str/split (str/trim (str q)) #"\s+"))]
    (or (empty? words)
        (boolean (re-find (re-pattern (str "(?i)" (str/join ".*" (map #(str/replace % #"[.*+?^${}()|\[\]\\]" "\\$&") words))))
                          (str s))))))

(defn list
  "{:files :folders}: a folder's files and subfolders; nil is the files in no folder, :all (or no folder) every file you can see, newest first. Options: :q :limit :offset"
  ([] (list :all))
  ([folder & {:keys [q limit offset acting-as]}]
   (let [page {:q q :limit limit :offset offset}
         files #(p/let [r (json-call :get "" nil % acting-as)] (:files r))]
     (case folder
       :all (p/let [fs (files page)] {:files fs :folders []})
       nil (p/let [fs (files (assoc page :folderless "true"))] {:files fs :folders []})
       (let [ref (folder-ref folder)]
         (p/let [[fs r] (p/all [(files (assoc page :folder ref))
                                (json-call :get "/folders" nil {:parent ref} acting-as)])]
           {:files fs :folders (filterv #(holds-words? q (:name %)) (:folders r))}))))))

(defn search
  "Folders and files whose paths hold the words of `q` in order, shortest first: {:results :truncated}, each result with :type. Options: :folder :limit :offset"
  [q & {:keys [folder limit offset acting-as]}]
  (json-call :get "/search" nil {:q q :folder (some-> folder folder-ref) :limit limit :offset offset} acting-as))

(defn delete
  "Delete a file, or an empty folder (a path ending in /, or the folder itself)."
  [ref & {:keys [acting-as]}]
  (p/let [[path query] (if (folder? ref) (folder-at ref) (file-at ref))
          _ (send! :delete (endpoint-url path query) {:acting-as acting-as})]
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
  [from to & {:keys [acting-as]}]
  (try
    (let [body (move-body from to)
          [path query] (if (folder? from) (folder-at from) (file-at from))]
      (json-call :patch path body query acting-as))
    (catch :default e (p/rejected e))))

(defn folder
  "The folder at `path`, made with every missing folder above it; `settings` {:public :cache :can-link} sets its own values (nil inherits)."
  ([path] (folder path {}))
  ([path settings & {:keys [acting-as]}]
   (json-call :put "/folders" (set/rename-keys settings {:can-link :can_link}) {:path path} acting-as)))

(defn grantees [{:keys [users groups]}]
  {:users (mapv xid-of users) :groups (mapv xid-of groups)})

(defn share
  "Give {:users :groups} (xids or records) `access` (read, write or delete) on `folder`; writers of the folder may."
  [folder access to & {:keys [acting-as]}]
  (let [[path query] (folder-at folder "/share")]
    (p/let [_ (json-call :post path (assoc (grantees to) :access (name access)) query acting-as)] nil)))

(defn unshare
  "Take `access` on `folder` away from {:users :groups}."
  [folder access from & {:keys [acting-as]}]
  (let [[path query] (folder-at folder "/unshare")]
    (p/let [_ (json-call :post path (assoc (grantees from) :access (name access)) query acting-as)] nil)))

(defn access
  "Who holds read, write and delete on `folder`."
  [folder & {:keys [acting-as]}]
  (let [[path query] (folder-at folder "/access")] (json-call :get path nil query acting-as)))

(defn versions
  "The kept versions of file `ref` (the last ten), newest first."
  [ref & {:keys [acting-as]}]
  (p/let [x (xid-at ref acting-as)
          r (json-call :get (str (seg x) "/versions") nil nil acting-as)]
    (:versions r)))

(defn restore
  "Make kept `version` (its :id, or the version) of file `ref` current again; no bytes are copied."
  [ref version & {:keys [acting-as]}]
  (p/let [x (xid-at ref acting-as)]
    (json-call :post (str (seg x) "/versions/" (enc (version-id version)) "/restore") nil nil acting-as)))

(defn link-body [{:keys [expires max-downloads inline]}]
  (cond-> {}
    (some? expires) (assoc :expires expires)
    (some? max-downloads) (assoc :max_downloads max-downloads)
    (some? inline) (assoc :inline inline)))

(defn link
  "A link that downloads file `ref` without signing in, where its folder allows links; its :url is shown only now. Options: :expires (seconds, default 3600, at most 7 days) :max-downloads :inline"
  [ref & {:keys [acting-as] :as opts}]
  (p/let [x (xid-at ref acting-as)]
    (json-call :post (str (seg x) "/links") (link-body opts) nil acting-as)))

(defn links
  "The live links of file `ref` you can see: your own, or all of them for File Admin."
  [ref & {:keys [acting-as]}]
  (p/let [x (xid-at ref acting-as)
          r (json-call :get (str (seg x) "/links") nil nil acting-as)]
    (:links r)))

(defn unlink
  "End `link` (the link or its xid) at once, on every engine."
  [link & {:keys [acting-as]}]
  (p/let [_ (json-call :delete (str "/links" (seg link)) nil nil acting-as)] nil))

(defn object-url
  "Browser: download with your token into a blob: URL for <img> or <iframe>: {:url :revoke}."
  [ref & {:keys [acting-as]}]
  (p/let [blob (download ref :as :blob :acting-as acting-as)
          u (js/URL.createObjectURL blob)]
    {:url u :revoke #(js/URL.revokeObjectURL u)}))

(def served-headers
  ["content-type" "content-length" "content-range" "accept-ranges" "etag"
   "content-disposition" "cache-control" "x-content-type-options" "content-security-policy"])

(defn serve
  "Server: answer a browser's js/Request (or {:method :headers}) with file `ref` as a js/Response; Range and If-None-Match pass through, a refusal is the engine's own answer. Option :inline"
  [ref ^js request & {:keys [inline acting-as]}]
  (let [header (fn [k] (if (map? request) (get-in request [:headers k]) (some-> request .-headers (.get k))))
        [path query] (file-at ref)
        method (if (= "HEAD" (str/upper-case (str (if (map? request) (some-> request :method name) (some-> request .-method)))))
                 :head :get)]
    (p/let [resp (http/authed method (endpoint-url path (cond-> query inline (assoc :disposition "inline")))
                              {:as :response
                               :headers (cond-> (into {} (keep (fn [k] (when-let [v (header k)] [k v]))) ["range" "if-none-match" "if-range"])
                                          acting-as (assoc "X-Acting-As" acting-as))})
            ^js r (:response resp)
            out (js/Headers.)]
      (doseq [k served-headers]
        (when-let [v (.get (.-headers r) k)] (.set out k v)))
      (js/Response. (.-body r) #js {:status (.-status r) :headers out}))))
