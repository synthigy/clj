(ns synthigy.client.auth-supervised-test
  "Contract test for the supervised-stdio token source — spawns a `bb` child
   (cheapest way to get `synthigy.client.auth` on a classpath — bb.edn's
   [\"src\"] path is discovered from the inherited cwd) under a stub parent
   speaking `auth.token`.
   The child prints its result to STDERR as EDN so the test's own
   bookkeeping never collides with the protocol channel it's exercising —
   stdout is exclusively `auth.token` frames.

   Run: clj -M:test (needs `bb` on PATH)."
  (:require
   [babashka.json :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]])
  (:import
   [com.sun.net.httpserver HttpServer HttpExchange HttpHandler]
   [java.io File]
   [java.net InetSocketAddress]
   [java.util.concurrent TimeUnit]))


(defn- spawn-child!
  "Write `source` to a temp .clj file and run it under `bb`, inheriting this
   process's cwd (sdk/clj — where bb.edn lives) so `synthigy.client.auth` is
   on the child's classpath. SYNTHIGY_SUPERVISED=1, no other auth env unless
   `extra-env` adds it back."
  ([source] (spawn-child! source nil))
  ([source extra-env]
   (let [f (doto (File/createTempFile "synthigy-auth-supervised" ".clj")
             (.deleteOnExit))]
     (spit f source)
     (let [pb (ProcessBuilder. ^"[Ljava.lang.String;"
                               (into-array String ["bb" (.getAbsolutePath f)]))
           env (.environment pb)]
       (doseq [k ["SYNTHIGY_TOKEN" "SYNTHIGY_CLIENT_ID" "SYNTHIGY_CLIENT_SECRET"]]
         (.remove env k))
       (.put env "SYNTHIGY_SUPERVISED" "1")
       (doseq [[k v] extra-env] (.put env k v))
       (.start pb)))))

(defn- out-reader [^Process p] (io/reader (.getInputStream p)))
(defn- err-reader [^Process p] (io/reader (.getErrorStream p)))
(defn- in-writer [^Process p] (io/writer (.getOutputStream p)))

(defn- write-line! [w s]
  (doto w (.write (str s "\n")) .flush))

(defn- read-result!
  "Read the child's EDN result line off stderr, skipping anything bb itself
   prints there first (warnings, blank lines). EOF without a result surfaces
   the whole stderr so the failure says what the child actually did."
  [proc]
  (let [r (err-reader proc)]
    (loop [noise []]
      (let [line (.readLine r)]
        (cond
          (nil? line) {:ok false :error "child exited without a result" :stderr noise}
          (.startsWith (.trim ^String line) "{") (edn/read-string line)
          :else (recur (conj noise line)))))))


(def ^:private child-ask-once
  "(require '[synthigy.client.auth :as auth])
   (let [src (auth/supervised)]
     (try
       (let [token ((:token-fn src))]
         (binding [*out* *err*] (prn {:ok true :token token})))
       (catch Exception e
         (binding [*out* *err*]
           (prn {:ok false :error (ex-message e) :code (:code (ex-data e))})))))")


(deftest asks-and-receives-token-test
  (let [proc (spawn-child! child-ask-once)
        out (out-reader proc)
        in (in-writer proc)]
    (try
      (let [frame-line (.readLine out)]
        (is frame-line "child never wrote a frame to stdout")
        (let [frame (json/read-str frame-line {:key-fn keyword})]
          (is (= "2.0" (:jsonrpc frame)))
          (is (= "auth.token" (:method frame)))
          (is (:id frame))
          (write-line! in (json/write-str
                            {:jsonrpc "2.0" :id (:id frame)
                             :result {:token "supervised-token-abc" :expires_in 300}}))
          (let [result (read-result! proc)]
            (is (:ok result) result)
            (is (= "supervised-token-abc" (:token result))))))
      (finally
        (.close in)
        (.waitFor proc 5 TimeUnit/SECONDS)))))


(deftest stray-and-mismatched-lines-are-ignored-test
  (testing "non-frame noise and a response for a DIFFERENT request id must
            not corrupt dispatch of the real response"
    (let [proc (spawn-child! child-ask-once)
          out (out-reader proc)
          in (in-writer proc)]
      (try
        (let [frame (json/read-str (.readLine out) {:key-fn keyword})]
          (write-line! in "not json at all")
          (write-line! in (json/write-str
                            {:jsonrpc "2.0" :id (+ (:id frame) 999)
                             :result {:token "wrong-request"}}))
          (write-line! in (json/write-str
                            {:jsonrpc "2.0" :id (:id frame)
                             :result {:token "right-token" :expires_in 300}}))
          (let [result (read-result! proc)]
            (is (:ok result) result)
            (is (= "right-token" (:token result)))))
        (finally
          (.close in)
          (.waitFor proc 5 TimeUnit/SECONDS))))))


(deftest timeout-falls-through-to-teaching-throw-test
  (testing "a hung or missing parent must not hang the bot — see step 3"
    (let [proc (spawn-child! child-ask-once)
          out (out-reader proc)
          in (in-writer proc)]
      (try
        (is (.readLine out))
        ;; Never respond — the SDK's ~5s ask timeout fires.
        (let [result (read-result! proc)]
          (is (false? (:ok result)))
          (is (= "NO_TOKEN" (:code result))))
        (finally
          (.close in)
          (.waitFor proc 10 TimeUnit/SECONDS))))))


(def ^:private child-ask-twice
  "(require '[synthigy.client.auth :as auth])
   (let [src (auth/supervised)]
     (try
       (let [t1 ((:token-fn src) \"aud-a\")
             t2 ((:token-fn src) \"aud-b\")]
         (binding [*out* *err*] (prn {:ok true :tokens [t1 t2]})))
       (catch Exception e
         (binding [*out* *err*]
           (prn {:ok false :error (ex-message e) :code (:code (ex-data e))})))))")


(deftest malformed-line-does-not-kill-the-reader-test
  (testing "a garbage line between two independent asks must not take the
            background dispatcher down with it — the second ask still has
            to work, or every future request in this process hangs forever"
    (let [proc (spawn-child! child-ask-twice)
          out (out-reader proc)
          in (in-writer proc)]
      (try
        (let [frame1 (json/read-str (.readLine out) {:key-fn keyword})]
          (write-line! in "ÿ not even valid json {")
          (write-line! in "{ this looks like a frame but isn't valid json")
          (write-line! in (json/write-str
                            {:jsonrpc "2.0" :id (:id frame1)
                             :result {:token "first-token" :expires_in 300}}))
          (let [frame2 (json/read-str (.readLine out) {:key-fn keyword})]
            (is (not= (:id frame2) (:id frame1)))
            (write-line! in (json/write-str
                              {:jsonrpc "2.0" :id (:id frame2)
                               :result {:token "second-token" :expires_in 300}}))
            (let [result (read-result! proc)]
              (is (:ok result) result)
              (is (= ["first-token" "second-token"] (:tokens result))))))
        (finally
          (.close in)
          (.waitFor proc 5 TimeUnit/SECONDS))))))


(def ^:private child-create-client-token
  "(require '[synthigy.client.core :as core])
   (let [c (core/create-client {:endpoint \"http://unused.invalid\"})]
     (try
       (let [token ((:token-fn c))]
         (binding [*out* *err*] (prn {:ok true :token token})))
       (catch Exception e
         (binding [*out* *err*]
           (prn {:ok false :error (ex-message e) :code (:code (ex-data e))})))))")

(deftest pipe-beats-env-token-when-supervised-test
  (testing "SYNTHIGY_TOKEN in env AND SYNTHIGY_SUPERVISED=1: create-client
            installs the pipe, not the env snapshot — only the pipe can
            refresh mid-run"
    (let [proc (spawn-child! child-create-client-token
                             {"SYNTHIGY_TOKEN" "stale-env-snapshot"})
          out (out-reader proc)
          in (in-writer proc)]
      (try
        (let [frame-line (.readLine out)]
          (is frame-line "supervised child must ask the pipe even with SYNTHIGY_TOKEN set")
          (let [frame (json/read-str frame-line {:key-fn keyword})]
            (write-line! in (json/write-str
                              {:jsonrpc "2.0" :id (:id frame)
                               :result {:token "fresh-pipe-token" :expires_in 300}}))
            (let [result (read-result! proc)]
              (is (:ok result) result)
              (is (= "fresh-pipe-token" (:token result))))))
        (finally
          (.close in)
          (.waitFor proc 5 TimeUnit/SECONDS))))))


;; ============================================================================
;; End-to-end: real /data 401 -> clear -> reask -> retry, against a stub
;; HTTP server AND a real supervised-stdio parent, driving the SDK's actual
;; `synthigy.client/search` — proves `invalidate-fn` is wired into the
;; existing 401 clear+retry-once path (http.clj `authed`), not just present.
;; ============================================================================

(defn- start-stub-401-once!
  "HTTP server: first POST -> 401, every POST after -> 200 with an empty
   search result. Returns [server auth-headers-atom]."
  []
  (let [headers (atom [])
        server (HttpServer/create (InetSocketAddress. "127.0.0.1" 0) 0)]
    (.createContext
     server "/data"
     (reify HttpHandler
       (handle [_ exchange]
         (let [^HttpExchange exchange exchange]
           (with-open [is (.getRequestBody exchange)]
             (.readAllBytes is))
           (swap! headers conj (.getFirst (.getRequestHeaders exchange) "Authorization"))
           (let [first? (= 1 (count @headers))
                 body (if first?
                        (json/write-str {:error {:message "Unauthorized" :code "UNAUTHORIZED"}})
                        (json/write-str {:results [{:ok true :data []}]}))
                 bytes (.getBytes ^String body "UTF-8")]
             (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
             (.sendResponseHeaders exchange (if first? 401 200) (count bytes))
             (with-open [os (.getResponseBody exchange)]
               (.write os bytes)))))))
    (.setExecutor server nil)
    (.start server)
    [server headers]))

(def ^:private child-search-once
  "(require '[synthigy.client :as client])
   (client/connect! {:endpoint (System/getenv \"CHILD_ENDPOINT\")})
   (try
     (client/search :user nil nil)
     (binding [*out* *err*] (prn {:ok true}))
     (catch Exception e
       (binding [*out* *err*]
         (prn {:ok false :error (ex-message e) :code (:code (ex-data e))}))))")

(deftest full-401-clear-and-reask-lifecycle-test
  (testing "the full lifecycle a real bot exercises: ask, use, get 401
            (token revoked/invalid despite our clock saying it's fresh),
            clear, ask again, retry succeeds with the NEW token"
    (let [[server headers] (start-stub-401-once!)
          port (.getPort (.getAddress server))
          endpoint (str "http://127.0.0.1:" port)
          f (doto (File/createTempFile "synthigy-auth-supervised-e2e" ".clj")
              (.deleteOnExit))
          _ (spit f child-search-once)
          pb (ProcessBuilder. ^"[Ljava.lang.String;"
                              (into-array String ["bb" (.getAbsolutePath f)]))
          env (.environment pb)
          _ (doseq [k ["SYNTHIGY_TOKEN" "SYNTHIGY_CLIENT_ID" "SYNTHIGY_CLIENT_SECRET"]]
              (.remove env k))
          _ (.put env "SYNTHIGY_SUPERVISED" "1")
          _ (.put env "CHILD_ENDPOINT" endpoint)
          proc (.start pb)
          out (out-reader proc)
          in (in-writer proc)]
      (try
        (let [frame1 (json/read-str (.readLine out) {:key-fn keyword})]
          (write-line! in (json/write-str
                            {:jsonrpc "2.0" :id (:id frame1)
                             :result {:token "stale-token" :expires_in 300}}))
          (let [frame2 (json/read-str (.readLine out) {:key-fn keyword})]
            (is (not= (:id frame2) (:id frame1))
                "the 401 must trigger a SECOND, independent ask")
            (write-line! in (json/write-str
                              {:jsonrpc "2.0" :id (:id frame2)
                               :result {:token "fresh-token" :expires_in 300}}))
            (let [result (read-result! proc)]
              (is (:ok result) result)
              (is (= ["Bearer stale-token" "Bearer fresh-token"] @headers)))))
        (finally
          (.close in)
          (.waitFor proc 5 TimeUnit/SECONDS)
          (.stop server 0))))))
