# Synthigy Clojure SDK

Thin client for the Synthigy `/data` endpoint. Mirrors the shape of the
TypeScript SDK (`sdk/js`) — same auth contract, same operations, same
error codes.

**Scope:** service-to-service and server-side work (JVM Clojure, Babashka,
server-rendered CLJ frameworks, Datastar BFFs) **and** browser ClojureScript
apps via the [browser build](#browser-build-clojurescript). What it is *not*
is a stateful client cache — it's a thin fetch/watch layer over `/data`
(see "Who this SDK is not for").

One package, two runtimes. The pure helpers (op builders, selection / key
normalization, tree composition, result unpacking, `ok?`/`all-ok?`/`errors`)
are `.cljc` and shared. The client verbs and transport ship in both flavors,
side by side under `src/synthigy/client/`:

- **JVM / Babashka** (`.clj`) — synchronous, `babashka/http-client` transport.
  This is the default and what the rest of this README shows.
- **Browser / ClojureScript** (`.cljs`) — Promise-returning, `js/fetch` +
  SSE transport, built with shadow-cljs. Same surface; see
  [Browser build](#browser-build-clojurescript).

## Install

From Clojars — `deps.edn`:

```clojure
{:deps {com.synthigy/sdk {:mvn/version "0.1.0"}}}
```

Leiningen:

```clojure
[com.synthigy/sdk "0.1.0"]
```

Runtime deps are small and all Clojure-side: `babashka/http-client`,
`babashka/json`, `cheshire`, `org.clojure/core.async` and
`com.cognitect/transit-clj`. The ClojureScript build additionally pulls
`promesa`, `cljs-bean` and `transit-cljs`; on the JVM those resolve to
`.cljs` source jars that never load.

Under Babashka, `bb.edn` needs only the two babashka libs — transit,
core.async and JSON are built in.

## Quick start

```clojure
(require '[synthigy.client :as synthigy])

(synthigy/connect!
  {:endpoint "http://localhost:7887"
   :client-id "my-service"
   :client-secret "..."})

(synthigy/search :user
  {:-where {:active {:-eq true}}}
  {:name nil :roles [:name :active]})

(synthigy/sync :user {:name "alice" :active true})   ;; => {:count 1}
```

Writes are **silent by default** — `sync`/`stack` answer `{:count n}`, not the
record. Mint the id up front when you need it; cheaper than the echo, and a
retried write upserts the same row instead of duplicating it:

```clojure
(let [xid (synthigy.client.core/new-xid)]             ;; 22-char Base58
  (synthigy/sync :user {:xid xid :name "alice"})      ;; => {:count 1}
  (synthigy/sync :user {:xid xid :name "alice"}
                 :returning true))                    ;; => the written record
```

`connect!` installs the process-wide client (`*client*`) — a service talks
to ONE Synthigy, so no fn takes a client argument. For the rare second
endpoint or a test, `binding` `synthigy.client/*client*` around the calls.

Keys are kebab-case in both directions by default: you write
`{:published-on ...}` and responses come back as `:published-on`. The wire
is snake_case underneath; pass `:key-format nil` to `connect!` if you want
the raw wire keys, or `"camel"`.

### API surface at a glance

| Namespace | What | Use |
|---|---|---|
| `synthigy.client` | `connect!` + verbs: `search` `get` `sync` `stack` `delete` `batch`, XSQL `query`/`sql-template`, `watch-query`/`watch`/`watch-sql-template`/`close-watch!`, `history-*`, `schema`/`lint` | daily |
| *generated* (via `synthigy.gen`) | `(movie/list {...})` — typed ops from your `.xsql` | daily |
| `synthigy.client.core` | `create-client`/`*client*` (binding), `op-*` builders for `batch`, `results->data`/`ok?`, `compose-tree` | data helpers |
| `synthigy.client.subscriptions` | raw server subscription set | advanced — prefer watch |

The SDK is designed to be used via the `:as synthigy` alias — some
operations (`sync`, `get`) shadow `clojure.core`, so avoid `:refer`.

## Code generation

A folder of `.xsql` operation documents compiles to committed Clojure source —
one namespace per XSQL namespace, a documented fn per op (`(movie/list
{:since 1990})`), `watch-<name>` fns for `@watch` ops, and one-request batch
fns for `@batch`. The server is the only XSQL compiler (`op:describe`); the
generated code embeds each op's source string and ships no parser.

```bash
clj -X:gen :dir '"synthigy"' :out '"src"' :ns-prefix myapp.ops
```

Codegen runs under Babashka too — `bb gen` from a `bb.edn` task, same as on the
JVM. The schema snapshot it writes is byte-identical to the JS and Go
generators' output, so a polyglot repo keeps one artifact.

### Codegen authenticates as THE APP — not as you

`/schema` and `describe` are IAM-filtered **per principal**, so whoever pulls
the schema defines the generated surface. Generate with the app's own OAuth
client credentials and the generated contract is exactly what the app can do
at runtime; generate with a developer's personal identity and you ship types
the app will 403 on. Client credentials are also the only path that works
headless in CI (schema-drift checks on every PR).

Provision the client once per project (core nREPL, or the modeler UI):

```clojure
(require '[synthigy.iam :as iam])
(iam/add-client {:id "my-app" :name "My App" :type :confidential
                 :secret "..." :active true
                 :settings {"allowed-grants" ["client_credentials" "refresh_token"]
                            "redirections" ["http://localhost"]
                            "trusted" true}})
```

Grant the client's SERVICE user the roles your app needs, plus the
**`schema:read`** scope for codegen introspection (`dataset:load` — the model
tooling scope — also passes). Keep the secret in `.envrc` / CI secrets, like a
database URL:

```bash
export SYNTHIGY_CLIENT_ID=my-app
export SYNTHIGY_CLIENT_SECRET=...
```

Regenerate whenever an `.xsql` changes — the diff of the generated file shows
exactly what changed. Commit inputs and output.

## Authentication

This is the part worth understanding before you ship. The model is
**pull-based and lazy**: there is no background thread, no scheduled
refresh. A token is fetched the first time the SDK needs one, cached,
and refetched when either:

1. **The local clock says it's about to expire** — 30-second buffer by
   default. Proactive guess based on `expires_in` in the token response.
2. **The server returns 401 mid-request** — reactive safety net for key
   rotation, revocation, clock skew. The SDK invalidates the cache and
   retries once.

The SDK **does not decode JWTs**. Expiry is whatever the authorization
server told us at fetch time. If your server omits `expires_in`, we
assume 3600 seconds.

### Three ways to supply auth

**1. Convenience (OAuth2 client credentials — 95% of cases):**

```clojure
(synthigy/connect!
  {:endpoint "http://localhost:7887"
   :client-id "my-service"
   :client-secret "..."})
```

SDK builds an `auth/oauth` provider, caches tokens per-audience in a
closure-local atom, refreshes 30s before expiry.

Add `:audience` (JVM default: `$SYNTHIGY_AUDIENCE`) to bind one audience to
every mint this client makes. The platform's audience model is **opt-in by
design** — a token minted naming no audience resolves to an identity-only
audience that `/data` rejects, so without this every data call 401s. Set it to
the server's `/data` audience, published at `/.well-known/synthigy` as
`auth.oidc.audience`. Left unset the SDK names no audience, so an unentitled
client keeps a soft 401 rather than a hard `invalid_target`. `client/token`
with `:audience` still overrides per call.

**2. Static token (tests, scripts, pre-issued tokens):**

```clojure
(synthigy/connect!
  {:endpoint "http://localhost:7887"
   :token "eyJ..."})
```

No refresh. No cache invalidation. On 401 you get `UNAUTHORIZED` —
your problem to handle.

**3. Bring your own token source (anything custom — Vault, keychain,
shared cache, IMDS, etc.):**

```clojure
(synthigy/connect!
  {:endpoint "http://localhost:7887"
   :token-fn     #(my-token-source)
   :invalidate-fn #(my-token-source-clear!)})
```

`:token-fn` is 0-arg (returns a bearer string). Optional 1-arg variant
receives an `audience` for IdP federation. `:invalidate-fn` is how the
SDK signals "that token got rejected, drop it from your cache and give
me a fresh one next call". Omit it and 401s propagate without retry.

**4. Nothing at all — let the environment answer (JVM only):**

```clojure
(synthigy/connect! {:endpoint "http://localhost:7887"})
```

With none of the above supplied, `create-client` continues resolving:
under `SYNTHIGY_SUPERVISED=1` it asks its supervising parent (`synthigy
exec`/`agent`, or a robotics commander) for a token over the process's
own stdio, then falls back to the `SYNTHIGY_TOKEN` env var, then throws
`(ex-info ... {:code "NO_TOKEN"})` with a message that teaches the fix.
The pipe beats the env var deliberately: `exec` injects the cached token
*and* supervises, and only the pipe can refresh mid-run — so a bot
written this way runs unchanged bare, under `exec`, and under a
production commander. CLJS has neither env vars nor a stdio parent, so in
the browser one of options 1–3 is always required. See
`docs/plans/PLAN-EXEC-IDENTITY.md`.

### Explicit provider

For full control without the sugar:

```clojure
(require '[synthigy.client.auth :as auth])

(def provider (auth/oauth {:token-url "https://idp.example.com/oauth/token"
                           :client-id "..."
                           :client-secret "..."
                           :token-buffer 30}))

(synthigy/connect! (merge {:endpoint "http://localhost:7887"} provider))
```

### Running in the same JVM? Use `synthigy.embedded` instead

This SDK is a *client* — it exists to talk to a Synthigy over HTTP. If the
engine runs in your own process there is nothing to talk to, so reach for
**`synthigy.embedded`** (in core) rather than pointing this client inward:

```clojure
(require '[synthigy.embedded :as synthigy]
         '[synthigy.embedded.filter :as f])

(synthigy/search :user {:-where {:active (f/eq true)}} [:name])
(synthigy/sync   :user {:name "alice" :active true})
(synthigy/search "Human" nil [:first-name] :acting-as some-user-xid)
```

Same verb names, same argument order, same selection shorthand, kebab-case
both ways, `:acting-as` per call — dispatched straight at the engine, with no
client, no `connect!` and no wire. Kebab-case and `:acting-as` are *server*
features anyway (this SDK only sets `key_format` and `acting_as` in the
request body), so nothing is lost by not going through a client.

See that namespace's docstring for what it short-circuits (the wire envelope
and the batch scheduler) and what it deliberately keeps (the `mutate` hook
chain, argument coercion, audit rows).

### Where tokens are stored

| Provider | Storage | Lifetime |
|---|---|---|
| `auth/oauth` | Closure-local atom, keyed by audience | JVM process |
| `auth/static` | Closed-over string | JVM process |
| BYO `:token-fn` | Whatever you use | Your problem |

**Nothing is persisted to disk.** If you need cross-process token sharing
(e.g. for horizontal scaling), implement it behind BYO `:token-fn`.

### Per-audience tokens (IdP federation)

When Synthigy acts as the IdP for a downstream service:

```clojure
(synthigy/token {:audience "robotics"})
;; => "eyJ..."
```

Use the returned token as a `Bearer` header against the downstream
service directly — the SDK has no opinion on what that service looks like.

## Operations

All operations share the pattern `(op client entity ...)`. Single-op
helpers return the data or throw `ExceptionInfo`. Batch mode returns
raw result maps for inspection.

| Op | Purpose |
|---|---|
| `search` | List entities matching `args`, with selection |
| `get` | Single entity by unique constraint |
| `sync` | Upsert — create or update by identity fields |
| `stack` | Append to collection-valued relations without replacing |
| `delete` | Delete specific records |
| `purge` | Delete-where + return deleted data |
| `slice` | Unlink relation without deleting |
| `sql-template` | ERD-aware SQL with `{entity.field}` placeholders |
| `search-tree` | Walk self-FK UP to ancestors |
| `get-tree` | From root, return descendants |

Every op supports kwargs `:acting-as` (impersonation) and `:key-format`
(per-call override of response casing).

Beyond the table, `query`/`lint` (XSQL selection-DSL + diagnostics),
`deployed-model`/`runtime-model` (introspection), and the `history-*`
temporal ops (`get-at`, `events`, `diff`, `timeline`, `since`) are also
available — see their docstrings.

### Filters

`synthigy.client.filter` mirrors the Go/JS condition helpers. In Clojure
these are thin sugar over plain maps (`{:_eq v}`) — the combinators earn
their keep:

```clojure
(require '[synthigy.client.filter :as f])

(synthigy/search :user
  {:-where {:active (f/eq true) :age (f/gt 18)}} [:name])

(synthigy/search :user
  {:-where (f/or {:role (f/eq "admin")} {:role (f/eq "owner")})} [:name])
```

### Batch

The op builders and result helpers live in `synthigy.client.core` — only
`batch` itself is a network verb on `synthigy.client`:

```clojure
(require '[synthigy.client.core :as core])

(let [ops [(core/op-sync :user {:name "alice"})
           (core/op-search :user nil [:name])
           (core/op-search :user_role nil [:name])]
      [synced users roles] (core/results->data
                             (synthigy/batch ops) ops)]
  (when (core/all-ok? [synced users roles])
    (println synced users roles)))
```

Failed operations come back as `ExceptionInfo` in their slot — use
`core/ok?`, `core/all-ok?`, `core/errors` to triage.

### Selection shorthand

```clojure
[:name :email]              ; → {:name nil :email nil}
{:roles {:name nil}}        ; nested map
{:roles [:name :active]}    ; vector of fields
[:name {:roles [:name]}]    ; mixed
```

Kebab-case keys in `args`/selection are normalized to snake_case for the
wire. Response casing is controlled by `:key-format` (client-level or
per-call): `"kebab"`, `"camel"`, or `nil` for raw snake_case.

### Trees

`search-tree`/`get-tree` are network verbs on `synthigy.client`; the
`compose-*` shapers are pure helpers on `synthigy.client.core`:

```clojure
(def records
  (synthigy/search-tree :person :father
    {:-where {:name {:-eq "Bart"}}}
    [:xid :first-name {:father [:xid]}]))

(core/compose-tree records {:on :father})
;; => {:xid ... :first-name "Homer" :children [{:first-name "Bart" ...}]}
```

`core/compose-forest` handles multiple independent roots.

## Live data — watch, watch-query & watch-sql-template

The recommended live layer. All watches on a client share **one** SSE stream
and **one** consolidated server-side subscription (their interests are
unioned by an internal multiplexer, re-asserted on every reconnect).

`watch-query` — a live, RLS-scoped result set as an **atom**:

```clojure
(def msgs (synthigy/watch-query "Chato Message" nil [:content :published-on]
                                :acting-as user-xid))
@msgs                                        ; current rows
(add-watch msgs :ui (fn [_ _ _ rows] ...))   ; react to changes
(synthigy/close-watch! msgs)                 ; stop
```

Scope it to a "room" instead of the whole entity — record-interest plus
row tracking (edits/deletes of visible rows still fire):

```clojure
(synthigy/watch-query "Chato Message" nil [:content]
                      :acting-as user-xid
                      :entity-track false
                      :records [group-xid]
                      :track-rows true)
```

Raw change events without a result set:

```clojure
(def w (synthigy/watch {:entities ["user"]} (fn [ev] (prn ev))))
((:close w))

(synthigy/watch-schema (fn [ev] (prn :model-deployed)))   ; deploy notices
```

Events are notification-only — watches refetch on receipt
(notify-then-refetch keeps RLS correct).

### Raw stream + subscription set (advanced)

`synthigy.client/listen` streams `/data/events` yourself (blocks — wrap in
`future`; auto-reconnects with `Last-Event-ID` + backoff). The raw
subscription-set helpers live in **`synthigy.client.subscriptions`**
(`subscribe`, `subscribe-entity`, `set-subscriptions`, ...).

**Do not mix them with watches on the same client**: the server keeps ONE
full-replace subscription set per client identity, so raw calls clobber the
multiplexer's set (and vice versa) — live updates silently stop.

### Watching a SQL template

A template has no root entity, so the entities to observe must be named:

```clojure
(def stats (synthigy/watch-sql-template
             "SELECT count(*) AS n FROM {movie}" nil
             :entities ["Movie"]))
@stats
(synthigy/close-watch! stats)
```

A codegen'd `@watch` sql-template emits an equivalent `watch-<name>` that
carries the entities from the directive, so this is the hand-written route.

## Instrumentation

```clojure
(synthigy/create-client
  {...
   :on-request  (fn [req]     (log/debug "→" (:method req) (:url req)))
   :on-response (fn [req res] (log/debug "←" (:status res) (:elapsed-ms res) "ms"))
   :on-error    (fn [req err] (log/warn err "request failed"))})
```

Hooks fire **per attempt** — a 401 retry fires them twice. Hook
exceptions are caught and logged to `*err*`; they never bubble into
the caller.

## Retry

Opt-in helper for transient failures on idempotent reads.

```clojure
(require '[synthigy.client.retry :as retry])

(retry/with-retry
  (fn [] (synthigy/search :user nil [:name]))
  {:max-attempts 3
   :backoff-ms 500
   :max-backoff-ms 10000
   :on-retry (fn [attempt err delay] (log/info "retry" attempt "after" delay "ms"))})
```

Retryable: `TRANSPORT_ERROR` (network/timeout/DNS) and `HTTP_ERROR` with
status 502/503/504. Everything else (4xx, `UNAUTHORIZED`, operation
errors) propagates immediately.

**Do not** wrap `sync`, `delete`, `purge` or other writes unless you've
reasoned about double-apply under a server-side timeout that still
committed.

## Errors

All errors are `ExceptionInfo` with `:code` in `ex-data`:

| Code | Meaning |
|---|---|
| `CONFIG_ERROR` | `create-client` options are bad |
| `TRANSPORT_ERROR` | Network error — no HTTP response |
| `HTTP_ERROR` | Non-2xx response; `:status` carries the code |
| `UNAUTHORIZED` | Second 401 after invalidate-retry |
| `UNKNOWN_ENTITY` | Entity name not in deployed model |
| Server-issued codes | Whatever the `/data` endpoint returned |

Batch results wrap failures as `ExceptionInfo` in the result vector —
use `(errors [...])` to filter or `(all-ok? [...])` to short-circuit.

## Testing

```bash
# Unit tests (no server needed)
clj -M:test

# Integration tests (needs running Synthigy with a test OAuth client)
SYNTHIGY_TEST_ENDPOINT=http://localhost:7887 \
SYNTHIGY_TEST_CLIENT_ID=test-sdk \
SYNTHIGY_TEST_CLIENT_SECRET=test-secret \
  clj -M:integration
```

## Browser build (ClojureScript)

The browser client lives in this same package — the `.cljs` verb files and
transports (`http`/`sse`/`transit`/`auth`/`retry`) sit next to their `.clj`
siblings, sharing the `.cljc` helpers. It compiles with **shadow-cljs**, whose
config (`shadow-cljs.edn`) and CLI (`package.json`) are in this folder. The
build reads sources straight from `deps.edn` (`:dev` alias).

```bash
npm install            # pull the shadow-cljs CLI (one-time)
npm test               # compile the :test build → node out/test.js
npm run test:watch     # watch mode
npx shadow-cljs cljs-repl <build>   # browser REPL
```

Consume it from your own shadow-cljs project by adding this folder to the
build's source paths (via `:deps {:aliases [...]}` in your `shadow-cljs.edn`,
or a `:local/root` dep) — same `synthigy.client` namespace, same verbs.

