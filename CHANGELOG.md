# Changelog

All notable changes to `com.synthigy/sdk` (Clojars). Follows
[semver](https://semver.org). Pre-1.0: breaking changes can land on minor bumps.

## 0.1.0 — unreleased

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
