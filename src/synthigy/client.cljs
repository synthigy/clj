(ns synthigy.client
  "Synthigy client for the /data endpoint — ClojureScript (promise-returning).

  Same surface as the CLJ client; every network fn returns a Promise.
  Connect once via `connect!`; pure data helpers live in
  `synthigy.client.core`.

  THE INVARIANT: one process, one client, one backend — identity is
  multiplexed per-call with `:acting-as`, never with a second client."
  (:refer-clojure :exclude [sync get])
  (:require
   [clojure.string :as str]
   [synthigy.client.core :as core]
   [synthigy.client.http :as http]
   [synthigy.client.sse :as sse]
   [promesa.core :as p]))

(declare disconnect!)

(defn connect!
  "Create a client from `opts` and install it as the process-wide default —
   the same server-restart idiom as the CLJ client: the PREVIOUS client (if
   any) is destroyed first (`disconnect!` — its SSE listener stops, its
   watches close), then the new one replaces it. Call again to reconnect
   (e.g. after a silent-renew login/logout swap)."
  [opts]
  (disconnect!)
  (set! core/*client* (core/create-client opts))
  core/*client*)

(defn token
  "Resolve a bearer access token via the client's provider.
   Returns a promise — the CLJS transport is async throughout."
  ([] (http/token))
  ([{:keys [audience]}]
   (if audience (http/token audience) (http/token))))

(defn- execute
  [operations opts]
  (let [body (core/build-request (core/the-client) operations opts)]
    (p/let [response (http/request body)]
      (:results response))))

(defn- single-result
  [operation opts]
  (p/let [results (execute [operation] opts)
          r (core/->result (first results) operation)]
    (if (core/ok? r) r (throw r))))

(defn search
  "Search for entities. Returns a Promise of the result data."
  [entity args selection & {:as opts}]
  (single-result (core/op-search entity args selection) opts))

(defn get
  "Get a single entity by unique constraint. Returns a Promise."
  [entity args selection & {:as opts}]
  (single-result (core/op-get entity args selection) opts))

(defn sync
  "Sync (upsert) entity data — resolves to {:count n}. Pass `:returning true`
   for the written records."
  [entity data & {:keys [returning] :as opts}]
  (single-result (core/op-sync entity data returning) opts))

(defn stack
  "Stack data on top of current state — resolves to {:count n}. Same
   `:returning` contract as sync."
  [entity data & {:keys [returning] :as opts}]
  (single-result (core/op-stack entity data returning) opts))

(defn delete
  "Delete entity records. Returns a Promise."
  [entity data & {:as opts}]
  (single-result (core/op-delete entity data) opts))

(defn slice
  "Slice relations from entity. Returns a Promise."
  [entity args selection & {:as opts}]
  (single-result (core/op-slice entity args selection) opts))

(defn purge
  "Find and delete matching records. Returns a Promise of deleted data."
  [entity args selection & {:as opts}]
  (single-result (core/op-purge entity args selection) opts))

(defn sql-template
  "Execute an ERD-aware SQL template. Returns a Promise."
  [template params & {:keys [cached] :or {cached true} :as opts}]
  (single-result (core/op-sql-template template params :cached cached) opts))

(defn search-tree
  "Search matching entities + walk :on relation UP to ancestors. Returns a Promise."
  [entity on args selection & {:as opts}]
  (single-result (core/op-search-tree entity on args selection) opts))

(defn get-tree
  "Get root + descendants via :on relation. Returns a Promise."
  [entity root on selection & {:as opts}]
  (single-result (core/op-get-tree entity root on selection) opts))

(defn batch
  "Execute multiple operations in a single request. Returns a Promise
   of raw results vector; use `results->data` to extract."
  [operations & {:as opts}]
  (execute operations opts))

;; =======================================================================
;; Schema introspection
;; =======================================================================

(defn schema
  "Fetch the IAM-filtered deployed model via GET /schema. Returns a Promise."
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

(defn- xsql-document
  "Ensure an XSQL operation DOCUMENT (STRICT wire: XSQL travels only as
   {:op \"xsql\" :xsql <document>}). Sources already starting with `@` pass
   through — their @verb is authoritative; bare rooted bodies get a
   synthetic `@<op> _q` header."
  [source op]
  (if (clojure.string/starts-with? (clojure.string/triml source) "@")
    source
    (str "@" (name op) " _q\n" source)))

(defn query
  "Run an XSQL query with optional ?name:type[] params. STRICT wire: sends
   the `xsql` DOCUMENT op ({:op \"xsql\" :xsql <document> :params …}) — a
   bare rooted body gets a synthetic `@<op> _q` header client-side; the
   server derives verb/entity/selections/args from the document. `:op`
   defaults to \"search\" — pass :op :get for a unique-key read. Returns a Promise."
  [xsql params & {:keys [op] :or {op "search"} :as opts}]
  (single-result (cond-> {:op "xsql" :xsql (xsql-document xsql op)}
                   params (assoc :params params))
                 opts))

(defn ^:no-doc describe
  "Compile an XSQL program `source` to codegen IR (op:describe).
   Returns a Promise of {:operations [...]}. Requires dataset:load scope."
  [source & {:as opts}]
  (single-result (core/op-describe source) opts))

(defn ^:no-doc run-xsql
  "Run a compiled op map `{:op :source :entity}` with a params map. Returns
   a Promise. Reads ride the XSQL selection path; sql-templates the template.
   `opts` (e.g. {:acting-as …}) forward to the underlying call."
  ([op-map params] (run-xsql op-map params nil))
  ([{:keys [op source entity]} params opts]
   (if (= op "sql-template")
     (apply sql-template source params (mapcat identity opts))
     (apply query source params :op op :entity entity
            (mapcat identity opts)))))

(defn lint
  "Check an XSQL `source` string against the IAM-projected schema.
   Returns a Promise of the diagnostics seq. :entity enables
   schema-aware checks, :op sets the wire op."
  [source & {:keys [entity op]}]
  (p/then (http/post-json (str (http/base-url) "/lint")
                          (cond-> {:source source}
                            entity (assoc :entity (name entity))
                            op (assoc :op (name op))))
          :diagnostics))

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
                  A blank xid rejects with :code \"XID_REQUIRED\"; one that
                  doesn't resolve rejects with :code \"USER_NOT_FOUND\".
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
                  an unregistered value rejects with :code
                  \"RETURN_URL_NOT_REGISTERED\".

   Returns a Promise of {:onboard_url ... :expires_at ... :user {:xid ...}}
   (wire keys as-is, like schema/lint).
   Rejects with ex-info carrying :code (e.g.
   \"PROVISION_FORBIDDEN\", \"XID_REQUIRED\", \"USER_NOT_FOUND\") and :status
   on failure."
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

   Returns a Promise of {:user {:xid ...} :active true}. Rejects with
   ex-info carrying :code (e.g. \"CLAIM_INVALID\") and :status on failure."
  [ticket]
  (http/onboard-complete {:ticket ticket}))

;; =======================================================================
;; Model introspection
;; =======================================================================

(defn deployed-model
  "Fetch the raw ERD model as deployed. Requires dataset:load scope.
   Returns a Promise."
  [& {:as opts}]
  (single-result (core/op-deployed-model) opts))

(defn runtime-model
  "Fetch the runtime ERD model (deployed + identity/audit/ref expansion).
   Requires dataset:load scope. Returns a Promise."
  [& {:as opts}]
  (single-result (core/op-runtime-model) opts))

;; =======================================================================
;; SSE listener — returns a 0-arg `stop` fn (does NOT block)
;; =======================================================================

(defn listen
  "Stream SSE notifications from /data/events. Calls `on-event` with
   each `{:type :entity :relations :xids}` notification. Returns a
   0-arg `stop` function that aborts the listener.

   Auto-reconnects with Last-Event-ID + exponential backoff. Does
   NOT block — runs on the microtask queue via fetch streaming.

   Options:
     :max-delay       — reconnect backoff cap ms (default 30000)
     :on-parse-error  — (fn [error raw]) for malformed frames"
  ([on-event] (listen on-event nil))
  ([on-event opts] (sse/listen on-event opts)))

;; =======================================================================
;; Watch — ergonomic SSE subscription (TS-SDK parity: client.watch)
;; =======================================================================
;;
;; ONE SSE stream + ONE consolidated subscription per client, shared by
;; every watch/watch-query. Each watcher registers an {:interest :sink};
;; the UNION of interests (synthigy.client.core/mux-union) drives
;; /data/subscription/set, and each incoming event is dispatched to the
;; watchers whose interest matches it (synthigy.client.core/event-matches?)
;; — the SAME pure logic the JVM client uses, so both platforms are pinned
;; by identical tests.
;;
;; No core.async here: JS is single-threaded, so the JVM mux's channel +
;; dedicated-consumer-thread decoupling (a slow/blocking sink must never
;; stall the shared SSE reader) has no JVM-style failure mode to guard
;; against — there IS no other thread to block. A sink is called directly
;; from the dispatch loop; a synchronously-throwing sink is caught so it
;; can't take down delivery to other watchers, matching the JVM contract
;; minus the thread machinery that contract needed only because the JVM
;; has real OS threads to protect.
;;
;; Flush serialization mirrors sdk/js's WatchMultiplexer: a bare `swap!` +
;; fire-and-forget POST would let two overlapping flushes race over the
;; network, and the smaller/older union could land LAST on the server
;; (see JS's `_scheduleFlush` comment). `mux-flush!` chains onto any
;; in-flight POST so the server always observes the union computed
;; CLOSEST to when its own POST actually fires.

(defn- mux-flush-now!
  "Recompute the union over all watchers and POST it as the full
   subscription set. Returns a Promise."
  [mux]
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
    (http/flush-subs!)))

(defn- mux-flush!
  "Schedule a flush, serialized after any flush already in flight — the
   union is recomputed fresh inside `mux-flush-now!` right before its own
   POST, so overlapping flushes never let an older union land last."
  [mux]
  (when-not (:flush-scheduled? @mux)
    (swap! mux assoc :flush-scheduled? true)
    (js/queueMicrotask
     (fn []
       (swap! mux assoc :flush-scheduled? false)
       (let [prior (:flush-inflight @mux)
             ;; p/handle always resolves (regardless of prior success/failure)
             ;; so one rejected flush can never wedge every flush after it.
             next (-> (if prior (p/handle prior (fn [_ _] nil)) (p/resolved nil))
                      (p/then (fn [_] (mux-flush-now! mux))))]
         (swap! mux assoc :flush-inflight next)
         (p/finally next
                    (fn [_ _]
                      (when (identical? next (:flush-inflight @mux))
                        (swap! mux assoc :flush-inflight nil)))))))))

(defn- mux-dispatch [mux event]
  (doseq [{:keys [interest sink]} (vals (:watches @mux))]
    (when (core/event-matches? interest event)
      (try (sink event)
           (catch :default e
             (js/console.warn "synthigy.client watch sink threw:" (ex-message e)))))))

(defn- mux-ensure-listening! [mux]
  (when-not (:stop @mux)
    (let [stop (sse/listen (fn [ev] (mux-dispatch mux ev))
                           {:on-connect #(mux-flush! mux)})]
      (swap! mux assoc :stop stop))))

(defn- mux-maybe-stop! [mux]
  (when (empty? (:watches @mux))
    (when-let [stop (:stop @mux)] (stop))
    (swap! mux assoc :stop nil)))

(defn- mux-register! [interest sink]
  (let [mux (:mux (core/the-client))
        id  (str (random-uuid))]
    (swap! mux assoc-in [:watches id] {:interest interest :sink sink})
    (mux-ensure-listening! mux)
    (mux-flush! mux)
    id))

(defn- mux-unregister! [id]
  (let [mux (:mux (core/the-client))]
    (swap! mux update :watches dissoc id)
    (mux-flush! mux)
    (mux-maybe-stop! mux)))

(defn- mux-set-interest! [id interest]
  (let [mux (:mux (core/the-client))]
    (swap! mux assoc-in [:watches id :interest] interest)
    (mux-flush! mux)))

(defn watch
  "Open a live SSE subscription for `interest` and stream change events to
   `on-event`. Mirrors the TypeScript SDK's `client.watch(interest)` and
   the CLJ SDK's `watch` — same interest shape, same shared multiplexer.

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

     (def w (watch {:entities [\"user\"]} (fn [ev] (js/console.log ev))))
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
   how many pokes land inside the window."
  [gen ms refetch!]
  (let [g (swap! gen inc)]
    (js/setTimeout
     (fn [] (when (= g @gen) (refetch!)))
     ms)))

(defn- live-value*
  "Shared engine of watch-query/watch-xsql: snapshot atom + mux interest +
   coalesced notify-then-refetch (+ optional row tracking). `run` is a 0-arg
   refetch returning a Promise of the current rows.

   Returns a Promise of the closeable atom — cljs is async throughout, so
   the FIRST snapshot must be awaited before the atom is safe to read;
   every refetch AFTER that just resets! the atom in the background."
  [entity-name run {:keys [debounce-ms relations records entity-track
                            track-rows interest-entities]
                     :or {debounce-ms 80 entity-track true}}]
  (let [value (atom nil)
        gen   (atom 0)
        poke! (fn [] (debounced-refetch! gen debounce-ms
                       #(p/catch (p/then (run) (fn [rows] (reset! value rows)))
                                 (fn [e]
                                   (js/console.warn "synthigy.client watch refetch failed:"
                                                    (ex-message e))))))
        base-entities (or interest-entities (when entity-track [entity-name]))
        interest-for (fn [rows]
                       (cond-> {}
                         (seq base-entities) (assoc :entities (vec base-entities))
                         (seq relations)     (assoc :relations relations)
                         (seq records)       (assoc :records (vec records))
                         (and track-rows (seq rows))
                         (update :records (fnil into []) (keep :xid rows))))]
    (p/let [rows (run)]
      (reset! value rows)
      (let [id (mux-register! (interest-for rows) (fn [_ev] (poke!)))]
        (when track-rows
          (add-watch value ::track-rows
                     (fn [_ _ _ rows] (mux-set-interest! id (interest-for rows)))))
        (alter-meta! value assoc :close #(mux-unregister! id) :watch-id id)
        value))))

(defn watch-query
  "Live, RLS-scoped result-set for a `search`. Returns a Promise of an
   ATOM holding the current result vector, kept fresh in the background —
   await it once, then deref/add-watch/close-watch! it like any atom.

     (p/let [msgs (watch-query \"Chat Message\" {:-where {...}} [:content])]
       (add-watch msgs :ui (fn [_ _ old new] ...))
       (js/console.log @msgs))

   Mirrors the TS SDK's watchQuery / the CLJ SDK's watch-query: snapshot
   via `search`, register the entity interest with the shared multiplexer,
   and on each matching event do a coalesced re-run + reset! the atom
   (notify-then-refetch — RLS stays correct because the refetch carries the
   same :acting-as). Many watch-queries now coexist on ONE client and ONE
   stream — the multiplexer unions their interests.

   Options: same as the CLJ SDK's watch-query (:acting-as/:key-format,
   :relations/:records, :entity-track, :track-rows, :debounce-ms)."
  [entity args selection & {:as opts}]
  (let [search-opts (mapcat identity (select-keys opts [:acting-as :key-format]))
        run (fn [] (apply search entity args selection search-opts))]
    (live-value* (name entity) run opts)))

(defn ^:no-doc watch-xsql
  "Live result-set for a generated/compiled XSQL op map `{:op :source :entity}`
   — `watch-query` for codegen ops. Returns the same Promise<atom>; same
   options as `watch-query`, plus `:entities` to override the interest
   entirely (used by sql-template ops, whose `@watch` declares the
   entities to observe)."
  ([op-map params] (watch-xsql op-map params nil))
  ([op-map params opts]
   (let [run-opts (select-keys opts [:acting-as :key-format])
         run (fn [] (run-xsql op-map params run-opts))]
     (live-value* (:entity op-map) run opts))))

(defn close-watch!
  "Stop a handle from `watch`/`watch-schema` (a map with :close) or from
   `watch-query`/`watch-xsql` (an atom carrying :close in its metadata)."
  [h]
  (if (map? h)
    ((:close h))
    (when-let [c (:close (meta h))] (c)))
  nil)

(defn disconnect!
  "Destroy the connected client, server-restart style: stop the watch
   multiplexer's SSE listener, drop all watches, best-effort clear the
   server-side subscription set, and uninstall the client. No-op when not
   connected. `connect!` calls this on the previous client automatically.
   Returns a Promise."
  []
  (if-let [client core/*client*]
    (let [mux (:mux client)]
      (when-let [stop (:stop @mux)] (stop))
      (swap! mux assoc :watches {} :stop nil)
      (-> (try
            (reset! (:subs client) {:data {} :entities #{} :relations #{} :models #{}})
            (http/flush-subs!)
            (catch :default _ (p/resolved nil)))
          (p/catch (fn [_] nil))
          (p/then (fn [_]
                    ;; connect! calls disconnect! WITHOUT awaiting it (it's
                    ;; synchronous — changing that would break every
                    ;; existing caller's "connect! returns the client"
                    ;; contract). If THIS teardown's flush-subs! POST is
                    ;; still in flight when a reconnect happens, a bare
                    ;; `(set! core/*client* nil)` here would fire LATER and
                    ;; clobber the brand-new client that's since replaced
                    ;; this one. Only clear if nothing else has connected
                    ;; in the meantime.
                    (when (identical? core/*client* client)
                      (set! core/*client* nil))
                    nil))))
    (p/resolved nil)))
