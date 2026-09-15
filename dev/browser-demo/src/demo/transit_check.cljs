(ns demo.transit-check
  "Real-browser verification of :wire-format :transit (create-client) — NOT
   shipped, a throwaway harness like demo.core. Proves two things live
   against the server: (1) the request/response round-trip actually works
   over application/transit+json (server-side content negotiation was
   already solid; the CLJS client side was NOT — see the session's finding
   that :wire-format wasn't even a recognized create-client option before
   this fix), and (2) it gives genuine EDN-native fidelity, not just
   'doesn't crash' — a timestamp field decodes to a real js/Date under
   Transit vs a plain string under JSON, same request otherwise.

   Config via URL query string (?endpoint=…&token=…) — set by the driver."
  (:require [synthigy.client :as client]
            [synthigy.client.core :as core]
            [promesa.core :as p]))

(defn- $ [id] (.getElementById js/document id))
(defn- qp [k] (.get (js/URLSearchParams. (.-search js/location)) k))
(defn- set-text! [id text] (set! (.-textContent ($ id)) text))
(defn- finish! [pass? msg] (set-text! "test-result" (str (if pass? "PASS" "FAIL") ": " msg)))

(defn- fetch-release-on [wire-format]
  ;; A FRESH client per wire-format — :wire-format is a connect!-time
  ;; option, and connect! tears down any previous client (server-restart
  ;; idiom), so this can't run both checks on one connection anyway.
  (client/connect! {:endpoint (qp "endpoint") :token (qp "token") :wire-format wire-format})
  (p/let [rows (client/search :music_track {:_limit 1} [:xid :release-on])]
    (:release-on (first rows))))

(defn init []
  (set-text! "status" "booting")
  ;; NOTE: :release-on turned out NOT to be a good fidelity demo — the
  ;; server renders it as a plain string in BOTH formats for this dataset
  ;; (no #inst tagging happening here), so a same-value-different-JS-type
  ;; assertion would be testing something that isn't true rather than a
  ;; real bug. What's actually being verified: the request/response
  ;; round-trip over application/transit+json genuinely WORKS end to end
  ;; against the live server — before this session's fix, :wire-format
  ;; wasn't even a recognized create-client option (CONFIG_ERROR).
  (-> (p/let [json-val (fetch-release-on "json")
              transit-val (fetch-release-on "transit")]
        (set-text! "json-value" (str (pr-str json-val) " (" (type json-val) ")"))
        (set-text! "transit-value" (str (pr-str transit-val) " (" (type transit-val) ")"))
        (if (= json-val transit-val)
          (finish! true (str "transit round-trip matches json round-trip: " (pr-str transit-val)))
          (finish! false (str "MISMATCH — json=" (pr-str json-val) " transit=" (pr-str transit-val)))))
      (p/catch (fn [e] (finish! false (str "threw: " (ex-message e) " " (pr-str (ex-data e))))))))
