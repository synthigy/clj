# Changelog

All notable changes to `com.synthigy/sdk` (Clojars). Follows
[semver](https://semver.org). Pre-1.0: breaking changes can land on minor bumps.

## 0.2.0

### Added
- **Codegen:** each `.xsql` file is sent to `describe` separately (`sources: [{path,
  source}]`) instead of one concatenated text. Concatenation leaked the
  first file's buffer-level `@namespace` into every later file and dropped
  the others'. Needs an engine with per-file `describe`. A `(namespace,
  name)` declared twice across files now fails with `DUPLICATE_OPERATION`,
  naming both files; a `@batch` naming an op from another file fails with
  `BATCH_MEMBER_UNRESOLVED`. Both are `validation` in the error table.
  `op-describe` accepts `[{:path :source}]`.
- **Typed errors.** Every coded throw's `ex-data` now carries `:category`
  and `:retryable` beside `:code`, from the code table the other four SDKs
  share (`synthigy.client.error`), on the JVM, in the browser and in
  babashka. Server errors keep their own fields. `watch-sql-template` without
  `:entities` now has a code, `MISSING_ENTITIES`.
- **Browser login: `login-start` / `login-complete` / `login-cancel`** (JVM) —
  OIDC authorization code + PKCE for a confidential client, same contract as
  the JS and Python SDKs. The in-flight login lives in a `:login-store`
  (`synthigy.client.login/memory-login-store` for one process); the user's
  `:xid` comes from the id_token. `create-client` now keeps `:client-id` and
  the secret (behind a fn) for the code exchange.
- **Browser login: `synthigy.client.login` (CLJS)** — public client, code +
  PKCE, silent renew through a hidden `prompt=none` iframe, tokens in memory
  only; the oidc-client-ts model without the dependency. `(login/provider)`
  plugs into `connect!`, and a 401 retry waits for the renewal it starts.
- **`:endpoint` defaults to `SYNTHIGY_ENDPOINT` (JVM), and `connect!` has a
  zero-arity form.** Under `synthigy exec` that and the identity are already
  set, so `(c/connect!)` is the whole setup.
- **`compile`** — `synthigy.client/compile` posts an XSQL source to the new
  `POST /compile` endpoint and returns the wire operation map the engine
  would execute (`{:op :entity :args :selections}`), without executing it.
  Params bind exactly as on `query`, so the map returned is what the engine
  receives; edit it and send it through `batch` for programmatic control
  instead of hand-writing wire JSON. Available in the browser build too
  (returns a Promise). Shadows `clojure.core/compile` — the ns already
  excludes `sync`/`get`.

### Changed
- **Codegen no longer falls back to `http://localhost:7887`.** With no
  endpoint passed and no `SYNTHIGY_ENDPOINT`, a run that needs the server
  fails with `NO_ENDPOINT`, as the client does; offline runs from a current
  `ops.ir.json` are unaffected.
- **A missing endpoint throws `NO_ENDPOINT`** (category `validation`), the
  code the other four SDKs use; it was `CONFIG_ERROR`.
- **`auth/oauth` (client credentials) throws in a browser** (`CONFIG_ERROR`) —
  the secret would ship to every visitor. It still works under Node.
- **Codegen reads and writes `xsql/ops.ir.json`**, the IR file every other
  SDK's generator shares. While it matches the `.xsql` sources, `generate` and
  `check` run with no server; after an edit it is refreshed live and never
  generated from stale, and with no server reachable the run fails naming the
  edit instead of a bare `Unauthorized`. `:pull true` forces a refresh. `check`
  never writes it.
- **`:dir` defaults to `"xsql"`** in `generate`, `check`, `watch!` and
  `pull-schema!`, the directory every example uses (was `"synthigy"`).

### Fixed
- **Generated `@batch` functions returned an error for every XSQL member.**
  `op-xsql` still sent a read as `{:op "search" :selections <source>}`, which
  the server refuses (`INVALID_SELECTIONS`); only sql-template members worked.
  It now sends the `xsql` document op, as `query` does. Regenerate nothing —
  the fix is in the SDK the generated code calls.
