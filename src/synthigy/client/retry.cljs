(ns synthigy.client.retry
  "Opt-in retry helper for transient failures in the browser SDK.

   Scope matches the CLJ sibling: wrap a promise-returning thunk, retry
   transport errors and 5xx responses, leave 4xx alone. Use only on
   idempotent calls.

   Retryable:
     :code TRANSPORT_ERROR  — network blip, DNS, timeout
     :code HTTP_ERROR + :status 502/503/504 — gateway / upstream hiccup

   NOT retryable (propagates on first failure):
     :code UNAUTHORIZED / FORBIDDEN — auth problem
     :code HTTP_ERROR with 4xx      — caller bug
     app-level failures"
  (:require
   [promesa.core :as p]))


(def ^:private retryable-statuses #{502 503 504})


(defn- retryable?
  [e]
  (when (instance? ExceptionInfo e)
    (let [{:keys [code status]} (ex-data e)]
      (or (= code "TRANSPORT_ERROR")
          (and (= code "HTTP_ERROR")
               (contains? retryable-statuses status))))))


(defn with-retry
  "Invoke `thunk` (0-arg, promise-returning). Retry transient failures
   up to `:max-attempts` (default 3) with exponential backoff starting
   at `:backoff-ms` (default 500), capped at `:max-backoff-ms` (default
   10000).

   `:on-retry` — optional (fn [attempt error delay-ms]) for logging.

   Returns a promise of the thunk's value. Rejects with the last error
   if all attempts fail or with the original error if not retryable."
  ([thunk] (with-retry thunk nil))
  ([thunk {:keys [max-attempts backoff-ms max-backoff-ms on-retry]
           :or {max-attempts 3
                backoff-ms 500
                max-backoff-ms 10000}}]
   (letfn [(attempt [n delay]
             (p/catch (thunk)
                      (fn [e]
                        (if (or (>= n max-attempts)
                                (not (retryable? e)))
                          (throw e)
                          (do (when on-retry (on-retry n e delay))
                              (p/then (p/delay delay)
                                      #(attempt (inc n)
                                                (min (* delay 2) max-backoff-ms))))))))]
     (attempt 1 backoff-ms))))
