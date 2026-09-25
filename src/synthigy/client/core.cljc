(ns synthigy.client.core
  "Pure core of the Synthigy client — everything that is genuinely
   platform-free: the client map (construction + the connected `*client*`),
   wire operation builders, result unpacking, tree composition, and the
   subscription wire shapes. No transport here; `synthigy.client` (.clj /
   .cljs) owns the verbs."
  (:require
   [synthigy.client.error :as err]
   [clojure.string :as str]
   [synthigy.client.selection :as selection]
   [synthigy.client.auth :as auth]))

(def platform-audience
  "The platform API this SDK is a client of. `/data`, `/schema`, `/history`,
   `/logs` and subscriptions all require a token bound to it. It names the API,
   never a deployment, so it is the same string on localhost and in production
   — which is why it is a constant rather than something every caller
   configures. Minting a token for some OTHER API is a per-call argument."
  "https://synthigy.com")

(def ^:dynamic *client*
  "The connected client — set once via `synthigy.client/connect!`; every API
   fn uses it. Rebind with `binding` for a scoped alternate client (tests, a
   second endpoint) — SDK consumers talk to ONE Synthigy, so it is not a
   parameter."
  nil)

(defn ^:no-doc the-client
  "The connected client, or a loud NOT_CONNECTED error."
  []
  (or *client*
      (throw (err/ex-info "Not connected — call (synthigy.client/connect! {:endpoint … :client-id …}) first"
                      {:code "NOT_CONNECTED"}))))