- **Browser client: `~f` / `~n` decode to JS numbers.** The engine coerces
  every `_agg` leaf to a BigDecimal (SQLite/Postgres parity), which transit
  writes as `~f`. transit-js's built-in decoder turns that into an opaque
  tagged value, so an aggregate read back in ClojureScript was a JS object
  (`{tag, rep, hashCode}`) rather than a number — it stringified as one, too.
  The `/data` reader now carries `{"f" parseFloat "n" parseInt}`. The JVM
  client is unaffected (it gets a real `BigDecimal`), as is the JSON wire.

## 0.1.2

### Added
- **`deploy` and `destroy`** — `synthigy.client/deploy` takes a modeler
  export's contents and `destroy` takes a dataset xid, with `op-deploy` /
  `op-destroy` builders in `synthigy.client.core` for batch use. Both are in
  the `.clj` and `.cljs` clients. A model export goes over the wire verbatim
  as a string and the server decodes it, so no client needs a transit codec —
  which matters least here, Clojure being the one SDK that HAS one, and most
  as a contract: the SDK hands over bytes it never opens. `destroy` is
  `delete` on the `dataset` meta-entity, and the deploy ack carries the
  dataset xid it takes, so a caller holding nothing but the export can still
  tear down what it deployed. Both are scope-gated server-side
  (`dataset:deploy` / `dataset:delete`, which only the Dataset Developer role
  carries), so the SDK adds no permission surface of its own.

## 0.1.1

### Changed
- **The platform audience is now the default; nobody configures it.** A
  `client_credentials` mint naming no audience resolves to the identity-only
  OIDC audience, which `/data`, `/schema`, `/history`, `/logs` and
  subscriptions all reject — so every user had to set `SYNTHIGY_AUDIENCE` to a
  constant they could not look up, since the server does not advertise it in
  discovery. The failure was a bare 401 that said nothing about audiences.
  This SDK is the client for the platform API, so that is what it now mints
  for. Minting for a different API stays a per-call argument. The environment
  variable remains as an escape hatch.

### Added
- **`watch-sql-template`** — the ad-hoc twin of the `watch-<name>` a codegen'd
  `@watch` sql-template already emits. JavaScript and Python have had
  `watchSqlTemplate` / `watch_sql_template` since 0.1.0; in Clojure the only
  route was the `^:no-doc` `watch-xsql` with a hand-built op map, so anyone not
  using codegen had no documented path. `:entities` is required and the call
  says so plainly: a template has no root entity to infer the watch interest
  from. Present on both the JVM and ClojureScript clients.

### Fixed
- **Codegen could not run under Babashka.** `synthigy.gen` pulled in
  `org.clojure/data.json` for a single call — writing the schema snapshot
  indented — and Babashka cannot load that library, so `bb gen` failed on
  require. `babashka/json` was not a substitute: it silently ignores both
  `:pretty` and `:indent`. The generator now uses Cheshire, which Babashka
  bundles, with its pretty printer tuned so output stays byte-identical to
  `JSON.stringify(x, null, 2)` — the same artifact the JS and Go generators
  write. Cheshire replaces data.json in the dependency list rather than joining
  it.

## 0.1.0

First public release.

### Added
- Thin `/data` client for JVM Clojure, Babashka and browser ClojureScript —
  one `synthigy.client` namespace, same verbs on every target.
- Full CRUD verb set, XSQL queries, SQL templates, schema introspection, the
  temporal `/history` API, record subscriptions and the watch family.
- OAuth client-credentials and supervised token sources.
- `synthigy.gen` — typed codegen from an `.xsql` operations document plus the
  IAM-filtered schema, with a drift gate (`-T:gen-check`).
- Transit on the wire, so timestamps arrive as real date objects on both hosts.
