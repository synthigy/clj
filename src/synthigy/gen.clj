(ns synthigy.gen
  "Code generator: a folder of .xsql → committed Clojure source.

   Connects to a running Synthigy backend ONCE (op:describe = the XSQL
   compiler) and emits one namespace per XSQL @namespace, with a named,
   documented function per operation. The generated code carries NO parser and
   needs NO backend at runtime — it embeds each op's compiled XSQL source string
   and sends it via synthigy.client. Connection is a BUILD-time dependency, like
   Prisma/sqlc/genqlient; commit the output and it runs offline forever after.

     clj -X:gen :dir '\"synthigy\"' :out '\"src\"' \\
                :ns-prefix myapp.ops :endpoint '\"http://localhost:7887\"'

   Auth for the pull: THE APP'S OWN client credentials (:client-id/:client-secret
   or SYNTHIGY_CLIENT_ID/SYNTHIGY_CLIENT_SECRET) — /schema + describe are
   IAM-filtered per principal, so generating as the app makes the generated
   contract exactly what the app can do at runtime (a personal/dev identity
   would generate a surface the app can't honor). Grant the client `schema:read`
   (or the broader `dataset:load`). :token / SYNTHIGY_TOKEN and authless remain
   for bare dev servers. Regenerate any time you edit an .xsql — the diff shows
   exactly what changed."
  (:require [synthigy.client :as c]
            [synthigy.client.core :as score]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private xsql-verbs
  "Verbs that become generated functions. Mutations (sync/stack/delete) stay the
   plain wire fallback — not generated. Must mirror `synthigy.xsql.program`'s
   `doc-verbs` (sdk/clj has no dependency on core/xsql, so it can't be
   imported — a verb added there must be added here too)."
  #{"search" "get" "search-tree" "get-tree" "slice" "purge" "sql-template"})

(def ^:private core-syms
  (set (map str (keys (ns-publics 'clojure.core)))))

(defn- kebab
  "camelCase / snake_case / spaced → kebab-case. Splits a lower/digit→upper
   boundary, then collapses whitespace/underscore runs to a single dash, then
   lowercases — matching the Go/JS/Python SDK kebab so an op named e.g.
   `musicAlbumList` generates `music-album-list`, not `musicalbumlist`."
  [s]
  (-> (str s)
      (str/replace #"([a-z0-9])([A-Z])" "$1-$2")
      (str/replace #"[\s_]+" "-")
      str/lower-case))

(defn- ns-seg
  "Clojure ns segment for an op — its @namespace, else its root entity."
  [op] (kebab (or (:namespace op) (:entity op))))

(defn- op-identity
  "Qualified `namespace/name` identity (lower-case) — the SAME (namespace,
   name) pair the lint enforces uniqueness on. Bare op names may repeat
   across entities (`movie/list`, `user_rating/list`)."
  [op]
  (str (str/lower-case (or (:namespace op) (:entity op) ""))
       "/" (str/lower-case (:name op))))

(defn- resolve-member
  "Resolve a `@batch` member ref — bare `name` (must be unique) or qualified
   `ns/name` — against `ops`. Throws on unknown/ambiguous."
  [ops batch-name ref]
  (let [[ns nm]  (if (str/includes? ref "/")
                   (let [i (str/index-of ref "/")]
                     [(str/lower-case (subs ref 0 i)) (subs ref (inc i))])
                   [nil ref])
        nm       (str/lower-case nm)
        matches  (filterv #(and (= nm (str/lower-case (:name %)))
                                (or (nil? ns)
                                    (= ns (str/lower-case (or (:namespace %) (:entity %) "")))))
                          ops)]
    (cond
      (empty? matches)
      (throw (ex-info (str "@batch '" batch-name "' references unknown/ungenerated op '" ref "'")
                      {:batch batch-name :member ref}))
      (and (nil? ns) (> (count matches) 1))
      (throw (ex-info (str "@batch '" batch-name "' — '" ref "' is ambiguous: "
                           (str/join ", " (map op-identity matches))
                           " — qualify the reference")
                      {:batch batch-name :member ref :candidates (mapv op-identity matches)}))
      :else (first matches))))

(defn- read-source [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile ^java.io.File %) (str/ends-with? (.getName ^java.io.File %) ".xsql")))
       (sort-by #(.getName ^java.io.File %))
       (map slurp)
       (str/join "\n\n")))

(defn- doc-for [op]
  (or (:description op)
      (str (:op op) " " (or (:namespace op) (:entity op))
           (when (seq (:params op))
             (str " — params: " (str/join ", " (map :name (:params op))))))))

(defn- emit-fn
  "One op → an op-map def + a defn. Params destructure by their exact (snake)
   wire names so the arglist documents them; all-optional params also get a
   0-param arity. Trailing kwargs (:acting-as, :key-format, …) forward to the
   underlying call. The `<name>-op` def is public (no-doc) so `@batch` fns —
   possibly in a sibling generated ns — can compose it."
  [op]
  (let [fname  (kebab (:name op))
        params (:params op)
        ;; STRICT wire: reads embed a full XSQL DOCUMENT (`@verb name` +
        ;; body) — sql-templates stay raw SQL; :entity rides for watches.
        opmap  (cond-> {:op (:op op)
                        :source (if (= "sql-template" (:op op))
                                  (:source op)
                                  (str "@" (:op op) " " (:name op) "\n" (:source op)))}
                 (:entity op) (assoc :entity (:entity op)))
        destr  (if (seq params)
                 (str "{:keys [" (str/join " " (map :name params)) "] :as params}")
                 "params")
        call   (str "(c/run-xsql " fname "-op params opts)")
        ;; `@watch` on the op → a live watch-<name> variant returning the
        ;; closeable watch atom. sql-templates declare their observed entities
        ;; in the directive (`@watch Movie, UserRating`) — pass them as the
        ;; mux interest; XSQL reads default to the op's root entity.
        watch  (:watch op)
        wopts  (if (sequential? watch)
                 (str "(merge {:interest-entities " (pr-str (vec watch)) "} opts)")
                 "opts")
        wcall  (str "(c/watch-xsql " fname "-op params " wopts ")")
        wfn    (when watch
                 (str "\n\n(defn watch-" fname "\n"
                      "  " (pr-str (str "Live " fname " — watch-query over the same op; returns the "
                                        "closeable atom (deref / add-watch / c/close-watch!). "
                                        "Accepts watch-query options (:acting-as :records "
                                        ":entity-track :track-rows :debounce-ms)."))
                      "\n"
                      (if (every? :optional params)
                        (str "  ([] (watch-" fname " {}))\n"
                             "  ([" destr " & {:as opts}]\n"
                             "   " wcall "))")
                        (str "  [" destr " & {:as opts}]\n"
                             "  " wcall ")"))))]
    (str "(def ^:no-doc " fname "-op\n  " (pr-str opmap) ")\n\n"
         "(defn " fname "\n"
         "  " (pr-str (doc-for op)) "\n"
         (if (every? :optional params)
           (str "  ([] (" fname " {}))\n"
                "  ([" destr " & {:as opts}]\n"
                "   " call "))")
           (str "  [" destr " & {:as opts}]\n"
                "  " call ")"))
         wfn)))

(defn- emit-batch
  "A `@batch <name>: <op> <op> …` → a defn that sends ALL member ops in ONE
   wire request and returns results keyed by member name (kebab keywords;
   members whose bare name repeats key by qualified `ns/name`). A failed
   member is an ExceptionInfo VALUE under its key (results->data)."
  [seg seg-of {:keys [name members]} resolve-ref]
  (let [fname  (kebab name)
        mops   (mapv resolve-ref members)
        opref  (fn [m]
                 (let [mseg (seg-of m) mf (kebab (:name m))]
                   (if (= mseg seg) (str mf "-op") (str mseg "/" mf "-op"))))
        params (distinct (mapcat :params mops))
        pnames (distinct (map :name params))
        destr  (if (seq pnames)
                 (str "{:keys [" (str/join " " pnames) "] :as params}")
                 "params")
        dup?   (->> mops (map (comp str/lower-case :name)) frequencies
                    (keep (fn [[n c]] (when (> c 1) n))) set)
        rkey   (fn [m] (str ":" (kebab (if (dup? (str/lower-case (:name m)))
                                         (op-identity m)
                                         (:name m)))))
        rkeys  (str "[" (str/join " " (map rkey mops)) "]")
        opsvec (str "[" (str/join "\n              "
                                  (map #(str "(score/op-xsql " (opref %) " params)") mops)) "]")
        body   (str "(let [ops " opsvec "]\n"
                    "     (zipmap " rkeys "\n"
                    "             (score/results->data (apply c/batch ops (mapcat identity opts)) ops)))")]
    (str "(defn " fname "\n"
         "  " (pr-str (str "Batch: " (str/join " + " members)
                           " — ONE wire request; results keyed by op name."))
         "\n"
         (if (every? :optional params)
           (str "  ([] (" fname " {}))\n"
                "  ([" destr " & {:as opts}]\n"
                "   " body "))")
           (str "  [" destr " & {:as opts}]\n"
                "  " body ")")))))

(defn- emit-ns [ns-prefix seg ops batches seg-of resolve-ref]
  (let [full-ns  (str ns-prefix "." seg)
        fnames   (concat (map (comp kebab :name) ops)
                         (map (comp kebab :name) batches))
        excludes (distinct (filter core-syms fnames))
        ;; sibling generated nss hosting cross-ns batch members
        foreign  (distinct
                  (for [b batches
                        m (:members b)
                        :let [mseg (seg-of (resolve-ref b m))]
                        :when (not= mseg seg)]
                    mseg))
        requires (str "  (:require [synthigy.client :as c]"
                      (when (seq batches)
                        "\n            [synthigy.client.core :as score]")
                      (apply str (for [f foreign]
                                   (str "\n            [" ns-prefix "." f " :as " f "]")))
                      "))")]
    (str ";; Generated by synthigy.gen — DO NOT EDIT.\n"
         ";; Regenerate: clj -X:gen  (re-describes against the backend)\n"
         "(ns " full-ns "\n"
         (when (seq excludes)
           (str "  (:refer-clojure :exclude [" (str/join " " excludes) "])\n"))
         requires "\n\n"
         (str/join "\n\n" (concat (map emit-fn ops)
                                  (map #(emit-batch seg seg-of % (partial resolve-ref %)) batches)))
         "\n")))

(defn- ns->path [out full-ns]
  (str out "/" (-> full-ns (str/replace "." "/") (str/replace "-" "_")) ".clj"))

(defn emit-files
  "Pure half of the generator: IR `operations` →
   `{:files {path {:content s :ops n :batches n}} :skipped [op …]}`.
   No I/O, no printing — `emit-all` writes it, `check` diffs it against disk."
  [operations {:keys [out ns-prefix] :or {out "src" ns-prefix "synthigy.ops"}}]
  (let [ops     (filter #(xsql-verbs (:op %)) operations)
        ;; batch entries carry no :op, so `op` truthy already excludes them
        skipped (filter #(and (:op %) (not (xsql-verbs (:op %)))) operations)
        batches (filter :batch operations)
        ;; identity = (namespace-or-entity, name) — the same pair the lint
        ;; enforces; duplicate QUALIFIED identities are a hard error, but bare
        ;; names may repeat across entities (movie/list, user_rating/list).
        _ (doseq [[id os] (group-by op-identity ops)]
            (when (> (count os) 1)
              (throw (ex-info (str "duplicate operation '" id
                                   "' — (namespace, name) must be unique")
                              {:identity id}))))
        member-of (fn [b ref] (resolve-member ops (:name b) ref))
        seg-of  ns-seg
        by-ns   (group-by ns-seg ops)
        ;; a batch lives in its FIRST member's namespace
        batches-by-ns (group-by #(seg-of (member-of % (first (:members %)))) batches)]
    {:skipped skipped
     :files (into (sorted-map)
                  (for [[seg seg-ops] by-ns
                        :let [seg-batches (get batches-by-ns seg [])]]
                    [(ns->path out (str ns-prefix "." seg))
                     {:content (emit-ns ns-prefix seg seg-ops seg-batches seg-of member-of)
                      :ops     (count seg-ops)
                      :batches (count seg-batches)}]))}))

(defn- report-skipped [skipped]
  ;; Never drop an op silently — say WHY it isn't a generated fn.
  (doseq [{:keys [op name entity]} skipped]
    (println (str "skipped @" op " " name
                  " — mutations aren't generated; call (c/" op " \""
                  (or entity "<entity>") "\" data) directly"))))

(defn emit-all
  "Emit generated namespaces from IR `operations` into `out` under `ns-prefix`.
   No network (drives the proof against a committed ops.ir.json too).
   Returns the seq of written paths."
  [operations opts]
  (let [{:keys [files skipped]} (emit-files operations opts)]
    (report-skipped skipped)
    (doall
     (for [[path {:keys [content ops batches]}] files]
       (do (io/make-parents path)
           (spit path content)
           (println "wrote" path (str "(" ops " ops"
                                      (when (pos? batches) (str ", " batches " batches")) ")"))
           path)))))

(defn- pull-client
  "The backend client for a pull, from opts + env. One identity for every call
   in a pull, so the IR and the schema snapshot describe the same principal."
  [{:keys [endpoint client-id client-secret token]}]
  (let [endpoint (or endpoint (System/getenv "SYNTHIGY_ENDPOINT") "http://localhost:7887")
        tok  (or token (System/getenv "SYNTHIGY_TOKEN"))
        cid  (or client-id (System/getenv "SYNTHIGY_CLIENT_ID"))
        csec (or client-secret (System/getenv "SYNTHIGY_CLIENT_SECRET"))]
    (score/create-client
     (cond-> {:endpoint endpoint}
       (and cid csec) (assoc :client-id cid :client-secret csec)
       (and tok (not (and cid csec))) (assoc :token tok)
       (and (not tok) (not (and cid csec))) (assoc :token "")))))

(defn- pull-ir
  "op:describe over `dir`'s .xsql against the backend → IR operations.
   Requires `score/*client*` to be bound."
  [{:keys [dir] :or {dir "synthigy"}}]
  (:operations (c/describe (read-source dir))))

(def schema-pretty
  "Cheshire printer tuned to `JSON.stringify(x, null, 2)` — byte-identical to
   what the JS and Go generators write, so a polyglot repo keeps one artifact.
   Cheshire's defaults differ: ` : ` between key and value, and inline arrays."
  (assoc json/default-pretty-print-options
         :indent-arrays? true
         :object-field-value-separator ": "))

(defn pull-schema!
  "GET /schema → `<dir>/schema.json`. Nothing at runtime reads this file; it is
   the snapshot XSQL editor tooling lints and completes against (`xsql-lint`,
   and the LSP when it lands) — see docs/plans/PLAN-XSQL-TOOLING.md. Same filename and
   same indentation the JS and Go generators write, so a polyglot repo gets one
   artifact rather than one per language. Requires `score/*client*` bound: the
   snapshot must describe the same principal the IR did."
  [{:keys [dir] :or {dir "synthigy"}}]
  (let [path   (io/file dir "schema.json")
        schema (c/schema)]
    (io/make-parents path)
    (spit path (str (json/generate-string schema {:pretty schema-pretty}) "\n"))
    (println "wrote" (str path) (str "(" (count (:entities schema)) " entities)"))
    path))

(defn generate
  "Pull IR from the backend (op:describe over `dir`'s .xsql) and emit source.
   Also refreshes `<dir>/schema.json` for editor tooling.
   Returns the seq of written paths."
  [opts]
  (binding [score/*client* (pull-client opts)]
    (let [operations (pull-ir opts)
          paths      (emit-all operations opts)
          ;; Editor artifact, not the deliverable — a /schema hiccup must not
          ;; fail a build whose generated source is already on disk.
          _ (try (pull-schema! opts)
                 (catch Exception e
                   (binding [*out* *err*]
                     (println "warning: schema.json not refreshed —"
                              (ex-message e)))))]
      (println "done —" (count (filter #(xsql-verbs (:op %)) operations)) "ops")
      paths)))

(defn check*
  "The offline half of `check`: diff `emit-files` output for `operations`
   against what's on disk. Returns `{:missing [..] :drifted [..] :stale [..]}`
   (all empty = in sync). `:stale` = .clj files under the ns-prefix subtree of
   `out` that the IR no longer produces (deleted ops leaving orphans)."
  [operations {:keys [out ns-prefix] :or {out "src" ns-prefix "synthigy.ops"} :as opts}]
  (let [{:keys [files skipped]} (emit-files operations opts)
        _        (report-skipped skipped)
        expected (set (keys files))
        gen-root (io/file (str out "/" (-> ns-prefix (str/replace "." "/") (str/replace "-" "_"))))
        on-disk  (when (.isDirectory gen-root)
                   (->> (file-seq gen-root)
                        (filter #(and (.isFile ^java.io.File %)
                                      (str/ends-with? (.getName ^java.io.File %) ".clj")))
                        (map #(.getPath ^java.io.File %))))]
    {:missing (vec (remove #(.exists (io/file %)) expected))
     :drifted (vec (for [[path {:keys [content]}] files
                         :when (and (.exists (io/file path))
                                    (not= content (slurp path)))]
                     path))
     :stale   (vec (remove expected on-disk))}))

(defn check
  "CI gate: pull IR and verify the committed files under `out` match what
   `generate` would write. Prints each missing/drifted/stale file and throws
   (non-zero exit under -X) when anything is out of sync.

     clj -X:gen-check :dir '\"synthigy\"' :out '\"src\"' :ns-prefix myapp.ops"
  [opts]
  ;; No `pull-schema!` here — `check` is a read-only drift gate.
  (let [operations (binding [score/*client* (pull-client opts)] (pull-ir opts))
        {:keys [missing drifted stale] :as report} (check* operations opts)]
    (doseq [p missing] (println "missing:" p))
    (doseq [p drifted] (println "drifted:" p))
    (doseq [p stale]   (println "stale:  " p "(no op produces it — delete it)"))
    (if (every? empty? [missing drifted stale])
      (println "ok — generated code in sync")
      (throw (ex-info "generated code out of sync — run: clj -X:gen" report)))))

(defn watch!
  "Dev loop for user.clj: poll `dir` for .xsql content changes (also fires once
   on start); on change re-describe against the backend, rewrite `out`, and
   `load-file` every written file so the running REPL picks the new fns up
   immediately. Backend errors don't kill the loop — it reports once per
   distinct error and retries each poll until the regen succeeds. Returns a
   0-arg stop fn.

     (defonce stop-gen
       (gen/watch! {:dir \"synthigy\" :out \"src\" :ns-prefix \"myapp.ops\"}))"
  [{:keys [interval-ms] :or {interval-ms 500} :as opts}]
  (let [running  (atom true)
        done-src (atom ::none)   ; last successfully generated source
        last-err (atom nil)
        step!    (fn []
                   (let [src (try (read-source (:dir opts "synthigy"))
                                  (catch Exception _ nil))]
                     (when (and src (not= src @done-src))
                       (try
                         (let [paths (generate opts)]
                           (run! load-file paths)
                           (println "[gen/watch] reloaded" (count paths) "namespaces")
                           (reset! done-src src)
                           (reset! last-err nil))
                         (catch Exception e
                           (let [msg (ex-message e)]
                             (when (not= msg @last-err)
                               (println "[gen/watch] regen failed (retrying):" msg)
                               (reset! last-err msg))))))))]
    (doto (Thread. ^Runnable
                   (fn []
                     (while @running
                       (step!)
                       (Thread/sleep (long interval-ms))))
                   "synthigy.gen/watch!")
      (.setDaemon true)
      (.start))
    (fn stop! [] (reset! running false))))
