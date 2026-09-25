(ns synthigy.client
  "Synthigy client for the /data endpoint — JVM / babashka.

  Connect once, then call; no client threading:

    (connect! {:endpoint \"http://localhost:7887\"
               :client-id \"my-service\"
               :client-secret \"secret\"})

    (search :user
      {:-where {:active {:-eq true}}}
      {:name nil :roles [:name :active]})

    (sync :user {:name \"alice\" :active true})

  THE INVARIANT: one process, one client, one backend. A process (BFF,
  service, script) connects to Synthigy as exactly ONE OAuth client —
  identity is multiplexed per-call with `:acting-as`, never with a second
  client. `binding synthigy.client.core/*client*` exists for TESTS, nothing
  else. Internals that pass the client VALUE do so for lifecycle correctness
  (a watch must tear down against the client it registered in, even after a
  REPL re-`connect!`) — not for multi-endpoint support, which is a non-goal.

  Keys are kebab-case in both directions by default (`:key-format nil`
  for raw snake_case wire keys).

  Pure data helpers — op builders for `batch`, `results->data`/`ok?`,
  `compose-tree`/`compose-forest` — live in `synthigy.client.core`."
  (:refer-clojure :exclude [sync get compile])
  (:require
   [synthigy.client.error :as err]
   [clojure.core.async :as a]
   [clojure.string :as str]
   [synthigy.client.core :as core]
   [synthigy.client.http :as http]
   [synthigy.client.login :as login]))

(declare disconnect!)

(defn connect!
  "Create a client from `opts` (see `synthigy.client.core/create-client`) and
   install it as the process-wide default — the Clojure server-restart idiom:
   the PREVIOUS client (if any) is destroyed first (`disconnect!` — its SSE
   listener stops, its watches close), then the new one replaces it. Call at
   startup; call again to reconnect. With no opts, everything comes from the
   environment `synthigy exec` sets up."
  ([] (connect! {}))
  ([opts]
   (disconnect!)
   (let [c (core/create-client opts)]
     (alter-var-root #'core/*client* (constantly c))
     c)))

(defn token
  "Resolve a bearer access token via the client's provider.

   Without opts: default token (for /data — Synthigy itself).
   With `:audience \"svc\"`: a token for another service that trusts
   Synthigy as IdP."
  ([] (http/token))
  ([{:keys [audience]}]
   (if audience (http/token audience) (http/token))))

(defn- execute
  [operations opts]
  (let [body (core/build-request (core/the-client) operations opts)
        response (http/request body)]
    (:results response)))

(defn- single-result
  [operation opts]
  (let [results (execute [operation] opts)
        r (core/->result (first results) operation)]
    (if (core/ok? r) r (throw r))))

(defn search
  "Search for entities matching args.

  Args and selection accept kebab-case (normalized to snake_case).
  Selection supports shorthand syntax."
  [entity args selection & {:as opts}]
  (single-result (core/op-search entity args selection) opts))

(defn get
  "Get a single entity by unique constraint.

  Args and selection accept kebab-case (normalized to snake_case).
  Selection supports shorthand syntax."
  [entity args selection & {:as opts}]
  (single-result (core/op-get entity args selection) opts))

(defn sync
  "Sync (upsert) one record (a map) or many (a vector — one operation, for
  bulk import) — returns {:count n}.

  Data keys accept kebab-case (normalized to snake_case). Pass
  `:returning true` for the written records; mint ids with
  `synthigy.client.core/new-xid` when you need them up front."
  [entity data & {:keys [returning] :as opts}]
  (single-result (core/op-sync entity data returning) opts))

(defn stack
  "Stack one record or a vector of them on top of current state — returns {:count n}.

  Data keys accept kebab-case (normalized to snake_case). Same `:returning`
  contract as sync."
  [entity data & {:keys [returning] :as opts}]
  (single-result (core/op-stack entity data returning) opts))

(defn delete
  "Delete entity records.

  Data keys accept kebab-case (normalized to snake_case)."
  [entity data & {:as opts}]
  (single-result (core/op-delete entity data) opts))

(defn slice
  "Slice relations from entity.

  Args and selection accept kebab-case (normalized to snake_case).
  Selection supports shorthand syntax."
  [entity args selection & {:as opts}]
  (single-result (core/op-slice entity args selection) opts))

(defn purge
  "Find and delete matching records, return deleted data.

  Args and selection accept kebab-case (normalized to snake_case).
  Selection supports shorthand syntax."
  [entity args selection & {:as opts}]
  (single-result (core/op-purge entity args selection) opts))

(defn sql-template
  "Execute an ERD-aware SQL template.
   Auto-generates FROM and JOINs from {entity.field} / {entity->rel.field}
   placeholders; supports junction, self-FK (tree), and field-ref traversal."
  [template params & {:keys [cached] :or {cached true} :as opts}]
  (single-result (core/op-sql-template template params :cached cached) opts))

(defn search-tree
  "Search matching entities + walk :on relation UP to their ancestors.
   `on` is a keyword/string naming the tree (self-FK) relation."
  [entity on args selection & {:as opts}]
  (single-result (core/op-search-tree entity on args selection) opts))

(defn get-tree
  "From `root` entity id, return root + descendants reachable via :on."
  [entity root on selection & {:as opts}]
  (single-result (core/op-get-tree entity root on selection) opts))


(defn batch
  "Execute multiple operations in a single request.

  Returns vector of raw results in request order. Each result is
  {:ok true :data ...} or {:ok false :error {:message ... :code ...}}.

  Use op-* builders to construct operations:

    (let [ops [(core/op-sync :user {:name \"alice\"})
               (core/op-search :user nil [:name])
               (core/op-search :user_role nil [:name])]
          [synced users roles] (results->data (batch ops) ops)]
      (when (all-ok? [synced users roles])
        (println synced users roles)))"
  [operations & {:as opts}]
  (execute operations opts))

;; =======================================================================
;; Schema introspection
;; =======================================================================

(defn schema
  "Fetch the IAM-filtered deployed model via GET /schema.

   Without `entities`: full schema.
   With a seq of entity names (kebab-case strings or keywords): narrow
   the response to those entities. Returns {:id-key :entities}."
  ([] (schema nil))
  ([entities]
   (let [url (str (http/base-url) "/schema")
         opts (cond-> {}
                (seq entities)
                (assoc :query-params
                       {"entities" (str/join "," (mapv name entities))}))]
     (http/get-json url opts))))

;; =======================================================================
;; XSQL query + lint
;; =======================================================================

(defn query
  "Run an XSQL query with optional ?name:type[] params. STRICT wire: sends
   the `xsql` DOCUMENT op ({:op \"xsql\" :xsql <document> :params …}) — a
   bare rooted body gets a synthetic `@<op> _q` header client-side; the
   server derives verb/entity/selections/args from the document. `:op`
   defaults to \"search\" — pass :op :get for a unique-key read."
  [xsql params & {:keys [op] :or {op "search"} :as opts}]
  (single-result (cond-> {:op "xsql" :xsql (core/xsql-document xsql op)}
                   params (assoc :params params))
                 opts))

(defn ^:no-doc describe
  "Compile an XSQL program `source` to codegen IR via the server
   (op:describe). Returns {:operations [...]}. Requires dataset:load scope.
   Used by the `synthigy.gen` code generator, not usually called directly."
  [source & {:as opts}]
  (single-result (core/op-describe source) opts))

(defn ^:no-doc run-xsql
  "Run a compiled op map `{:op :source :entity}` (as embedded in generated
   code) with a params map. Reads ride the XSQL selection path; sql-templates
   the template path. `opts` (e.g. {:acting-as … :key-format …}) forward to
   the underlying call. This is what generated functions delegate to."
  ([op-map params] (run-xsql op-map params nil))
  ([{:keys [op source entity]} params opts]
   (if (= op "sql-template")
     (apply sql-template source params (mapcat identity opts))
     (apply query source params :op op :entity entity
            (mapcat identity opts)))))

(defn lint
  "Check an XSQL `source` string against the IAM-projected schema and
   return a seq of diagnostics. :entity enables schema-aware checks,
   :op sets the wire op."
  [source & {:keys [entity op]}]
  (:diagnostics
   (http/post-json (str (http/base-url) "/lint")
                   (cond-> {:source source}
                     entity (assoc :entity (name entity))
                     op (assoc :op (name op))))))

(defn compile
  "Compile an XSQL `source` to the wire operation the engine would execute
   (POST /compile) — same coercion as a real call, nothing runs. `params`
   bind exactly as they would on `query`, so the map returned IS what the
   engine receives; edit it and POST it to /data yourself."
  [source & {:keys [op params] :or {op "search"}}]
  (let [{:keys [ok operation error]}
        (first (:results (http/post-json
                          (str (http/base-url) "/compile")
                          (cond-> {:operations [{:op "xsql"
                                                 :xsql (core/xsql-document source op)}]}
                            params (assoc-in [:operations 0 :params] params)))))]
    (if ok
      operation
      (throw (err/ex-info (:message error) (or error {}))))))

;; =======================================================================
;; Account onboarding
;; =======================================================================

(defn onboard
  "Mint a one-time account-claim link (POST /oauth/onboard). Confidential
   client whose principal administers the account — RBAC update on User plus
   the row inside its owner-group write scope, which the shipped User
   Provisioner role grants — else :code \"PROVISION_FORBIDDEN\". The
   connected client's own client_credentials identity IS that principal, so
   no separate credential is needed here.

   xid          : an EXISTING account's xid. Onboarding no longer creates
                  accounts — create it first over `/data` (with person_info,
                  roles, groups in one tree), then mint a ticket for it.
                  A blank xid throws :code \"XID_REQUIRED\"; one that doesn't
                  resolve throws :code \"USER_NOT_FOUND\".
   :reset       : soft-recycle this account first — strip its federated
                  identities, null its password, revoke live sessions/tokens
                  — before minting. Does NOT touch `active`; that flag is
                  your data, write it yourself.
   :methods     : restrict the claim page, e.g. [\"password\"] or [\"google\"];
                  omitted = every active federation provider plus password
   :ttl-seconds : claim-link lifetime; server default 24h
   :return-url  : where a successful DIRECT (browser) claim redirects instead
                  of Synthigy's generic status page. Must match one of THIS
                  client's registered redirections (or be a loopback URI);
                  an unregistered value throws :code \"RETURN_URL_NOT_REGISTERED\".

   -> {:onboard_url ... :expires_at ... :user {:xid ...}} (wire keys as-is,
   like schema/lint).
   Throws ex-info with :code (e.g. \"PROVISION_FORBIDDEN\", \"XID_REQUIRED\",
   \"USER_NOT_FOUND\") and :status on failure."
  [xid & {:keys [reset methods ttl-seconds return-url]}]
  (http/onboard (cond-> {:xid xid}
                  (some? reset) (assoc :reset reset)
                  methods (assoc :methods (mapv name methods))
                  ttl-seconds (assoc :ttl_seconds ttl-seconds)
                  return-url (assoc :return_url return-url))))

(defn onboard-complete
  "Redeem an onboarding ticket without a browser (POST
   /oauth/onboard/complete) — the indirect face of the SAME ticket `onboard`
   mints. Must be called by the SAME client that minted the ticket; any
   other client's bearer is rejected with :code \"CLAIM_INVALID\", and the
   client must still administer the account (:code \"PROVISION_FORBIDDEN\").

   The caller runs its own out-of-band proofing (email link, SMS OTP, push
   approval, KYC, a phone call — Synthigy never learns which) and, once
   satisfied, redeems the ticket itself instead of bouncing the user's
   browser through /oauth/claim. This never sets a credential — credentials
   are subject-only. The account activates with none; give it one via the
   claim page (password or a federated identity) or a later ticket.

   ticket : the token minted by `onboard` (parse it out of :onboard_url)

   -> {:user {:xid ...} :active true}
   Throws ex-info with :code (e.g. \"CLAIM_INVALID\") and :status on failure."
  [ticket]
  (http/onboard-complete {:ticket ticket}))

;; =======================================================================
;; Browser login (OIDC authorization code + PKCE)
;; =======================================================================

(defn login-start
  "Begin a person's login; redirect the browser to the returned :url.

   redirect-uri     : your callback URL, registered on this OAuth client
   :return-to       : handed back by `login-complete` (default \"/\")
   :scope           : default \"openid\"
   :public-endpoint : the browser-facing server URL, when it differs from :endpoint

   Needs a confidential client (:client-id + :client-secret) and a :login-store."
  [redirect-uri & {:as opts}]
  (login/start (core/the-client) redirect-uri opts))

(defn login-complete
  "Exchange the callback's code; -> {:user {:xid :name :scopes} :tokens {...} :return-to ...}.

   Throws :code LOGIN_STATE_UNKNOWN, LOGIN_NONCE_MISMATCH or LOGIN_EXCHANGE_FAILED."
  [code state redirect-uri]
  (login/complete (core/the-client) code state redirect-uri))

(defn login-cancel
  "Discard an in-flight login (the callback came back with `error`); -> {:return-to ...} or nil."
  [state]
  (login/cancel (core/the-client) state))

;; =======================================================================
;; Model introspection
;; =======================================================================

(defn deploy
  "Deploy a dataset version from a modeler export, verbatim. Requires dataset:deploy."
  [export-contents & {:as opts}]
  (single-result (core/op-deploy export-contents) opts))

(defn destroy
  "Destroy a dataset (every version, table and row). Requires dataset:delete."
  [dataset-xid & {:as opts}]
  (single-result (core/op-destroy dataset-xid) opts))

(defn deployed-model
  "Fetch the raw ERD model as deployed. Requires dataset:load scope."
  [& {:as opts}]
  (single-result (core/op-deployed-model) opts))

(defn runtime-model
  "Fetch the runtime ERD model (deployed + identity/audit/ref expansion).
   Requires dataset:load scope."
  [& {:as opts}]
  (single-result (core/op-runtime-model) opts))

;; =======================================================================
;; History — temporal query surface over the audit plug (/history)
;; =======================================================================

(defn- history-post
  [op hopts]
  (try
    (:result (http/post-json (str (http/base-url) "/history")
                             {:op op :opts hopts}))
    (catch clojure.lang.ExceptionInfo e
      (if (= 404 (:status (ex-data e)))
        (throw (err/ex-info "History unavailable — no audit provider configured on the server"
                        {:code "HISTORY_UNAVAILABLE"}))
        (throw e)))))

(defn- between-or-now
  "Default the time range to [nil now] when unset (matches Go/JS)."
  [between]
  (clojure.core/or between [nil (str (java.time.Instant/now))]))

(defn history-get-at
  "State of `record-xid` at timestamp `at`. Options: :tenant :include-deleted?"
  [record-xid at & {:keys [tenant include-deleted?]}]
  (history-post "get-at"
                (cond-> {:record-xid record-xid :at at}
                  tenant (assoc :tenant tenant)
                  (some? include-deleted?) (assoc :include-deleted? include-deleted?))))

(defn history-events
  "Events for `record-xid` (nil = any record) over a time range. Options:
   :between [from to] (defaults [nil now]) :tenant :limit :track"
  [record-xid & {:keys [between tenant limit track]}]
  (history-post "events"
                (cond-> {:between (between-or-now between)}
                  record-xid (assoc :record-xid record-xid)
                  tenant (assoc :tenant tenant)
                  limit (assoc :limit limit)
                  track (assoc :track track))))

(defn history-diff
  "Difference in a record's state between two timestamps. Option: :tenant"
  [record-xid from-ts to-ts & {:keys [tenant]}]
  (history-post "diff"
                (cond-> {:record-xid record-xid :from-ts from-ts :to-ts to-ts}
                  tenant (assoc :tenant tenant))))

(defn history-timeline
  "Events grouped by :group-by (\":request\"/\":actor\"/\":scope\").
   Options: :between [from to] :group-by :tenant :limit"
  [& {:keys [between group-by tenant limit]}]
  (history-post "timeline"
                (cond-> {:between (between-or-now between)}
                  group-by (assoc :group-by group-by)
                  tenant (assoc :tenant tenant)
                  limit (assoc :limit limit))))

(defn history-since
  "Events strictly after the :cursor timestamp, oldest-first.
   Options: :cursor :tenant :limit :track"
  [& {:keys [cursor tenant limit track]}]
  (history-post "since"
                (cond-> {}
                  cursor (assoc :cursor cursor)
                  tenant (assoc :tenant tenant)
                  limit (assoc :limit limit)
                  track (assoc :track track))))

;; =======================================================================
;; SSE listener
;; =======================================================================

(defn listen
  "Stream SSE change events from /data/events, calling `on-event` with each
   delta envelope. `:type` is the delta kind — record/insert|update|delete
   (with `:record-xid` + `:before`/`:after`), relation/link|unlink (with
   `:data` [subscribed other]), or the coalesced pokes entity/touched
   (`:entity`) / relation/touched (`:relation`).

   BLOCKS the calling thread — wrap in (future ...) for background use.
   Auto-reconnects with Last-Event-ID + exponential backoff (1s → 30s).

   Events are notification-only — fetch the updated records via
   `search`/`get`/`batch` after receiving one.

   Options:
     :stop?     — 0-arg predicate; truthy stops the loop between frames
     :max-delay — cap for reconnect backoff in ms (default 30000)

   UNAUTHORIZED/FORBIDDEN propagate; other transport errors reconnect.

     (subscribe c :user)
     (future (listen c prn))"
  ([on-event] (listen on-event nil))
  ([on-event opts] (http/sse-listen on-event (or opts {}))))

;; =======================================================================
;; Watch — ergonomic SSE subscription (TS-SDK parity: client.watch)
;; =======================================================================

;; ── multiplexer internals ─────────────────────────────────────────────
;; These take the client VALUE (not the dynvar) for LIFECYCLE correctness,
;; The root client is the ONLY live client (connect! destroys the previous
;; one, server-restart style), so the mux reads it directly — no threading.
;; ONE SSE stream + ONE consolidated subscription per client, shared by
;; every watch / watch-query. Each watcher registers an {:interest :ch};
;; the UNION of interests drives /data/subscription/set, and each incoming
;; event is dispatched to the watchers whose interest matches it. This is
;; the Clojure flavor of the TS WatchMultiplexer.
;;
;; core.async decoupling: mux-dispatch runs on the ONE thread that also
;; reads the SSE stream — calling a subscriber's callback there directly
;; would let one slow/blocking sink (I/O, a lock, a slow UI redraw) stall
;; delivery to every OTHER watcher and delay the next SSE frame for the
;; whole client. Each watcher instead gets its own sliding-buffered
;; channel + a dedicated consumer thread. `mux-dispatch` only ever does a
;; non-blocking `a/put!`; a slow subscriber delays only its OWN delivery,
;; and old pending events for it are dropped (sliding window) rather than
;; unboundedly queuing.
;;
;; `a/thread`, not `go`/`go-loop`, for the consumer: `sink` is caller code
;; that may block. `go`'s own docstring still says blocking ops risk
;; depleting "the fixed pool of go block threads" — current core.async
;; happens to use a cached (not literally fixed-size) executor for go
;; dispatch, so in practice it degrades to spinning up real threads
;; rather than deadlocking, but that's an internal detail, not the
;; documented contract (overridable via the executor-factory sysprop,
;; and go-checking, which WOULD throw on this, is off by default — so
;; nothing stops it silently). `a/thread`/`io-thread` is core.async's own
;; sanctioned API for "may do blocking I/O" work, costs the same one
;; real thread either way, and — unlike routing through `go` — doesn't
;; draw on the pool every OTHER core.async user in the process shares.

(defn- mux-flush!
  "Recompute the union over all watchers and POST it as the full
   subscription set (serialized so the latest union always lands last)."
  [mux]
  (locking mux
    (let [{:keys [records entities relations models operations]}
          (core/mux-union (:watches @mux))]
      (reset! (:subs (core/the-client))
              {:data (if (seq records)
                       (let [d (core/normalize-descriptor {:records records :operations operations})]
                         {(core/descriptor-key d) d})
                       {})
               :entities (set entities)
               :relations (set relations)
               :models    (set models)})
      (http/flush-subs!))))

(defn- mux-dispatch [mux event]
  (doseq [{:keys [interest ch]} (vals (:watches @mux))]
    (when (core/event-matches? interest event)
      ;; Non-blocking — a full/slow watcher must never stall this loop.
      (a/put! ch event))))

(defn- mux-ensure-listening! [mux]
  (locking mux
    (when-not (:fut @mux)
      (let [stop? (atom false)]
        (swap! mux assoc :stop stop? :error nil
               :fut (future
                      (try
                        (listen (fn [ev] (mux-dispatch mux ev))
                                {:stop? #(deref stop?)
                                 ;; the server-side subscription set may have been
                                 ;; lost or replaced while disconnected — re-assert
                                 ;; the union on every (re)connect.
                                 :on-connect #(try (mux-flush! mux)
                                                   (catch Throwable _))})
                        (catch Throwable e
                          ;; Orderly shutdown (stop flag / future-cancel
                          ;; interrupt) is not a death — stay quiet. A REAL
                          ;; death must NEVER be silent: log, record for
                          ;; introspection, and clear :fut so the next watch
                          ;; registration restarts the listener.
                          (when-not (or @stop? (instance? InterruptedException e))
                            (binding [*out* *err*]
                              (println "synthigy.client watch listener DIED:"
                                       (ex-message e) (pr-str (ex-data e))))
                            (locking mux
                              (swap! mux assoc :fut nil :error e)))))))))))

(defn- mux-maybe-stop! [mux]
  (locking mux
    (when (empty? (:watches @mux))
      (when-let [s (:stop @mux)] (reset! s true))
      (when-let [f (:fut @mux)] (future-cancel f))
      (swap! mux assoc :fut nil :stop nil))))

(def ^:private watch-buffer-size
  "Sliding-window size per watcher channel — bounds memory under a burst;
   overflow drops the OLDEST pending event for that watcher (never blocks
   the dispatch loop, never affects other watchers)."
  100)

(defn- spawn-watch-channel!
  "Wire a fresh sliding-buffered channel to a dedicated consumer thread
   that calls `sink` for every event put onto it. Returns the channel —
   `mux-dispatch` only ever `a/put!`s onto it (non-blocking); a slow or
   blocking `sink` only delays ITS OWN delivery.

   A REAL thread (`a/thread`), not `go`: `sink` is arbitrary caller code
   that may block (I/O, a lock, a slow UI redraw) — `a/thread`/`io-thread`
   is core.async's own documented mechanism for that (`go` bodies are
   documented as never blocking; see the mux-dispatch comment above for
   why that still holds even though today's go-dispatch executor happens
   to be a cached, not fixed, pool). Pure and independently testable —
   no client/server needed."
  [sink]
  (let [ch (a/chan (a/sliding-buffer watch-buffer-size))]
    (a/thread
      (loop []
        (when-let [ev (a/<!! ch)]
          (try (sink ev)
               (catch Throwable e
                 (binding [*out* *err*]
                   (println "synthigy.client watch sink threw:" (ex-message e)))))
          (recur))))
    ch))

(defn- mux-register! [interest sink]
  (let [mux (:mux (core/the-client))
        id  (str (gensym "w"))
        ch  (spawn-watch-channel! sink)]
    (swap! mux assoc-in [:watches id] {:interest interest :ch ch})
    (mux-ensure-listening! mux)
    (mux-flush! mux)
    id))

(defn- mux-unregister! [id]
  (let [mux (:mux (core/the-client))
        ch  (get-in @mux [:watches id :ch])]
    (swap! mux update :watches dissoc id)
    ;; Closing ends the consumer thread's loop (a/<!! returns nil once the
    ;; channel is closed AND drained) — no thread leak on close.
    (when ch (a/close! ch))
    (mux-flush! mux)
    (mux-maybe-stop! mux)))

(defn- mux-set-interest! [id interest]
  (let [mux (:mux (core/the-client))]
    (swap! mux assoc-in [:watches id :interest] interest)
    (mux-flush! mux)))

;; =======================================================================
;; Watch — ergonomic SSE subscription (TS-SDK parity: client.watch)
;; =======================================================================

(defn watch
  "Open a live SSE subscription for `interest` and stream change events to
   `on-event`. Mirrors the TypeScript SDK's `client.watch(interest)`.

   Registers with the client's shared multiplexer: ALL watch / watch-query
   on a client share ONE /data/events stream and ONE consolidated
   subscription (their interests are unioned). No clobber, one connection.

   `interest` is a map with any of:
     :records    — record xids (data-track)
     :entities   — entity names (entity-track: any change to the entity)
     :relations  — relation labels (relation-track)
     :operations — optional op filter for the record track

   Non-blocking. Returns a handle map:
     :interest     (fn [])         → current interest
     :set-interest (fn [interest]) → replace interest (re-unions + re-POSTs)
     :add          (fn [xids])     → add record xids
     :remove       (fn [xids])     → remove record xids
     :close        (fn [])         → deregister (stream stops when last closes)

   Events are notification-only — refetch via search/get/batch on receipt
   (RLS-correct notify-then-refetch).

   `on-event` runs on its OWN dedicated thread, decoupled via a
   sliding-buffered core.async channel — a slow or blocking callback
   (I/O, a lock, a slow UI redraw) delays only ITS OWN delivery, never
   another watcher's, and never the shared SSE stream. Overflow (100
   pending events) drops the oldest for that watcher only.

     (def w (watch {:entities [\"user\"]} (fn [ev] (prn ev))))
     ((:add w) [\"some-xid\"])
     ((:close w))"
  [interest on-event & {:as _opts}]
  (let [state (atom interest)
        id    (mux-register! interest on-event)]
    {:interest     (fn [] @state)
     :set-interest (fn [i] (reset! state i) (mux-set-interest! id i))
     :add          (fn [xids] (mux-set-interest! id
                                (swap! state update :records (fnil into []) xids)))
     :remove       (fn [xids] (let [s (set xids)]
                                (mux-set-interest! id
                                  (swap! state update :records #(vec (remove s %))))))
     :close        (fn [] (mux-unregister! id))}))

(defn watch-schema
  "Stream model-deploy events over the shared multiplexer. Mirrors the TS
   SDK's client.watchSchema(). `:raw true` watches the raw deployed model;
   default the runtime model. Returns a handle with :close."
  [on-event & {:keys [raw]}]
  (let [id (mux-register!
            {:models [(if raw "deployed-model" "runtime-model")]}
            on-event)]
    {:close (fn [] (mux-unregister! id))}))

(defn- debounced-refetch!
  "Coalesced notify-then-refetch: `gen` counts poke generations; call this
   on every poke. After `ms`, if no NEWER poke has arrived (gen still
   matches), `refetch!` runs — exactly ONE refetch per burst, no matter
   how many pokes land inside the window.

   The wait parks on a `core.async/timeout` channel — cheap, no OS thread
   held — so a burst of N pokes costs N parked go-blocks, not N real
   threads sleeping (the old `future` + `Thread/sleep` per poke).
   `refetch!` itself is expected to block (real HTTP I/O), so it runs on
   `a/thread` (a real thread), never inside the `go` block. Pure and
   independently testable — no client/server needed."
  [gen ms refetch!]
  (let [g (swap! gen inc)]
    (a/go
      (a/<! (a/timeout ms))
      (when (= g @gen)
        (a/thread (refetch!))))))

(defn- live-value*
  "Shared engine of watch-query / watch-xsql: snapshot atom + mux interest +
   coalesced notify-then-refetch (+ optional row tracking). `run` is a 0-arg
   refetch returning the current rows."
  [entity-name run {:keys [debounce-ms relations records entity-track
                            track-rows interest-entities]
                     :or {debounce-ms 80 entity-track true}}]
  (let [value (atom (run))
        gen   (atom 0)
        poke! (fn [] (debounced-refetch! gen debounce-ms
                       #(try (reset! value (run))
                             (catch Throwable e
                               (binding [*out* *err*]
                                 (println "synthigy.client watch refetch failed:"
                                          (ex-message e)))))))
        base-entities (or interest-entities (when entity-track [entity-name]))
        interest-for (fn [rows]
                       (cond-> {}
                         (seq base-entities) (assoc :entities (vec base-entities))
                         (seq relations)     (assoc :relations relations)
                         (seq records)       (assoc :records (vec records))
                         (and track-rows (seq rows))
                         (update :records (fnil into []) (keep :xid rows))))
        id    (mux-register! (interest-for @value) (fn [_ev] (poke!)))]
    (when track-rows
      (add-watch value ::track-rows
                 (fn [_ _ _ rows] (mux-set-interest! id (interest-for rows)))))
    (alter-meta! value assoc :close #(mux-unregister! id) :watch-id id)
    value))

(defn watch-query
  "Live, RLS-scoped result-set for a `search`. Clojure-flavored: returns an
   ATOM holding the current result vector, kept fresh in the background —
   deref it, `add-watch` it, `close-watch!` it.

     (def msgs (watch-query \"Chat Message\" {:-where {...}} [:content]
                            :acting-as user-xid))
     @msgs                                          ; current results
     (add-watch msgs :ui (fn [_ _ old new] ...))    ; react to changes
     (close-watch! msgs)                            ; stop

   Mirrors the TS SDK's watchQuery: snapshot via `search`, register the
   entity interest with the shared multiplexer, and on each matching event
   do a coalesced re-run + reset! the atom (notify-then-refetch — RLS stays
   correct because the refetch carries the same :acting-as). Many
   watch-queries with different :acting-as / interests now coexist on ONE
   client and ONE stream — the multiplexer unions their interests.

   Options:
     :acting-as / :key-format — forwarded to the snapshot + every refetch
     :relations / :records    — widen the watch interest beyond the entity
     :entity-track            — false to DROP the entity-wide interest and
                                watch only :records/:relations (default true).
                                Use with :records to scope a live view to a
                                \"room\" — e.g. a group record whose link
                                events fire when children attach to it.
     :track-rows              — also keep the CURRENT result rows' xids in
                                the interest, re-registered after every
                                refetch. With :entity-track false this makes
                                edits/deletes of already-visible rows fire
                                (their events carry only the row's own xid).
     :debounce-ms             — coalesce window for bursts (default 80)"
  [entity args selection & {:as opts}]
  (let [search-opts (mapcat identity (select-keys opts [:acting-as :key-format]))
        run (fn [] (apply search entity args selection search-opts))]
    (live-value* (name entity) run opts)))

(defn ^:no-doc watch-xsql
  "Live result-set for a generated/compiled XSQL op map `{:op :source :entity}`
   — `watch-query` for codegen ops (`@watch` in the .xsql emits a `watch-<name>`
   fn that delegates here). Returns the same closeable atom; same options as
   `watch-query`, plus `:interest-entities` to override the interest entirely (used by
   sql-template ops, whose `@watch` declares the entities to observe)."
  ([op-map params] (watch-xsql op-map params nil))
  ([op-map params opts]
   (let [run-opts (select-keys opts [:acting-as :key-format])
         run (fn [] (run-xsql op-map params run-opts))]
     (live-value* (:entity op-map) run opts))))

(defn watch-sql-template
  "Live, RLS-scoped result for a SQL template — the ad-hoc twin of the
   `watch-<name>` a codegen'd `@watch` sql-template emits. Returns the same
   closeable atom as `watch-query`.

     (def stats (watch-sql-template \"SELECT count(*) AS n FROM {movie}\" nil
                                    :entities [\"Movie\"]))
     @stats                                         ; current rows
     (close-watch! stats)                           ; stop

   `:entities` is required: a template has no root entity, so there is nothing
   to infer the multiplexer interest from. Otherwise takes `watch-query`'s
   options."
  [template params & {:keys [entities] :as opts}]
  (when-not (seq entities)
    (throw (err/ex-info "watch-sql-template needs :entities — a SQL template has no root entity to infer the watch interest from"
                    {:code "MISSING_ENTITIES" :template template})))
  (watch-xsql {:op "sql-template" :source template} params
              (-> opts
                  (dissoc :entities)
                  (assoc :interest-entities (vec entities)))))

(defn close-watch!
  "Stop a handle from `watch`/`watch-schema` (a map with :close) or from
   `watch-query` (an atom carrying :close in its metadata)."
  [h]
  (if (map? h)
    ((:close h))
    (when-let [c (:close (meta h))] (c)))
  nil)

(defn disconnect!
  "Destroy the connected client, server-restart style: stop the watch
   multiplexer's SSE listener, drop all watches, best-effort clear the
   server-side subscription set, and uninstall the client. No-op when not
   connected. `connect!` calls this on the previous client automatically."
  []
  (when-let [client core/*client*]
    (let [mux (:mux client)]
      (locking mux
        ;; Close every watch channel FIRST — a consumer loop terminates only
        ;; when its channel closes-and-drains (a/<!! → nil). Just dropping the
        ;; :watches map would orphan the channels and leak their consumer
        ;; threads (parked on a/<!! forever) on every disconnect!/reconnect.
        (doseq [{:keys [ch]} (vals (:watches @mux))]
          (when ch (a/close! ch)))
        (swap! mux assoc :watches {})
        (when-let [stop (:stop @mux)] (reset! stop true))
        (when-let [fut (:fut @mux)] (future-cancel fut))
        (swap! mux assoc :fut nil :stop nil)))
    (try
      (reset! (:subs client) {:data {} :entities #{} :relations #{} :models #{}})
      (http/flush-subs!)
      (catch Throwable _))
    (alter-var-root #'core/*client* (constantly nil)))
  nil)
