# Synthigy Chat — dataset export

`synthigy-chat@0.1.2.json` — transit-serialized dataset version (same format as
the rls-demo datasets): `{:euuid :name :xid :dataset :model}`.

Version 0.1.2 is the first with the **correct RLS write guard** — the
`author = current user` `:ref` condition points at the live Author attribute
(0.1.1 shipped with a dangling reference that fail-closed all edits).

Deploy to a fresh server from a REPL:

```clojure
(require '[synthigy.dataset :as dataset]
         '[synthigy.transit :refer [<-transit]]
         '[clojure.java.io :as io])

(-> "datasets/synthigy-chat@0.1.2.json" io/file slurp <-transit dataset/deploy!)
```

Note: the demo's `main.clj` hardcodes seeded user/group xids — deploying the
model alone gives you the schema + rules, not the cast. Re-seed or update the
constants for a fresh database.
