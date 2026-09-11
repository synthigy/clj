(ns synthigy.client.retry
  "Opt-in retry helper for transient failures.

   Scope: wrap a thunk that calls the SDK, retry on transport errors and
   5xx responses, leave 4xx alone. NOT composable with write ops at the
   caller's discretion — retrying a `sync` after a timeout could double-
   apply if the server processed the first attempt. Use on idempotent
   calls (search / get / count / schema / sql-template).

   Retryable:
     :code TRANSPORT_ERROR  — network blip, DNS, timeout
     :code HTTP_ERROR + :status 502/503/504 — gateway / upstream hiccup

   NOT retryable (propagates on first failure):
     :code UNAUTHORIZED / FORBIDDEN — auth problem; retry won't help
     :code HTTP_ERROR with 4xx      — caller bug; retry won't help
     :code UNKNOWN_ENTITY / operation errors — app-level failures"
  (:require
   [clojure.string :as str]))


(def ^:private retryable-statuses #{502 503 504})


(defn- retryable?
  [^Throwable t]
  (when (instance? clojure.lang.ExceptionInfo t)
    (let [{:keys [code status]} (ex-data t)]
      (or (= code "TRANSPORT_ERROR")
          (and (= code "HTTP_ERROR")
               (contains? retryable-statuses status))))))


(defn with-retry
  "Invoke `thunk` (0-arg). On transient failures, sleep + retry up to
   `:max-attempts` times (default 3). Backoff is exponential starting
   at `:backoff-ms` (default 500) — 500, 1000, 2000, ... capped at
   `:max-backoff-ms` (default 10000).

   `:on-retry` (optional) — (fn [attempt ^Throwable delay-ms]) called
   between attempts; good for logging.

   Returns the thunk's value on success. Throws the last exception if
   all attempts fail, OR the original exception if it's not retryable."
  ([thunk] (with-retry thunk nil))
  ([thunk {:keys [max-attempts backoff-ms max-backoff-ms on-retry]
           :or {max-attempts 3
                backoff-ms 500
                max-backoff-ms 10000}}]
   (loop [attempt 1
          delay backoff-ms]
     (let [result (try
                    {:ok (thunk)}
                    (catch Throwable e
                      {:err e}))]
       (cond
         (contains? result :ok)
         (:ok result)

         (or (>= attempt max-attempts)
             (not (retryable? (:err result))))
         (throw (:err result))

         :else
         (do
           (when on-retry (on-retry attempt (:err result) delay))
           (Thread/sleep delay)
           (recur (inc attempt) (min (* delay 2) max-backoff-ms))))))))
