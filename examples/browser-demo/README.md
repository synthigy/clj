# Browser-demo — real-browser verification harness

**Not a shipped example.** This proves the CLJS SDK's `watch`/`watch-query`
multiplexer (`client.cljs`) actually works in a real browser engine — real
`fetch`, real fetch-streaming SSE (`ReadableStream`/`TextDecoder`/
`AbortController`), real CORS enforcement. The `:test` build's
`shadow-cljs.edn` target (`node out/test.js`) only proves the code runs
under Node, which has none of those constraints.

## What it does

1. Mints a token via `client_credentials` against a live Synthigy server.
2. Fetches one real `music_track` row.
3. Compiles + serves the `:browser-demo` build (`demo.core/init` —
   `src/demo/core.cljs`), which: `connect!`s, `search`es the row, opens a
   `watch-query` scoped to just that row's xid, `sync`s a title change, and
   asserts the watch fired and the DOM reflects the live update — then
   restores the original title (best-effort cleanup; verified once via a
   follow-up server query, not asserted by the harness itself).
4. Drives it with a headless real Chrome (via `playwright-core` +
   `executablePath`, pointed at the system-installed browser — **not**
   Playwright's own downloaded browser binaries, to avoid the
   `playwright install-deps` sudo/apt step).
5. Reads the `#test-result` DOM element for PASS/FAIL.

## Run it

```bash
npx shadow-cljs compile browser-demo     # once, or after editing client.cljs
npm install --no-save playwright-core    # one-time; NOT a project dependency

SYNTHIGY_TEST_ENDPOINT=http://localhost:7887 \
SYNTHIGY_TEST_CLIENT_ID=<a client_credentials-capable client> \
SYNTHIGY_TEST_CLIENT_SECRET=<its secret> \
  node run.mjs
```

Any client with the `client_credentials` grant works — this harness only
needs a valid bearer token to exercise the wire. That is **not** a
statement about how a real browser app should authenticate: a real browser
app is a public OAuth client and cannot use `client_credentials` at all
(the server rejects it) — see the SDK README's "Auth in the browser"
section for the real story (bring-your-own `:token-fn`, `oidc-client-ts`
+ silent renew, etc.).

## Why `music_track`

Whatever entity you point it at needs at least one row and a text field
safe to mutate-and-restore. `music_track`/`title` is what the demo dataset
already has seeded (see the `datastar-music` example) — no reason to seed
anything new just for this check.