```clojure
(require '[synthigy.client :as synthigy]
         '[promesa.core :as p])

(synthigy/connect!
  {:endpoint "http://localhost:7887"
   :token-fn (fn [] (my-app/get-access-token))})   ; see "Auth in the browser" below

(p/let [users (synthigy/search :user nil [:name])]
  (js/console.log (clj->js users)))
```

Every network verb returns a **Promise** instead of blocking; the data helpers
(`op-*`, `results->data`, `compose-tree`, `ok?`) are identical to the JVM side.
The browser transport defaults to **JSON**; pass `:wire-format :transit` to
`connect!` for EDN-native values (keywords, sets, instants) end to end.

### Auth in the browser

**This SDK ships no auth code** — the `{:token-fn :invalidate-fn}` seam from
the [Authentication](#authentication) section is the entire browser story too.
That's deliberate: a browser app is (or should be) a **public** OAuth client —
there is nowhere to hide a `client-secret`, so the `:client-id`+`:client-secret`
convenience path from the JVM section does not apply here, and `create-client`
will refuse it without a secret. Bring your own token source instead:

```clojure
(synthigy/connect!
  {:endpoint "http://localhost:7887"
   :token-fn      (fn [] (my-auth/get-access-token))
   :invalidate-fn (fn [] (my-auth/force-renew!))})   ; called once on a 401, then retried
```

Two real integrations, depending on what you already have:

- **Already have a backend** (BFF / server that mints tokens for the SPA)?
  Use it — `:token-fn` just reads whatever your host page already has, same
  as the JVM SDK's convenience path but with an OAuth flow you control
  yourself. This is the recommended default; `acting_as` (identity
  multiplexing) is a confidential-client/BFF feature and the server rejects
  it outright for public clients (`PUBLIC_CLIENT_FORBIDDEN`) — with a BFF you
  keep that option.
- **No backend — the browser talks to Synthigy directly** as a public OAuth
  client (`authorization_code` + PKCE). Wire a library like
  [`oidc-client-ts`](https://github.com/authts/oidc-client-ts) — its
  `UserManager` with `automaticSilentRenew: true` and
  `userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() })`
  keeps tokens in memory only (never `localStorage`/`sessionStorage`) and
  silently refreshes via a hidden iframe against the IdP session cookie —
  no refresh token ever reaches the browser:

  ```js
  import { UserManager, WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts'

  const userManager = new UserManager({
    authority: 'http://localhost:7887',
    client_id: 'my-spa',                 // a PUBLIC client — no secret, ever
    redirect_uri: window.location.origin + '/callback',
    response_type: 'code',               // authorization_code + PKCE
    automaticSilentRenew: true,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  })

  window.SYNTHIGY_TOKEN_FN = async () => {
    const user = await userManager.getUser()
    return user?.access_token
  }
  window.SYNTHIGY_INVALIDATE_FN = () => userManager.signinSilent()
  ```

  ```clojure
  (synthigy/connect!
    {:endpoint "http://localhost:7887"
     :token-fn      #(js/window.SYNTHIGY_TOKEN_FN)
     :invalidate-fn #(js/window.SYNTHIGY_INVALIDATE_FN)})
  ```

  Silent renew needs the SPA served same-site with Synthigy (the renew
  iframe carries the IdP session cookie — third-party-cookie contexts are
  increasingly blocked by browsers). Cross-site deployments fall back to a
  visible re-login when the token expires.

### Live data in the browser

Same `watch`/`watch-query`/`watch-schema`/`close-watch!` surface as the JVM
SDK — one shared multiplexed SSE stream, interests unioned, re-asserted on
every reconnect. The one real difference: **`watch-query` returns a
`Promise` of the atom** (cljs is async throughout, so the first snapshot must
be awaited before there's anything to deref):

```clojure
(p/let [msgs (synthigy/watch-query :chat_message nil [:content :published-on])]
  (add-watch msgs :ui (fn [_ _ _ rows] (render! rows)))
  (js/console.log (clj->js @msgs)))          ; current rows, right now

(synthigy/close-watch! msgs)
```

`watch`/`watch-schema` are synchronous (no network round trip needed before
you have a handle) and return the same `{:interest :set-interest :add
:remove :close}` shape as the JVM client.

## Who this SDK is not for

**Apps that want a stateful client store** — a normalized in-memory cache
with optimistic mutations, change tracking, and batched commit. This SDK is
a thin fetch/watch layer over `/data`, not a store; layer your own cache on
top if you need one.

If you're bypassing the SDK entirely and talking Transit straight (as
`frontend/modeling` historically does), that still works — but the browser
build with `:wire-format :transit` is now a first-class option.

This SDK is the right tool when:
- You're writing a Clojure service that talks to Synthigy (CRUD,
  batch, `sql-template`).
- You're writing a Babashka script or a Datastar BFF.
- You need SSE subscriptions (`/data/events`) from a server process.
- You're driving Synthigy-as-IdP federation from a CLJ service
  (`client/token` with `:audience`).

## What this SDK does not do

- **No JWT decoding.** Expiry is whatever the authorization server said.
- **No token persistence.** Cross-process sharing is BYO.
- **No background refresh loop.** Refresh is pull-based on the next call.
- **No automatic write retries.** Retry policy is opt-in and limited
  to idempotent reads.
- **No normalized client cache.** The SDK is a thin fetch/watch layer on
  both runtimes. Apps wanting a store (optimistic mutations, change
  tracking) build one on top.

## License

MIT — see [LICENSE](LICENSE). The SDKs are permissive client libraries; the
Synthigy engine is fair-code under the Sustainable Use License.
