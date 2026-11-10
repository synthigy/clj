(ns synthigy.client.files-live-test
  "Live files suite: File Admin, File User and File Reader clients walk folders, sharing, bytes, versions, links and refusals.

  Run with `npm run test:files`; skips unless SYNTHIGY_FILES_TEST_ENDPOINT and the
  SYNTHIGY_FILES_TEST_{ADMIN,USER,READER}_{ID,SECRET} client credentials are set."
  (:require
   [cljs.test :refer [deftest is async]]
   [promesa.core :as p]
   [synthigy.client :as client]
   [synthigy.client.core :as core]
   [synthigy.client.files :as files]))

(def env (if (exists? js/process) (.-env js/process) #js {}))

(defn env-of [k] (aget env k))

(def endpoint (env-of "SYNTHIGY_FILES_TEST_ENDPOINT"))

(def run-id (str "sdkcljs-" (.toString (js/Date.now) 36) "-" (subs (.toString (js/Math.random) 36) 2 8)))

(def BIN [0x00 0x01 0xff 0x7f 0x80 0x00 0xfe 0xff 0x10 0x20])
(def BAD-JSON [0xff 0xfe 0x7b 0x7d])
(def NEXT [0xff 0x00 0xaa 0x55 0x00])

(defn u8 [v] (js/Uint8Array.from (clj->js v)))

(defn bytes-of [x]
  (p/let [buf (if (instance? js/Uint8Array x) x (p/then (.arrayBuffer x) #(js/Uint8Array. %)))]
    (vec (js/Array.from buf))))

(defn mk [role & {:as opts}]
  (core/create-client (merge {:endpoint endpoint
                              :client-id (env-of (str "SYNTHIGY_FILES_TEST_" role "_ID"))
                              :client-secret (env-of (str "SYNTHIGY_FILES_TEST_" role "_SECRET"))}
                             opts)))

(defn as
  "Run `f` with `c` as the connected client; each call captures its client before it awaits."
  [c f & args]
  (set! core/*client* c)
  (apply f args))

(defn refused [pr]
  (p/catch (p/then pr (fn [v] {:resolved v})) ex-data))

(defn delete-folder-xid! [xid]
  (let [[path query] (files/folder-at xid)] (files/send! :delete (files/endpoint-url path query) {})))

(defn xids [users] (set (map #(if (map? %) (:xid %) %) users)))

(defn versions-until [c xid n deadline]
  (p/let [vs (as c files/versions xid)]
    (if (or (>= (count vs) n) (> (js/Date.now) deadline))
      vs
      (p/do (p/delay 500) (versions-until c xid n deadline)))))

(defn uploads-step
  "The user uploads, reads, replaces and restores; answers the files it made."
  [user F st]
  (p/let [bin (as user files/put (str (:path F) "bin-" run-id ".bin") (u8 BIN))
          _ (swap! st assoc :bin bin)
          original (:hash bin)
          _ (is (= (count BIN) (:size bin)))
          got (p/then (as user files/download (:xid bin)) bytes-of)
          _ (is (= BIN got) "binary round-trips byte-exact")
          json (as user files/put (str (:path F) "bad-" run-id ".json") (u8 BAD-JSON) :content-type "application/json")
          _ (swap! st assoc :json json)
          _ (is (= (count BAD-JSON) (:size json)))
          got (p/then (as user files/download (:xid json) :as :blob) bytes-of)
          _ (is (= BAD-JSON got) "invalid UTF-8 sent as application/json round-trips byte-exact")
          i (as user files/info (:xid bin))
          _ (is (= [(str "bin-" run-id ".bin") original (:xid F)] [(:name i) (:hash i) (get-in i [:folder :xid])]))
          listed (as user files/list F)
          _ (is (every? (set (map :xid (:files listed))) [(:xid bin) (:xid json)]))
          by-path (as user files/info (str (:path F) "bin-" run-id ".bin"))
          _ (is (= (:xid bin) (:xid by-path)))
          found (as user files/search (str "bin-" run-id))
          _ (is (= [[(:xid bin) "file"]] (mapv (juxt :xid :type) (:results found))))
          got (p/then (as user files/download (:xid bin) :range [2 4]) bytes-of)
          _ (is (= (subvec BIN 2 5) got))
          got (p/then (as user files/download (:xid bin) :range [nil 3]) bytes-of)
          _ (is (= (subvec BIN 7) got) "suffix range")
          nm (as user files/download (:xid bin) :if-none-match original)
          _ (is (nil? nm) "not modified")
          e (refused (as user files/put (:xid bin) (u8 NEXT) :if-match (apply str (repeat 64 "0"))))
          _ (is (= ["FILE_CHANGED" "conflict"] ((juxt :code :category) e)))
          nxt (as user files/put (str (:path F) "bin-" run-id ".bin") (u8 NEXT) :if-match original)
          _ (is (= (:xid bin) (:xid nxt)) "a put to the same path keeps the xid")
          _ (is (not= original (:hash nxt)))
          got (p/then (as user files/download (:xid bin)) bytes-of)
          _ (is (= NEXT got))
          vs (versions-until user (:xid bin) 2 (+ (js/Date.now) 30000))
          _ (is (= 2 (count vs)) "two versions within 30s")
          [cur old] vs
          _ (is (true? (:current cur)))
          _ (is (not (true? (:current old))))
          _ (is (= original (:hash old)))
          got (p/then (as user files/download (:xid bin) :version old) bytes-of)
          _ (is (= BIN got) "old version downloads")
          restored (as user files/restore (:xid bin) old)
          _ (is (= original (:hash restored)))
          i (as user files/info (:xid bin))
          _ (is (= original (:hash i)))
          e (refused (as user files/put (str "/nope-" run-id "/x.bin") (u8 BIN)))
          _ (is (= ["FOLDER_NOT_FOUND" "not_found"] ((juxt :code :category) e)))
          e (refused (as user files/move (:xid json) (str (:path F) "a\\b")))
          _ (is (= ["INVALID_NAME" "validation"] ((juxt :code :category) e)))
          loose (as user files/put {:name (str "loose-" run-id ".txt")} "loose")
          _ (swap! st assoc :loose loose)
          _ (is (nil? (:folder loose)))
          got (p/then (as user files/download (:xid loose)) bytes-of)
          _ (is (= (vec (.encode (js/TextEncoder.) "loose")) got))]
    {:bin bin :json json :loose loose}))

(defn reader-step
  "The reader sees F read-only and is refused every write."
  [reader user F bin loose]
  (p/let [top (as reader files/list "/")
          seen (first (filter #(= (:xid F) (:xid %)) (:folders top)))
          _ (is seen "reader lists F")
          _ (is (false? (:writable seen)))
          got (p/then (as reader files/download (:xid bin)) bytes-of)
          _ (is (= BIN got))
          hidden (as reader files/info (:xid loose))
          _ (is (nil? hidden) "a folderless file is not shared")
          e (refused (as reader files/put (str (:path F) "r-" run-id ".bin") (u8 BIN)))
          _ (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) e)))
          e (refused (as reader files/put (:xid bin) (u8 NEXT)))
          _ (is (= ["FORBIDDEN" 403] ((juxt :code :status) e)))
          e (refused (as reader files/folder (str (:path F) "r-" run-id "/")))
          _ (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) e)))
          vs (as user files/versions (:xid bin))
          e (refused (as reader files/restore (:xid bin) (or (second vs) 1)))
          _ (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) e)))]
    true))

(defn links-step
  "File Admin makes the folder public, File User sets its cache, and the plain link works without a token."
  [user admin F bin]
  (p/let [before (as user files/info (:xid bin))
          _ (is (false? (:public before)))
          e (refused (as user files/folder (:path F) {:public true}))
          _ (is (= ["ATTRIBUTE_FORBIDDEN" "iam"] ((juxt :code :category) e)))
          e (refused (as user client/sync "Folder" {:xid (:xid F) :cache 60}))
          _ (is (= "ENGINE_OWNED" (:code e)) "folders change only through /files")
          _ (as user files/folder (:path F) {:cache 120})
          _ (as admin files/folder (:path F) {:public true})
          i (as user files/info (:xid bin))
          _ (is (true? (:public i)))
          pub (as user files/url i)
          resp (js/fetch pub)
          _ (is (= [200 "public, max-age=120" "*"]
                   [(.-status resp) (.get (.-headers resp) "cache-control") (.get (.-headers resp) "access-control-allow-origin")]))
          got (p/then (.arrayBuffer resp) #(vec (js/Array.from (js/Uint8Array. %))))]
    (is (= BIN got) "the public link downloads without a token")))

(defn status-of [u & [headers]]
  (p/then (js/fetch u (clj->js {:headers (or headers {})})) #(.-status %)))

(defn of-xid [xid xs] (first (filter #(= xid (:xid %)) xs)))

(defn download-links-step
  "Links are off until File Admin switches them on; a link downloads without a token until it ends."
  [user admin reader F user-xid st]
  (p/let [shut (as user files/put (str (:path F) "link-" run-id ".txt") "linked")
          _ (swap! st assoc :shut shut)
          _ (as admin files/folder (:path F) {:public false})
          e (refused (as user files/link shut))
          _ (is (= ["LINKS_DISABLED" "iam"] ((juxt :code :category) e)))
          e (refused (as user files/folder (:path F) {:can-link true}))
          _ (is (= ["ATTRIBUTE_FORBIDDEN" "iam"] ((juxt :code :category) e)))
          shown (as admin files/folder (:path F) {:can-link true})
          _ (is (true? (:can-link shown)))
          e (refused (as reader files/link shut))
          _ (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) e)))
          e (refused (as user files/link shut :expires 0))
          _ (is (= ["INVALID_EXPIRES" "validation"] ((juxt :code :category) e)))
          link (as user files/link shut :expires 600)
          _ (is (= [(:xid shut) user-xid 0] ((juxt :file :created-by :downloads) link)))
          got (js/fetch (:url link))
          _ (is (= [200 "no-referrer"] [(.-status got) (.get (.-headers got) "referrer-policy")]))
          text (.text got)
          _ (is (= "linked" text))
          once (as user files/link shut :max-downloads 1 :inline true)
          first-get (js/fetch (:url once) #js {:headers #js {"range" "bytes=1-2"}})
          _ (is (= 200 (.-status first-get)) "a limited link ignores Range")
          _ (is (re-find #"^inline" (str (.get (.-headers first-get) "content-disposition"))))
          text (.text first-get)
          _ (is (= "linked" text))
          s (status-of (:url once))
          _ (is (= 404 s) "used up")
          camel (mk "USER" :key-format "camel")
          seen (p/then (as camel files/links shut) #(of-xid (:xid link) %))
          _ (is (= [user-xid nil false] [(:createdBy seen) (:maxDownloads seen) (contains? seen :created_by)]) "key-format camel")
          ci (as camel files/info shut)
          ui (as user files/info shut)
          _ (is (= (:content-type ui) (:contentType ci)))
          mine (as user files/links shut)
          _ (is (= 1 (:downloads (of-xid (:xid link) mine))))
          _ (is (not-any? :url mine) "the url is shown once")
          admins (as admin files/link shut)
          mine (as user files/links shut)
          _ (is (nil? (of-xid (:xid admins) mine)) "File User sees its own")
          all (as admin files/links shut)
          _ (is (of-xid (:xid link) all) "File Admin sees all")
          e (refused (as user files/unlink (:xid admins)))
          _ (is (= ["LINK_NOT_FOUND" "not_found"] ((juxt :code :category) e)))
          _ (as admin files/folder (:path F) {:can-link false})
          s (status-of (:url link))
          _ (is (= 404 s) "switched off: existing links stop")
          _ (as admin files/folder (:path F) {:can-link true})
          s (status-of (:url link))
          _ (is (= 200 s))
          u (as user files/unlink link)
          _ (is (nil? u))
          s (status-of (:url link))
          _ (is (= 404 s) "unlinked: gone at once")
          _ (as user files/delete shut)
          _ (swap! st dissoc :shut)
          s (status-of (:url admins))
          _ (is (= 404 s) "file deleted: its links die")
          back (as admin files/folder (:path F) {:public true :can-link nil})]
    (is (= {:public true :cache 120 :can-link nil} (:settings back)))))

(deftest files-live
  (if-not endpoint
    (do (println "SKIP files-live: set SYNTHIGY_FILES_TEST_ENDPOINT and the ADMIN/USER/READER client ids and secrets")
        (is true))
    (async done
      (let [admin (mk "ADMIN") user (mk "USER") reader (mk "READER")
            st (atom {})
            sname (str "sub-" run-id)
            srenamed (str "sub2-" run-id)]
        (-> (p/let [;; 1. admin creates F and shares it
                    F (as admin files/folder (str "/F-" run-id "/"))
                    _ (swap! st assoc :F F)
                    _ (is (= (str "F-" run-id) (:name F)))
                    uw (as user client/whoami)
                    rw (as reader client/whoami)
                    user-xid (:xid uw)
                    reader-xid (:xid rw)
                    _ (is (and user-xid reader-xid) "whoami gives both xids")
                    _ (as admin files/share F "write" {:users [user-xid]})
                    _ (as admin files/share F "read" {:users [user-xid]})
                    _ (as admin files/share F "delete" {:users [user-xid]})
                    _ (as admin files/share F "read" {:users [reader-xid]})
                    acl (as admin files/access F)
                    _ (doseq [a [:write :read :delete]]
                        (is (contains? (xids (get-in acl [a :users])) user-xid) (str "user holds " (name a))))
                    _ (is (contains? (xids (get-in acl [:read :users])) reader-xid))
                    _ (is (not (contains? (xids (get-in acl [:write :users])) reader-xid)))

                    ;; 2. user uploads and versions
                    {:keys [bin loose]} (uploads-step user F st)

                    ;; 3. user folders
                    S (as user files/folder (str (:path F) sname "/"))
                    _ (swap! st assoc :S S)
                    _ (is (= sname (:name S)))
                    S (as user files/move S (str (:path F) srenamed "/"))
                    _ (is (= srenamed (:name S)))
                    e (refused (as user files/move S (str "/" srenamed "/")))
                    _ (is (= ["FORBIDDEN" "iam"] ((juxt :code :category) e)) "File User cannot write ROOT")
                    e (refused (as user files/delete F))
                    _ (is (= ["FOLDER_NOT_EMPTY" "conflict"] ((juxt :code :category) e)))

                    ;; 4. reader
                    _ (reader-step reader user F bin loose)

                    ;; 5. links: File Admin makes F public
                    _ (links-step user admin F bin)

                    ;; 5b. download links
                    _ (download-links-step user admin reader F user-xid st)

                    ;; 6. admin moves the subfolder to ROOT
                    moved (as admin files/move S (str "/" srenamed "/"))
                    _ (is (= (:parent F) (:parent moved)) "a top folder's parent is ROOT")
                    _ (is (= (str "/" srenamed "/") (:path moved)))
                    gone (as admin files/delete moved)
                    _ (is (nil? gone))
                    _ (swap! st dissoc :S)

                    ;; 7. cleanup
                    _ (p/doseq [k [:bin :json :loose]]
                        (p/let [f (get @st k)
                                d (as user files/delete (:xid f))
                                after (as user files/info (:xid f))]
                          (is (nil? d))
                          (is (nil? after) (str (name k) " is gone"))
                          (swap! st dissoc k)))
                    d (as admin files/delete F)
                    _ (is (nil? d))
                    after (as admin files/list "/")
                    _ (is (not-any? #(= (:xid F) (:xid %)) (:folders after)))]
              (swap! st dissoc :F))
            (p/catch (fn [e] (is false (str "live files suite failed: " (ex-message e) " " (pr-str (ex-data e))))))
            (p/finally
              (fn []
                (let [{:keys [F S bin json loose shut]} @st
                      quiet #(p/catch % (constantly nil))]
                  (-> (p/doseq [f (remove nil? [bin json loose shut])]
                        (quiet (as user files/delete (:xid f))))
                      (p/then #(when S (quiet (as admin delete-folder-xid! (:xid S)))))
                      (p/then #(when F (quiet (as admin delete-folder-xid! (:xid F)))))
                      (p/finally (fn [] (set! core/*client* nil) (done))))))))))))
