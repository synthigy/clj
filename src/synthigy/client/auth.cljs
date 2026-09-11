(ns synthigy.client.auth
  "Token providers for the Synthigy CLJS client.

   A provider is a map `{:token-fn f :invalidate-fn g}`:

     :token-fn     — 0-arg returning a bearer token (string or Promise<string>).
                     Optional 1-arg variant `(token-fn audience)` for IdP
                     federation.
     :invalidate-fn — 0/1-arg clearing the provider's cache so the next
                      `token-fn` call refetches.

   The SDK calls `token-fn` before every request; on HTTP 401 it calls
   `invalidate-fn` (if present) and retries once. The two defaults shipped
   here cover 90% of SDK use — teams wanting OIDC / PKCE / silent renew
   should plug their own `:token-fn` / `:invalidate-fn` in."
  (:require
   [promesa.core :as p]))


(defn static
  "Provider wrapping a single pre-fetched token."
  [token]
  {:token-fn (fn
               ([] token)
               ([_audience]
                (throw (ex-info "Static token cannot fetch per-audience tokens"
                                {:code "CONFIG_ERROR"}))))
   :invalidate-fn (fn ([]) ([_audience]))})


(defn- fetch-token
  [{:keys [token-url client-id client-secret]} audience]
  (let [form (js/URLSearchParams.)]
    (.append form "grant_type" "client_credentials")
    (.append form "client_id" client-id)
    (.append form "client_secret" client-secret)
    (when audience (.append form "audience" audience))
    (p/let [resp (js/fetch token-url
                   (clj->js
                    {:method "POST"
                     :headers {"Accept" "application/json"
                               "Content-Type" "application/x-www-form-urlencoded"}
                     :body (.toString form)}))
            status (.-status resp)]
      (if (<= 200 status 299)
        (p/let [body-text (.text resp)
                body (js->clj (js/JSON.parse body-text) :keywordize-keys true)
                access-token (:access_token body)
                expires-in (or (:expires_in body) 3600)]
          {:access-token access-token
           :expires-at (+ (js/Date.now) (* expires-in 1000))})
        (p/let [body-text (.text resp)]
          (throw (ex-info (str "Token request failed: HTTP " status)
                          {:code "TOKEN_ERROR" :status status :body body-text})))))))


(defn- expired?
  [t buffer-ms]
  (or (nil? t)
      (nil? (:expires-at t))
      (< (:expires-at t) (+ (js/Date.now) buffer-ms))))


(defn oauth
  "OAuth client-credentials provider.

   Options:
     :token-url     — required; full URL of the token endpoint
     :client-id     — required
     :client-secret — required
     :audience      — default audience for every mint; the platform's
                      audience model is opt-in, and a mint naming none
                      resolves to the identity-only OIDC audience that
                      /data rejects.
     :token-buffer  — seconds before expiry to refresh (default 30)

   Tokens are cached per audience (`nil` = default). `:token-fn` returns
   a Promise<string>."
  [{:keys [token-url client-id client-secret token-buffer audience]
    :or {token-buffer 30}
    :as config}]
  (assert (and token-url client-id client-secret)
          "oauth requires :token-url, :client-id, :client-secret")
  ;; cache entry per audience is either {:token <map>} (a resolved token) or
  ;; {:promise <Promise>} (a fetch in flight). Caching the in-flight promise
  ;; makes refresh single-flight: overlapping callers share one request
  ;; instead of each firing a duplicate token fetch. `start-fetch!` records
  ;; the promise synchronously before returning and JS runs each resolve call
  ;; to completion without interleaving, so even two token-fn calls in the
  ;; same tick converge on one fetch.
  (let [cache (atom {})
        buffer-ms (* token-buffer 1000)
        start-fetch!
        (fn [aud]
          (let [pr (p/then (fetch-token config aud)
                           (fn [t]
                             (swap! cache assoc aud {:token t})
                             t))]
            (swap! cache assoc aud {:promise pr})
            ;; On failure drop the entry so the next call retries instead of
            ;; replaying a rejected promise. Consumers await `pr` directly and
            ;; see the rejection; this branch only cleans up (no rethrow, so
            ;; it doesn't surface as an unhandled rejection).
            (p/catch pr (fn [_e]
                          (swap! cache (fn [m]
                                         (if (identical? pr (:promise (get m aud)))
                                           (dissoc m aud)
                                           m)))))
            pr))
        resolve (fn [aud]
                  (let [{:keys [token promise]} (get @cache aud)]
                    (cond
                      (and token (not (expired? token buffer-ms)))
                      (p/resolved (:access-token token))

                      promise
                      (p/then promise (fn [t] (:access-token t)))

                      :else
                      (p/then (start-fetch! aud) (fn [t] (:access-token t))))))]
    {:token-fn (fn
                 ([] (resolve audience))
                 ([a] (resolve (or a audience))))
     :invalidate-fn (fn
                      ([] (reset! cache {}))
                      ([a] (swap! cache dissoc a)))}))
