# Changelog

All notable changes to `com.synthigy/sdk` (Clojars). Follows
[semver](https://semver.org). Pre-1.0: breaking changes can land on minor bumps.

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
