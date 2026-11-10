(ns synthigy.client.files-integration-test
  "synthigy.client.files against a live engine, as three identities: File Admin, File User and File Reader.

   Env: SYNTHIGY_FILES_TEST_ENDPOINT and SYNTHIGY_FILES_TEST_{ADMIN,USER,READER}_{ID,SECRET},
   client-credentials clients whose service users hold those roles. Skips when the endpoint is unset.

   Run: clj -M:files-integration"
  (:require
   [clojure.test :refer [deftest is testing]]
   [synthigy.client :as client]
   [synthigy.client.core :as core]
   [synthigy.client.files :as files]))

(def endpoint (System/getenv "SYNTHIGY_FILES_TEST_ENDPOINT"))

(defn identity-client [who & {:as opts}]
  (core/create-client (merge {:endpoint endpoint
                              :client-id (System/getenv (str "SYNTHIGY_FILES_TEST_" who "_ID"))
                              :client-secret (System/getenv (str "SYNTHIGY_FILES_TEST_" who "_SECRET"))}
                             opts)))

(defmacro as [c & body] `(binding [core/*client* ~c] ~@body))

(defn refused [f]
  (try (f) nil (catch clojure.lang.ExceptionInfo e (ex-data e))))

(defn ubytes [& xs] (byte-array (map unchecked-byte xs)))

(defn same-bytes? [^bytes a ^bytes b] (java.util.Arrays/equals a b))

(defn holders [access level]
  (set (map :xid (get-in access [level :users]))))

(defn fetch
  "A GET without a token: status, bytes and the CORS and cache headers."
  [u]
  (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. u))]
    {:status (.getResponseCode conn)
     :body (with-open [in (.getInputStream conn)] (.readAllBytes in))
     :cors (.getHeaderField conn "Access-Control-Allow-Origin")
     :cache (.getHeaderField conn "Cache-Control")}))

(defn link-get
  "A tokenless GET of a download link: status, text and the headers a link answers with."
  [u & {:keys [range]}]
  (let [conn ^java.net.HttpURLConnection (.openConnection (java.net.URL. u))]
    (when range (.setRequestProperty conn "Range" range))
    (let [status (.getResponseCode conn)]
      {:status status
       :text (when (= 200 status) (with-open [in (.getInputStream conn)] (slurp in)))
       :disposition (.getHeaderField conn "Content-Disposition")
       :referrer (.getHeaderField conn "Referrer-Policy")})))

(defn poll-versions [xid n]
  (loop [tries 60]
    (let [vs (files/versions xid)]
      (if (or (<= n (count vs)) (zero? tries))
        vs
        (do (Thread/sleep 500) (recur (dec tries)))))))

(defn delete-folder-xid! [xid]
  (let [[path query] (files/folder-at xid)] (files/send! :delete (files/endpoint-url path query) {})))

(defn cleanup! [admin user created]
  (doseq [[kind xid] (reverse @created)
          c [user admin]]
    (try
      (as c (if (= kind :file) (files/delete xid) (delete-folder-xid! xid)))
      (catch Exception _ nil))))

(defn root-folder [xid] (some #(when (= xid (:xid %)) %) (:folders (files/list "/"))))

(deftest files-scenario
  (if-not endpoint
    (println "SKIP files-integration: SYNTHIGY_FILES_TEST_ENDPOINT is unset")
    (let [run (str "clj-" (System/currentTimeMillis) "-" (rand-int 100000))
          admin (identity-client "ADMIN")
          user (identity-client "USER")
          reader (identity-client "READER")
          created (atom [])
          track! (fn [kind x] (swap! created conj [kind (:xid x)]) x)
          binary (ubytes 0x00 0x01 0x02 0x03 0x04 0x05 0xff 0x00 0xff)
          not-utf8 (ubytes 0xff 0xfe 0x7b 0x7d)
          ok? (atom false)]
      (try
        (let [top (as admin (track! :folder (files/folder (str "/top-" run "/"))))
              user-xid (:xid (as user (client/whoami)))
              reader-xid (:xid (as reader (client/whoami)))]

          (testing "1. admin creates F and shares it"
            (is (= (str "/top-" run "/") (:path top)))
            (as admin
              (doseq [level [:write :read :delete]]
                (files/share top level {:users [user-xid]}))
              (files/share top :read {:users [reader-xid]})
              (let [access (files/access top)]
                (is (contains? (holders access :write) user-xid))
                (is (contains? (holders access :read) user-xid))
                (is (contains? (holders access :delete) user-xid))
                (is (contains? (holders access :read) reader-xid))
                (is (not (contains? (holders access :write) reader-xid))))))

          (let [bin-path (str (:path top) "bin-" run ".dat")
                bin (as user (track! :file (files/put bin-path binary)))
                js (as user (track! :file (files/put (str (:path top) "json-" run ".json") not-utf8 :content-type "application/json")))
                loose (as user (track! :file (files/put {:name (str "loose-" run ".txt")} "folderless")))]

            (testing "2. user uploads, reads, versions"
              (as user
                (is (same-bytes? binary (files/download bin)) "binary round-trips byte-exact")
                (is (same-bytes? binary (files/download bin-path)) "by path too")
                (is (same-bytes? not-utf8 (files/download js)) "non-UTF-8 application/json round-trips byte-exact")
                (let [i (files/info bin)]
                  (is (= [(str "bin-" run ".dat") (alength binary) (:xid top)]
                         [(:name i) (:size i) (get-in i [:folder :xid] (:folder i))]))
                  (is (= (:xid bin) (:xid (files/info bin-path))))
                  (is (some #{(:xid bin)} (map :xid (:files (files/list top)))))
                  (is (some #{(:xid bin)} (map :xid (:results (files/search (str "top " run " bin"))))))
                  (is (same-bytes? (ubytes 0x02 0x03 0x04) (files/download bin :range [2 4])))
                  (is (same-bytes? (ubytes 0xff 0x00 0xff) (files/download bin :range [nil 3])))
                  (is (nil? (files/download bin :if-none-match (:hash i))))
                  (let [e (refused #(files/put bin "stale" :if-match "0000stale"))]
                    (is (= ["FILE_CHANGED" "conflict"] ((juxt :code :category) e))))
                  (let [r (files/put bin-path "second version" :if-match i)]
                    (is (= (:xid bin) (:xid r)) "a put to the same path keeps the xid")
                    (is (not= (:hash i) (:hash r)))
                    (is (= "second version" (files/download bin :as :text))))
                  (let [vs (poll-versions (:xid bin) 2)
                        [newest old] vs]
                    (is (= 2 (count vs)) "two versions within 30s")
                    (is (true? (:current newest)))
                    (is (not (:current old)))
                    (when old
                      (is (same-bytes? binary (files/download bin :version old)))
                      (files/restore bin-path old)
                      (is (= (:hash i) (:hash (files/info bin))))
                      (is (same-bytes? binary (files/download bin))))))
                (is (= ["FOLDER_NOT_FOUND" "not_found"] ((juxt :code :category) (refused #(files/put (str "/nope-" run "/x.txt") "x")))))
                (is (= ["INVALID_NAME" "validation"] ((juxt :code :category) (refused #(files/move bin (str (:path top) "a\\b")))))
                    "a backslash in the name")
                (is (nil? (:folder loose)) "folderless upload has no folder")
                (is (= "folderless" (files/download loose :as :text)))))

            (let [sub (as user (track! :folder (files/folder (str (:path top) "sub-" run "/"))))
                  renamed (atom sub)]
              (testing "3. user manages a subfolder and moves a file"
                (as user
                  (reset! renamed (files/move sub (str (:path top) "sub2-" run "/")))
                  (is (= (str "/top-" run "/sub2-" run "/") (:path @renamed)))
                  (let [moved (files/move js (:path @renamed))]
                    (is (= (:xid @renamed) (get-in moved [:folder :xid])) "a / target keeps the name"))
                  (is (= ["FILE_NAME_TAKEN" "conflict"]
                         ((juxt :code :category) (refused #(files/move loose bin-path)))))
                  (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) (refused #(files/move @renamed (str "/sub2-" run "/"))))))
                  (is (= ["FOLDER_NOT_EMPTY" "conflict"] ((juxt :code :category) (refused #(files/delete top)))))))

              (testing "4. reader sees F read-only"
                (as reader
                  (let [seen (root-folder (:xid top))]
                    (is (some? seen))
                    (is (false? (:writable seen))))
                  (is (same-bytes? binary (files/download bin)))
                  (is (nil? (files/info loose)) "a folderless file of another user is not visible")
                  (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) (refused #(files/put (str (:path top) "r-" run ".txt") "r")))))
                  (let [e (refused #(files/put bin "reader"))]
                    (is (= ["FORBIDDEN" "iam" 403] ((juxt :code :category :status) e))))
                  (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) (refused #(files/folder (str (:path top) "rsub-" run "/"))))))
                  (let [old (last (as user (files/versions bin)))]
                    (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) (refused #(files/restore bin old))))))))

              (testing "5. links: File Admin makes F public, File User sets its cache"
                (as user
                  (is (false? (:public (files/info bin))))
                  (is (= ["ATTRIBUTE_FORBIDDEN" "iam"]
                         ((juxt :code :category) (refused #(files/folder (:path top) {:public true})))))
                  (is (= ["ENGINE_OWNED"] [(:code (refused #(client/sync "Folder" {:xid (:xid top) :cache 60})))]) "folders change only through /files")
                  (files/folder (:path top) {:cache 120}))
                (as admin (files/folder (:path top) {:public true}))
                (as user
                  (let [i (files/info bin)]
                    (is (true? (:public i)))
                    (doseq [u [(files/url i) (files/url bin-path)]]
                      (let [r (fetch u)]
                        (is (= [200 "public, max-age=120" "*"] ((juxt :status :cache :cors) r)) u)
                        (is (same-bytes? binary (:body r))))))))

              (testing "5b. download links"
                (let [shut (as user (track! :file (files/put (str (:path top) "link-" run ".txt") "linked")))]
                  (as admin (files/folder (:path top) {:public false}))
                  (is (= ["LINKS_DISABLED" "iam"] ((juxt :code :category) (refused #(as user (files/link shut))))))
                  (is (= ["ATTRIBUTE_FORBIDDEN" "iam"] ((juxt :code :category) (refused #(as user (files/folder (:path top) {:can-link true}))))))
                  (let [shown (as admin (files/folder (:path top) {:can-link true}))]
                    (is (true? (:can-link shown)))
                    (is (true? (get-in shown [:settings :can-link]))))
                  (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) (refused #(as reader (files/link shut))))))
                  (is (= ["INVALID_EXPIRES" "validation"] ((juxt :code :category) (refused #(as user (files/link shut :expires 0))))))
                  (let [link (as user (files/link shut :expires 600))
                        once (as user (files/link shut :max-downloads 1 :inline true))
                        admins (as admin (files/link shut))
                        camel (identity-client "USER" :key-format "camel")]
                    (is (= [(:xid shut) user-xid 0] ((juxt :file :created-by :downloads) link)))
                    (is (= [200 "no-referrer" "linked"] ((juxt :status :referrer :text) (link-get (:url link)))))
                    (let [first-get (link-get (:url once) :range "bytes=1-2")]
                      (is (= [200 "linked"] ((juxt :status :text) first-get)) "a limited link ignores Range")
                      (is (re-find #"^inline" (str (:disposition first-get)))))
                    (is (= 404 (:status (link-get (:url once)))) "used up")
                    (let [seen (some #(when (= (:xid link) (:xid %)) %) (as camel (files/links shut)))]
                      (is (= [user-xid nil false] [(:createdBy seen) (:maxDownloads seen) (contains? seen :created_by)]) "key-format camel")
                      (is (= (:content-type (as user (files/info shut))) (:contentType (as camel (files/info shut))))))
                    (let [mine (as user (files/links shut))]
                      (is (= 1 (:downloads (some #(when (= (:xid link) (:xid %)) %) mine))))
                      (is (not-any? :url mine) "the url is shown once")
                      (is (not-any? #(= (:xid admins) (:xid %)) mine) "File User sees its own"))
                    (is (some #(= (:xid link) (:xid %)) (as admin (files/links shut))) "File Admin sees all")
                    (is (= ["LINK_NOT_FOUND" "not_found"] ((juxt :code :category) (refused #(as user (files/unlink (:xid admins)))))))
                    (as admin (files/folder (:path top) {:can-link false}))
                    (is (= 404 (:status (link-get (:url link)))) "switched off: existing links stop")
                    (as admin (files/folder (:path top) {:can-link true}))
                    (is (= 200 (:status (link-get (:url link)))))
                    (is (nil? (as user (files/unlink link))))
                    (is (= 404 (:status (link-get (:url link)))) "unlinked: gone at once")
                    (as user (files/delete shut))
                    (is (= 404 (:status (link-get (:url admins)))) "file deleted: its links die")
                    (let [back (as admin (files/folder (:path top) {:public true :can-link nil}))]
                      (is (= {:public true :cache 120 :can-link nil} (:settings back)))))))

              (testing "6. admin moves the subfolder to ROOT and deletes it"
                (as admin
                  (files/delete js)
                  (let [moved (files/move @renamed (str "/sub2-" run "/"))]
                    (is (= (str "/sub2-" run "/") (:path moved)))
                    (is (= (:parent top) (:parent moved)) "parent is ROOT, like F's")
                    (is (nil? (files/delete moved)))
                    (is (nil? (root-folder (:xid moved))))))))

            (testing "7. cleanup"
              (as user
                (doseq [f [bin loose]]
                  (is (nil? (files/delete f)))
                  (is (nil? (files/info f)))))
              (as admin
                (is (nil? (files/delete top)))
                (is (nil? (root-folder (:xid top)))))
              (reset! ok? true))))
        (finally
          (when-not @ok? (cleanup! admin user created)))))))
