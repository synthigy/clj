# Synthigy Clojure SDK

Thin client for the Synthigy `/data` endpoint. Mirrors the shape of the
TypeScript SDK ([`@synthigy/sdk`](https://github.com/synthigy/js)) — same auth contract, same operations, same
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

(synthigy/connect!)                         ; endpoint + identity from `synthigy exec`

;; XSQL: the shape you write is the shape you get back
(synthigy/query "
movie (release_year > ?since:int, limit 10)
  xid
  title
  ->genres
    name" {:since 1990}
                :acting-as user-xid)          ; per-call impersonation

(synthigy/sync :movie {:title "Dune" :release-year 2021})   ;; => {:count 1}
```

Run it with `synthigy exec -- clj -M -m myapp.main`.

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
| `synthigy.client` | `connect!` + XSQL `query`/`sql-template`, writes `sync` `stack` `delete`, `batch`, `watch-sql-template`/`watch`/`close-watch!`, `history-*`, `schema`/`lint`/`compile` | daily |
| *generated* (via `synthigy.gen`) | `(movie/list {...})`, `(movie/watch-list {...})`, `@batch` fns — from your `.xsql` | daily |
| `synthigy.client.core` | `create-client`/`*client*` (binding), `op-*` builders for `batch`, `results->data`/`ok?`, `compose-tree` | data helpers |
| `synthigy.client.subscriptions` | raw server subscription set | advanced — prefer watch |

The SDK is designed to be used via the `:as synthigy` alias — some
operations (`sync`, `get`, `compile`) shadow `clojure.core`, so avoid `:refer`.

## Code generation

Write your queries in `.xsql` files and get typed functions for them. The
server compiles the queries, so the types always match what it returns.

**1. Get a server.** In your project folder:

```bash
synthigy env init
synthigy up
```

The first `up` prints a `/setup` link; open it and pick a database. (No
browser? `synthigy up --db sqlite` skips the wizard.) Already have a server?
Skip this step.

**2. Deploy your data model** in the modeler (or from code with `synthigy.client/deploy`, as a
client with the Dataset Developer role).

**3. Connect as your app.** Create its client once, then save it to the
project:

```bash
synthigy iam add-client "My App" --id my-app --type confidential \
  --role "Dataset Explorer" --api Synthigy --grant client_credentials --local
synthigy connect http://localhost:7887 --client-id my-app
```

`add-client` prints the secret once; `connect` asks for it. Code is generated
for what this app is allowed to see. (`--local` works on the server's own
machine; for a remote server, create the client in the console.)

**4. Install the SDK:**

```clojure
{:deps {com.synthigy/sdk {:mvn/version "0.1.0"}}}   ; deps.edn
```

**5. Write a query** in `xsql/movies.xsql`:

```
@search list
movie (release_year > ?since:int=1990, limit ?limit:int=20)
  xid
  title
  release_year
```

**6. Generate:**

```bash
synthigy exec -- clj -X synthigy.gen/generate :ns-prefix myapp.ops
```

This writes one namespace per XSQL namespace under `src/`, e.g. `src/myapp/ops/movie.clj`, and saves `xsql/schema.json` and `xsql/ops.ir.json`
next to your queries.

**7. Use it:**

```clojure
(ns myapp.main
  (:require [synthigy.client :as c]
            [myapp.ops.movie :as movie]))

(defn -main []
  (c/connect!)
  (println (map :title (movie/list {:since 2000}))))
```

```bash
synthigy exec -- clj -M -m myapp.main
```

`synthigy exec` gives your program the server address and the app's identity.
Without it, pass them yourself: `(c/connect! {:endpoint ... :client-id ... :client-secret ...})`.

**After you edit a query**, run step 6 again. As long as the `.xsql` files are
unchanged it works offline from `xsql/ops.ir.json`; after an edit it needs the
server, and it never generates from outdated results. In CI:

```bash
clj -X synthigy.gen/check :ns-prefix myapp.ops
```

**What to commit:** your `.xsql` files and `xsql/ops.ir.json`.
`xsql/schema.json` is your whole data model, so commit it only in a private
repo.

`@watch` ops also get `watch-<name>`, and `@batch` ops become one-request fns. In a REPL, `(synthigy.gen/watch! {:ns-prefix "myapp.ops"})` regenerates and reloads on every `.xsql` save. Runs under Babashka too.

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
the browser one of options 1–3 is always required.

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
(require '[synthigy.embedded :as synthigy])

(synthigy/query "user (limit 3)\n  name" nil :acting-as some-user-xid)
(synthigy/sync  :user {:name "alice" :active true})
```

Same verb names, same argument order, kebab-case
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

### Logging users in (`login-start` / `login-complete`)

Authorization code + PKCE for a confidential server (BFF), JVM (the browser has its own, see [Auth in the browser](#auth-in-the-browser)): the SDK
owns the protocol, your app owns sessions, cookies and routing. `login-start`
returns a URL, `login-complete` takes the callback's `code`/`state`; the
handlers are yours. The in-flight login lives in a `:login-store` you supply —
`{:put-fn (fn [state login]) :take-fn (fn [state])}`, `take-fn` one-shot.

```clojure
(require '[synthigy.client.login :as login])

(c/connect! {:endpoint "http://localhost:7887"
             :client-id "my-bff" :client-secret (System/getenv "BFF_SECRET")
             :login-store (login/memory-login-store)})

;; GET /login
(redirect (:url (c/login-start callback-url :return-to "/movies")))

;; GET /auth/callback?code=…&state=…  (or ?error=… when the user cancelled)
(if error
  (redirect (:return-to (c/login-cancel state) "/"))
  (let [{:keys [user tokens return-to]} (c/login-complete code state callback-url)]
    ;; user = {:xid :name :scopes}; your session, your cookie
    (redirect return-to)))
```

- `(:xid user)` is read from the id_token — no `/data` lookup. Pass it as
  `:acting-as` to act on the user's behalf.
- `memory-login-store` is **single process**: behind a load balancer the
  callback can land on an instance that never saw `/login`
  (`LOGIN_STATE_UNKNOWN`). Back the store with what holds your sessions.
- No store → `NO_LOGIN_STORE`; no `:client-secret` →
  `LOGIN_REQUIRES_CONFIDENTIAL_CLIENT`. Other codes: `LOGIN_NONCE_MISMATCH`,
  `LOGIN_EXCHANGE_FAILED` (with `:status`).
- `:public-endpoint` on `login-start` when the browser reaches the IdP on a
  different URL than this process does (containers, reverse proxies).
- The code exchange never retries: an authorization code is one-shot.

## Operations

Every op returns the data or throws `ExceptionInfo`, and takes the kwargs
`:acting-as` (impersonation) and `:key-format` (per-call response casing).

| Op | Purpose |
|---|---|
| `query` | XSQL read — `:op "get"` for one record |
| `sql-template` | ERD-aware SQL with `{entity.field}` placeholders |
| `sync` | Upsert — create or update by identity fields |
| `stack` | Append to collection-valued relations without replacing |
| `delete` | Delete specific records |
| `batch` | Several operations in one request |

Also available: `slice`/`purge`, `search-tree`/`get-tree`,
`lint`/`compile`, `deployed-model`/`runtime-model`, and the `history-*`
temporal ops (`get-at`, `events`, `diff`, `timeline`, `since`) — see their
docstrings.

### Filters, sorting, relations

All of it is written in the query:

```clojure
(synthigy/query "
movie (release_year >= ?from:int, order by title asc, limit 20)
  title (ilike ?q:string=\"%\")
  ->genres
    name" {:from 2000 :q "%dune%"})
```

- `?name:type=default` are named params, passed as a map.
- `->genres` is a **left** pull — a movie with no genres is still returned;
  `-genres` is **inner** and keeps only movies that have one.

### Batch

An `@batch` in your `.xsql` generates a function that sends every member in
one request and returns them keyed by name; a failed member is an
`ExceptionInfo` in its slot:

```clojure
(let [{:keys [list stats]} (movie/overview {:limit 3})]
  ...)
```

## Live data — generated watches, watch-sql-template & watch

The recommended live layer. All watches on a client share **one** SSE stream
and **one** consolidated server-side subscription (their interests are
unioned by an internal multiplexer, re-asserted on every reconnect).

An op marked `@watch` in your `.xsql` generates a `watch-<name>` fn — a live,
RLS-scoped result set as an **atom**:

```clojure
(def live (movie/watch-list {:limit 5} :acting-as user-xid))
@live                                        ; current rows
(add-watch live :ui (fn [_ _ _ rows] ...))   ; react to changes
(synthigy/close-watch! live)                 ; stop
```

Scope it to a "room" instead of the whole entity — record-interest plus
row tracking (edits/deletes of visible rows still fire):

```clojure
(message/watch-list {:room room-xid}
                    :acting-as user-xid
                    :entity-track false
                    :records [room-xid]
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
             :entities ["movie"]))
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
  (fn [] (synthigy/query "movie (limit 5)\n  title" nil))
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

All errors are `ExceptionInfo`. `ex-data` carries `:code` (stable — branch on
it, never on the message), `:category` and `:retryable`, from the same code
table as the Go, JS, Python and PHP SDKs:

| `:category` | Meaning | `:retryable` |
|---|---|---|
| `auth` | Token, session or IdP problem — re-authenticate | no |
| `iam` | RBAC/RLS denial | no |
| `validation` | The request (or client config) is wrong | no |
| `not_found` | Unknown entity, relation or template | no |
| `conflict` | Constraint violation | no |
| `rate_limit` | Too many requests, `TIMEOUT` | yes |
| `network` | No HTTP response | yes |
| `internal` | Unexpected server error; also any code the table doesn't know | yes |

```clojure
(try (c/search :movie nil [:title])
     (catch clojure.lang.ExceptionInfo e
       (let [{:keys [code retryable]} (ex-data e)]
         (if retryable (retry-later) (throw e)))))
```

Common codes:

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

# Live browser login, played headlessly (confidential client + password user)
SYNTHIGY_TEST_LOGIN_CLIENT_ID=… SYNTHIGY_TEST_LOGIN_CLIENT_SECRET=… \
SYNTHIGY_TEST_LOGIN_USER=… SYNTHIGY_TEST_LOGIN_PASSWORD=… \
  clj -M:login-integration
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

(p/let [movies (synthigy/query "movie (limit 5)\n  title" nil)]
  (js/console.log (clj->js movies)))
```

Every network verb returns a **Promise** instead of blocking; the data helpers
(`op-*`, `results->data`, `compose-tree`, `ok?`) are identical to the JVM side.
`/data` speaks **transit** in both builds (JVM and browser) — EDN-native
values end to end: keywords, sets, instants as real `js/Date`, and the
engine's `_agg` BigDecimals as JS numbers (the reader carries `f`/`n`
handlers; transit's default hands back an opaque tagged value). Sibling
endpoints that force JSON server-side (`/schema`, `/lint`) are decoded as
JSON, by response Content-Type. There is no `:wire-format` option.

### Auth in the browser

A browser is a **public** OAuth client: nowhere to hide a secret, so
`auth/oauth` (client credentials) throws there. `synthigy.client.login` logs
the user in with authorization code + PKCE and keeps the session alive by
silent renew, the same model as oidc-client-ts: tokens in memory only, no
refresh token, a hidden iframe re-authorizes with `prompt=none` against the
Synthigy session cookie.

```clojure
(require '[synthigy.client :as synthigy]
         '[synthigy.client.login :as login])

;; first thing on the silent redirect page — answers the renewal iframe
(when-not (login/silent-callback!)
  (login/configure! {:endpoint "http://localhost:7887"
                     :client-id "my-spa"               ; a PUBLIC client
                     :redirect-uri (str js/location.origin "/callback")
                     :audience "https://synthigy.com"})
  (synthigy/connect! (merge {:endpoint "http://localhost:7887"} (login/provider)))
  (if (login/callback?)
    (p/let [{:keys [return-to]} (login/login-complete)] (navigate! return-to))
    (-> (login/renew!)                                  ; restore after a reload
        (p/catch (fn [_] (show-login-button!))))))

;; the button
(login/login-start :return-to "/movies")
```

- `redirect-uri` and `silent-redirect-uri` (defaults to `redirect-uri`) must be
  registered on the client. A tiny static page that only calls
  `silent-callback!` as the silent redirect keeps the renewal iframe from
  loading your whole app.
- Renewal runs 60s before expiry. Token lifetime is the server's.
- Events via `(login/on event f)`: `:user-loaded`, `:user-unloaded`,
  `:access-token-expiring`, `:access-token-expired`, `:silent-renew-error`.
- `login/user` is the id_token's claims, merged with `/oauth/userinfo` when
  `:load-user-info? true` (off by default, as in oidc-client-ts — the id_token
  carries `sub`/`xid`, the profile fields such as `name` come from userinfo); `login/logout!` ends the server
  session; `login/remove-user!` forgets it locally.
- On a 401 the provider starts a renewal and the SDK's retry waits for it.
- **Cross-site**: when your app and Synthigy are on different sites, Safari,
  Firefox and Chrome-with-third-party-cookies-off hide the session cookie from
  the iframe, so renewal answers `login_required`. The SDK does not redirect on
  its own — handle `:silent-renew-error`, typically with
  `(login/login-start :prompt "none")`: a top-level redirect is first-party, so
  a live session bounces straight back without a login form.
- Codes: `LOGIN_STATE_UNKNOWN`, `LOGIN_NONCE_MISMATCH`, `LOGIN_EXCHANGE_FAILED`,
  `SILENT_TIMEOUT`, or the authorization error itself (`login_required`,
  `access_denied`).

`acting_as` stays a confidential-client (BFF) feature; with a public client the
user is the principal and RLS binds to the token itself.

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