(defn create-client
  "Create a Synthigy client.

  The client is just a plain map. Auth is supplied via a provider — a map
  `{:token-fn f :invalidate-fn g}` — with three ways to supply it:

    1. Explicit provider:
         (create-client (merge {:endpoint \"...\"}
                               (auth/oauth {:token-url ... :client-id ...})))

    2. Direct fn handles (full control, bring your own storage):
         (create-client {:endpoint \"...\"
                         :token-fn #(@my-cache)
                         :invalidate-fn #(reset! my-cache nil)})

    3. Convenience shortcuts (CLJ only — SDK builds the provider for you):
         :token \"eyJ...\"              → auth/static
         :client-id + :client-secret   → auth/oauth

  Options:
    :endpoint       — Server URL (e.g. \"http://localhost:7887\"); default $SYNTHIGY_ENDPOINT
    :token-fn       — 0-arg fn returning a bearer token. Optional 1-arg
                      variant `(f audience)` for IdP federation.
    :invalidate-fn  — Optional 0/1-arg fn clearing the provider's cache.
                      Used by the SDK to retry once on HTTP 401.
    :token          — (convenience) static bearer token
    :client-id      — (convenience) OAuth client id
    :client-secret  — (convenience) OAuth client secret
                      — JVM only: with none of the above, falls back to
                      SYNTHIGY_SUPERVISED=1 stdio (the pipe beats the env
                      var — it can refresh mid-run), then SYNTHIGY_TOKEN env;
                      else throws
                      {:code \"NO_TOKEN\"}. CLJS always requires a source.
    :audience       — (convenience) default audience bound to every
                      client_credentials mint. The platform's audience model
                      is opt-in: a mint naming none resolves to the
                      identity-only OIDC audience that /data rejects. The
                      server publishes its /data audience at
                      /.well-known/synthigy as auth.oidc.audience. JVM
                      default: $SYNTHIGY_AUDIENCE.
    :token-url      — (convenience) OAuth token URL — defaults to
                      \"<endpoint>/oauth/token\"
    :token-buffer   — (convenience) seconds before expiry to refresh (default 30)
    :key-format     — response key format. Default \"kebab\" — Clojure devs
                      write :published-on, so responses match. Pass nil for
                      the raw wire snake_case, or \"camel\".
    :request-timeout — HTTP timeout in ms for /data and /schema requests
                       (default 30000). SSE streams ignore this.
    :on-request     — (fn [req]) before each HTTP attempt.
                      req: {:method :url :headers :body}
    :on-response    — (fn [req resp]) after each HTTP attempt.
                      resp: {:status :headers :body :elapsed-ms}
    :on-error       — (fn [req ^Throwable]) when a request throws.

                      Hooks fire per attempt — a 401 retry fires them twice.
                      Hook exceptions are caught + logged to *err*, never
                      bubble into the caller.
    :login-store    — (JVM) in-flight browser logins for `login-start` /
                      `login-complete`: {:put-fn :take-fn}, see
                      `synthigy.client.login`. Unset = login throws NO_LOGIN_STORE."
  [{:keys [endpoint key-format request-timeout
           token-fn invalidate-fn
           token client-id client-secret token-url token-buffer audience
           on-request on-response on-error login-store]
    :or {request-timeout 30000 key-format "kebab"}
    :as opts}]
  (let [known #{:endpoint :key-format :request-timeout
                :token-fn :invalidate-fn
                :token :client-id :client-secret :token-url :token-buffer
                :audience :on-request :on-response :on-error :login-store}
        unknown (seq (remove known (keys opts)))]
    (when unknown
      (throw (err/ex-info (str "create-client: unknown option(s) " (pr-str unknown))
                      {:code "CONFIG_ERROR" :unknown unknown :known known}))))
  (let [endpoint (or endpoint
                     #?(:clj (System/getenv "SYNTHIGY_ENDPOINT") :cljs nil)
                     (throw (err/ex-info #?(:clj "create-client requires :endpoint (or SYNTHIGY_ENDPOINT — run under `synthigy exec`)"
                                            :cljs "create-client requires :endpoint")
                                         {:code "NO_ENDPOINT"})))
        env-token #?(:clj (System/getenv "SYNTHIGY_TOKEN") :cljs nil)
        supervised? #?(:clj (= "1" (System/getenv "SYNTHIGY_SUPERVISED")) :cljs false)
        provider (cond
                   token-fn {:token-fn token-fn
                             :invalidate-fn invalidate-fn}
                   token
                   (auth/static token)
                   (and client-id client-secret)
                   (auth/oauth
                    (cond-> {:token-url (or token-url (str endpoint "/oauth/token"))
                             :client-id client-id
                             :client-secret client-secret}
                      token-buffer (assoc :token-buffer token-buffer)
                      ;; Always bound: this SDK is the client for the platform
                      ;; API, so that is what it mints for. Nothing for a caller
                      ;; to configure, and nowhere to look the value up if there
                      ;; were — the server does not advertise it in discovery.
                      :always (assoc :audience
                                     (or audience
                                         #?(:clj (System/getenv "SYNTHIGY_AUDIENCE")
                                            :cljs nil)
                                         platform-audience))))
                   ;; The pipe beats the env var: exec injects the cached
                   ;; token AND supervises; only the pipe refreshes mid-run.
                   supervised? #?(:clj (auth/supervised) :cljs nil)
                   env-token (auth/static env-token)
                   :else (throw #?(:clj (auth/no-token-error)
                                   :cljs (err/ex-info "create-client requires :token-fn, :token, or :client-id + :client-secret"
                                                  {:code "CONFIG_ERROR"}))))]
    (merge {:endpoint endpoint
            :key-format key-format
            :request-timeout request-timeout
            ;; Client-side mirror of the server's subscription set. The
            ;; /data/subscription/set endpoint is full-replace, so every
            ;; subscribe/unsubscribe rebuilds and re-POSTs the whole set.
            :subs (atom {:data {} :entities #{} :relations #{} :models #{}})
            ;; Watch multiplexer: ONE SSE stream + ONE consolidated
            ;; subscription shared by all watch/watch-query.
            :mux (atom {:watches {}})}
           (cond-> {}
             client-id (assoc :client-id client-id)
             ;; behind a fn so a printed client map never shows the secret
             client-secret (assoc :client-secret-fn (constantly client-secret))
             login-store (assoc :login-store login-store)
             on-request (assoc :on-request on-request)
             on-response (assoc :on-response on-response)
             on-error (assoc :on-error on-error))
           provider)))


(defn ^:no-doc build-request
  "Build the /data request body. Per-call `:key-format` wins over the
   client-level default."
  [client operations {:keys [acting-as key-format]}]
  (let [kf (or key-format (:key-format client))]
    (cond-> {:operations operations}
      kf (assoc :key_format (if (keyword? kf) (name kf) kf))
      acting-as (assoc :acting_as acting-as))))


;; ============================================================================
;; Tree Composition — nest flat get-tree/search-tree results
;; ============================================================================

(defn- record-id
  "Extract a record's canonical id. Tries :xid, :euuid, :_eid and their
   kebab/snake variants so the helpers stay case-agnostic."
  [record]
  (some #(clojure.core/get record %) [:xid :euuid :_eid "xid" "euuid" "_eid"]))

(defn- parent-id
  "Read the parent id off a record for a given FK relation `on`.
   Accepts either a nested record (`{:father {:xid \"...\"}}`) or a bare
   id value. Tries kebab / snake casings of the relation name."
  [record on]
  (let [k (if (keyword? on) on (keyword on))
        snake (keyword (clojure.string/replace (name k) \- \_))
        kebab (keyword (clojure.string/replace (name k) \_ \-))
        v (or (clojure.core/get record k)
              (and (not= k snake) (clojure.core/get record snake))
              (and (not= k kebab) (clojure.core/get record kebab)))]
    (cond
      (nil? v) nil
      (map? v) (record-id v)
      :else v)))

(defn- tree-build-indexes
  "Two-pass indexing: record-by-id + parent->children-ids. Using id-level
   links avoids the snapshot bug where mutating a child's children after
   it's already been copied into a parent leaves stale data in the parent."
  [records on]
  (reduce
    (fn [[by-id kids] r]
      (let [id (record-id r)
            pid (parent-id r on)]
        (cond
          (nil? id) [by-id kids]
          (and pid (not= id pid))
          [(assoc by-id id r)
           (update kids pid (fnil conj []) id)]
          :else
          [(assoc by-id id r) kids])))
    [{} {}]
    records))

(defn- build-subtree
  "Recursively build a subtree rooted at `id`. `visited` is a set of ids
   currently on the recursion path; revisiting one means a cycle and we
   bail out (returns nil so the caller can filter it)."
  [by-id kids children-key id visited]
  (when-not (contains? visited id)
    (let [visited' (conj visited id)
          record (clojure.core/get by-id id)]
      (assoc record
             children-key
             (into []
                   (keep #(build-subtree by-id kids children-key % visited'))
                   (clojure.core/get kids id []))))))

(defn compose-tree
  "Compose a flat list of records into a single nested tree rooted at
   `root-id`. Requires each record to carry its parent FK (include the
   relation in your selection).

     (compose-tree records {:on :father :root-id howard-xid})
     ;; => {:xid :first_name :children [{:xid :first_name :children [...]}]}

   Options:
     :on            — relation name (keyword or string) used to walk the tree
     :root-id       — root record's id; defaults to first record's id
     :children-key  — nesting key for children (default :children)"
  [records {:keys [on root-id children-key]
            :or   {children-key :children}}]
  (when (and (seq records) on)
    (let [[by-id kids] (tree-build-indexes records on)
          root (or root-id (record-id (first records)))]
      (when (contains? by-id root)
        (build-subtree by-id kids children-key root #{})))))

(defn compose-forest
  "Compose a flat list into a forest — vector of trees, each rooted at a
   record whose parent isn't present in the set. Useful for search-tree
   results where multiple independent ancestor chains come back."
  [records {:keys [on children-key]
            :or   {children-key :children}}]
  (when (and (seq records) on)
    (let [[by-id kids] (tree-build-indexes records on)
          roots (keep (fn [r]
                        (let [id (record-id r)
                              pid (parent-id r on)]
                          (when (or (nil? pid)
                                    (not (contains? by-id pid))
                                    (= id pid))
                            id)))
                      records)]
      (mapv #(build-subtree by-id kids children-key % #{}) roots))))

;; ============================================================================
;; Result Helpers
;; ============================================================================

(defn ok?
  "Returns true if a value is not an error."
  [v]
  (not (instance? #?(:clj Throwable :cljs js/Error) v)))


(defn all-ok?
  "Returns true if all values are successful (no exceptions)."
  [vs]
  (every? ok? vs))


(defn errors
  "Filter exceptions from a data vector."
  [vs]
  (filterv (complement ok?) vs))


(defn ^:no-doc ->result
  "Unpack one wire result `{:ok :data :error}` into either its data (on ok)
   or an ExceptionInfo carrying the error + originating operation. The one
   canonical error-shaping — `results->data` maps it over a batch, and the
   platform clients' single-op verbs reuse it (throwing the ex-info it
   returns) so the error shape stays identical across batch and single ops."
  [{:keys [ok data error]} operation]
  (if ok
    data
    (err/ex-info (or (:message error) "Operation failed")
             (merge {:code (:code error)} error
                    (when operation {:operation operation})))))


(defn results->data
  "Extract data from batch results as a vector for destructuring.
  Successful operations return their data, failed operations return
  ExceptionInfo carrying the error and original operation.

    (let [[synced users roles] (results->data results ops)]
      (when (all-ok? [synced users roles])
        (println synced users roles)))"
  ([results]
   (mapv #(->result % nil) results))
  ([results operations]
   (mapv ->result results operations)))


;; ============================================================================
;; Operation Builders
;; ============================================================================
;;
;; KEY NORMALIZATION — `args`/`data` keys are NOT normalized here; `selections`
;; keys ARE (via selection/normalize). This split matches what the server
;; actually does:
;;   - where/args keys AND top-level data keys → the server's `normalize-name`
;;     resolves them to columns/operators (dash-aware: `release-on`→`release_on`,
;;     `:-where`→`:_where`), so client normalization is redundant. Crucially it
;;     leaves opaque jsonb VALUES untouched — whereas a client-side deep
;;     normalize mangled their inner keys (`{:someKey 1}`→`{:some_key 1}`),
;;     silently corrupting stored JSON. Passing args/data through raw fixes that.
;;   - selection keys → the server does NOT normalize the projection path
;;     (kebab `release-on` in a selection errors), so we snake-case those here.
;; camelCase attribute keys are no longer split (the server lowercases without
;; splitting, e.g. `defaultBranch`→`defaultbranch`); write kebab, the CLJ idiom.

(defn op-search
  "Build a search operation for batch use."
  [entity args selection]
  {:op "search" :entity (name entity)
   :args args
   :selections (selection/normalize selection)})

(defn op-get
  "Build a get operation for batch use."
  [entity args selection]
  {:op "get" :entity (name entity)
   :args args
   :selections (selection/normalize selection)})

(def ^:private base58-alphabet
  "123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")

(defn- hex-byte
  [pair]
  #?(:clj (Integer/parseInt (apply str pair) 16)
     :cljs (js/parseInt (apply str pair) 16)))

(defn- bytes->base58
  "Big-endian byte seq -> base58 string, schoolbook base-256->base-58
   conversion (the same algorithm Bitcoin-style base58 libraries use).
   Portable: only plain +/*/mod/quot on ordinary ints, safely inside every
   platform's integer range for 16 input bytes."
  [bs]
  (let [digits (atom [0])]
    (doseq [b bs]
      (let [carry (atom b)]
        (dotimes [i (count @digits)]
          (let [x (+ (* (nth @digits i) 256) @carry)]
            (swap! digits assoc i (mod x 58))
            (reset! carry (quot x 58))))
        (while (pos? @carry)
          (swap! digits conj (mod @carry 58))
          (swap! carry quot 58))))
    (apply str (map #(nth base58-alphabet %) (reverse @digits)))))

(defn new-xid
  "A fresh 22-char Base58 xid — client-minted identity for sync/stack, the
   same derivation the server uses (id/uuid->nanoid: UUID bytes -> base58,
   left-padded with '1'). Verified byte-for-byte against the server's
   algorithm across 2000 random UUIDs.

   Mint one before a write to know a record's id up front, or to make a
   retried write idempotent — the server accepts a caller-supplied id
   as-is, and the alternative (`returning: true`) costs the full echo on
   every write."
  []
  (let [hex (str/replace (str (random-uuid)) "-" "")
        bs  (mapv hex-byte (partition 2 hex))
        s   (bytes->base58 bs)
        pad (- 22 (count s))]
    (str (apply str (repeat (max 0 pad) \1)) s)))

(defn op-sync
  "Build a sync (upsert) operation for batch use; `data` is one record or a
   vector of them. The server answers
   {:count n}; pass `returning` true for the written records. Mint ids with
   `new-xid` when you need them up front — that is the cheap way to know
   what you wrote."
  ([entity data] (op-sync entity data false))
  ([entity data returning]
   {:op "sync" :entity (name entity)
    :data data :returning (boolean returning)}))

(defn op-stack
  "Build a stack operation for batch use, one record or a vector. Same
   `returning` contract as op-sync."
  ([entity data] (op-stack entity data false))
  ([entity data returning]
   {:op "stack" :entity (name entity)
    :data data :returning (boolean returning)}))

(defn op-delete
  "Build a delete operation for batch use."
  [entity data]
  {:op "delete" :entity (name entity)
   :data data})

(defn op-slice
  "Build a slice operation for batch use. The selection names link-sets
   to cut; the server ignores `:_join` on slice targeting."
  [entity args selection]
  {:op "slice" :entity (name entity)
   :args args
   :selections (selection/normalize selection)})

(defn op-purge
  "Build a purge operation for batch use."
  [entity args selection]
  {:op "purge" :entity (name entity)
   :args args
   :selections (selection/normalize selection)})

(defn op-sql-template
  "Build a SQL template operation (ERD-aware analytics) for batch use."
  [template params & {:keys [cached] :or {cached true}}]
  {:op "sql-template" :template template :params params :cached cached})

(defn op-search-tree
  "Build a search-tree operation (walk :on relation UP to ancestors)."
  [entity on args selection]
  {:op "search-tree" :entity (name entity) :on (name on)
   :args args
   :selections (selection/normalize selection)})

(defn op-get-tree
  "Build a get-tree operation (from root, return root + descendants via :on)."
  [entity root on selection]
  {:op "get-tree" :entity (name entity) :root root :on (name on)
   :selections (selection/normalize selection)})

(defn op-deploy
  "Build a deploy operation — export-contents travels to the server verbatim."
  [export-contents]
  {:op "deploy" :data export-contents})

(defn op-destroy
  "Build a destroy operation: delete on the dataset meta-entity by xid."
  [dataset-xid]
  {:op "delete" :entity "dataset" :data {:xid dataset-xid}})

(defn op-deployed-model
  "Build a deployed-model operation (raw ERD model as deployed)."
  [] {:op "deployed-model"})

(defn op-runtime-model
  "Build a runtime-model operation (deployed model + identity/audit/ref
   augmentation)."
  [] {:op "runtime-model"})

(defn xsql-document
  "An XSQL operation document: a source already starting with `@` keeps its
   verb; a bare body gets an `@<op> _q` header."
  [source op]
  (if (str/starts-with? (str/triml source) "@")
    source
    (str "@" (name op) " _q\n" source)))

(defn ^:no-doc op-xsql
  "Wire operation for a generated/compiled XSQL op map `{:op :source :entity}`
   plus a params map — the batch-composable form of `synthigy.client/run-xsql`.
   Used by generated `@batch` functions."
  [{:keys [op source]} params]
  (if (= op "sql-template")
    (cond-> {:op "sql-template" :template source :cached true}
      (seq params) (assoc :params params))
    (cond-> {:op "xsql" :xsql (xsql-document source op)}
      (seq params) (assoc :params params))))

(defn ^:no-doc op-describe
  "Build a describe operation — XSQL → codegen IR `{:operations [...]}`.
   `source` is one program, or `[{:path :source}]` with one entry per .xsql
   file (each parsed on its own, so its @namespace stays in it)."
  [source]
  (if (string? source)
    {:op "describe" :source source}
    {:op "describe" :sources (vec source)}))


;; ============================================================================
;; Subscription set — shared, pure helpers (record-scoped wire format)
;; ============================================================================
;;
;; The server's /data/subscription/set is FULL-REPLACE. We keep a local
;; mirror in (:subs client) and rebuild the whole `subscriptions` array on
;; every change. Wire item shapes:
;;   {:type "data"     :records [...] :operations [...]?}
;;   {:type "entity"   :entities [...]}
;;   {:type "relation" :relations [...]}
;;   {:type "runtime-model"} | {:type "deployed-model"}

(defn ^:no-doc normalize-descriptor
  "Validate + canonicalize a record descriptor. `records` is required and
   non-empty; `operations` optionally narrows to an op subset."
  [{:keys [records operations]}]
  (when (empty? records)
    (throw (err/ex-info "subscribe: records must be a non-empty seq of xid strings"
                    {:code "EMPTY_RECORDS"})))
  (cond-> {:records (set (map str records))}
    (seq operations) (assoc :operations (set (map name operations)))))

(defn ^:no-doc descriptor-key
  "Stable handle for a descriptor — sorted records + operations. Re-subscribing
   with the same records+operations is idempotent."
  [d]
  (pr-str [(sort (:records d)) (some-> (:operations d) sort)]))

(defn ^:no-doc build-subscriptions-body
  "Compose the wire `{:subscriptions [...]}` body from the local mirror."
  [{:keys [data entities relations models]}]
  {:subscriptions
   (vec (concat
         (for [k (sort (keys data)) :let [d (clojure.core/get data k)]]
           (cond-> {:type "data" :records (vec (sort (:records d)))}
             (:operations d) (assoc :operations (vec (sort (:operations d))))))
         (when (seq entities)  [{:type "entity"   :entities  (vec (sort entities))}])
         (when (seq relations) [{:type "relation" :relations (vec (sort relations))}])
         (for [t (sort models)] {:type t})))})


;; ============================================================================
;; Watch multiplexer — pure logic shared by CLJ (thread+core.async) and CLJS
;; (single-threaded callback) transports. ONE SSE stream + ONE consolidated
;; /data/subscription/set union per client, shared by every watch/watch-query;
;; each watcher registers an {:interest ...} and the union of interests drives
;; the wire subscription. These two fns hold ALL of the cross-platform
;; multiplexing logic — the platform `client.clj`/`client.cljs` only wire them
;; to their own transport (a/thread channels vs plain callbacks).
;; ============================================================================

(defn ^:no-doc mux-union
  "Union every live watcher's interest into one combined subscription set."
  [watches]
  (reduce (fn [acc {:keys [interest]}]
            (-> acc
                (update :records into (:records interest))
                (update :entities into (map name (:entities interest)))
                (update :relations into (map name (:relations interest)))
                (update :models into (map name (:models interest)))
                (update :operations into (map name (:operations interest)))))
          {:records #{} :entities #{} :relations #{} :models #{} :operations #{}}
          (vals watches)))

(defn ^:no-doc event-matches?
  "Does `event` concern `interest`? Discriminates on the live /data/events
   shapes:
     record/* (insert|update|delete) → `:record-xid`          → :records interest
     relation/* (link|unlink)        → `:data` [subscribed other],
                                        match on data[0] (subscribed endpoint) → :records interest
     entity/touched                  → `:entity`               → :entities interest
     relation/touched                → `:relation`             → :relations interest
     model deploy (deployed-model /
     runtime-model)                  → `:type` (see below)     → :models interest
   An opaque event (sentinel / unknown — no discriminator) is delivered to all.

   Model-deploy events carry NO :record-xid/:entity/:relation and their JSON
   payload has no :type of its own (server: {:event \"deployed-model\" :data
   {:action \"deploy\" ...}} — `format-sse` puts :event on the SSE `event:`
   line, only :data's value becomes the JSON body) — the transport layer
   (sse.cljs's parse-frame / http.clj's dispatch-frame!) fills in :type from
   that SSE event: name whenever the payload itself lacks one. Matching
   :type against a KNOWN model-type set (not treating any populated :type
   as a discriminator) keeps genuinely opaque sentinels — e.g.
   \"connection/resumed\", \"schema/changed\" — delivered to everyone, exactly
   as before; only the two real model-deploy types get the strict,
   :models-scoped treatment."
  [interest event]
  (let [rec-xid   (:record-xid event)
        rel-sub   (when (sequential? (:data event)) (first (:data event)))
        entity    (:entity event)
        relation  (:relation event)
        model     (#{"deployed-model" "runtime-model"} (:type event))
        want-recs (set (:records interest))
        want-ents (set (map name (:entities interest)))
        want-rels (set (map name (:relations interest)))
        want-mods (set (map name (:models interest)))]
    (if (or rec-xid rel-sub entity relation model)
      (boolean
        (or (and rec-xid  (contains? want-recs rec-xid))
            (and rel-sub  (contains? want-recs rel-sub))
            (and entity   (contains? want-ents entity))
            (and relation (contains? want-rels relation))
            (and model    (contains? want-mods model))))
      true)))


