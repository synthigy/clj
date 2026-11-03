(ns synthigy.client.error
  "Typed errors: every coded throw carries :code, :category and :retryable,
   from the same code table the Go, JS, Python and PHP SDKs use."
  (:refer-clojure :exclude [ex-info]))


(def categories
  {;; auth
   "UNAUTHORIZED" "auth"
   "CLIENT_NOT_FOUND" "auth"
   "CLIENT_INACTIVE" "auth"
   "PUBLIC_CLIENT_FORBIDDEN" "auth"
   "NOT_TRUSTED" "auth"
   "USER_NOT_FOUND" "auth"
   "USER_INACTIVE" "auth"
   "PROVISION_FORBIDDEN" "auth"
   "CLAIM_INVALID" "auth"
   "NO_LOGIN_STORE" "auth"
   ;; OAuth error codes from the IdP redirect (RFC 6749 4.1.2.1, OIDC 3.1.2.6)
   "access_denied" "auth"
   "login_required" "auth"
   "consent_required" "auth"
   "interaction_required" "auth"
   "LOGIN_NONCE_MISMATCH" "auth"
   "LOGIN_EXCHANGE_FAILED" "auth"
   "NO_TOKEN" "auth"
   "TOKEN_ERROR" "auth"
   "SILENT_TIMEOUT" "auth"
   "USERINFO_SUB_MISMATCH" "auth"
   "INVALID_CLIENT" "auth"
   "INVALID_SCOPE" "auth"
   "INVALID_AUDIENCE" "auth"
   ;; iam
   "FORBIDDEN" "iam"
   "FORBIDDEN_OP" "iam"
   "ENTITY_FORBIDDEN" "iam"
   "ENTITY_NOT_READABLE" "iam"
   "RELATION_NOT_READABLE" "iam"
   "RELATION_FORBIDDEN" "iam"
   "ATTRIBUTE_FORBIDDEN" "iam"
   "ROW_FORBIDDEN" "iam"
   "CREATE_FORBIDDEN" "iam"
   "DELETE_FORBIDDEN" "iam"
   "SLOT_OCCUPIED" "iam"
   "SYSTEM_DATASET_ENGINE_OWNED" "iam"
   "SYSTEM_ENTITY_ALTERED" "iam"
   "ENGINE_OWNED" "iam"
   ;; validation
   "NO_ENDPOINT" "validation"
   "IMMUTABLE_VERSION_ALTERED" "validation"
   "ENTITY_NAME_CONFLICT" "validation"
   "FIELD_KEY_CONFLICT" "validation"
   "SYSTEM_ENTITY_RLS" "validation"
   "RLS_GUARD_BROKEN" "validation"
   "VERSION_NAME_TAKEN" "validation"
   "INVALID_BODY" "validation"
   "NO_OPERATIONS" "validation"
   "UNKNOWN_OP" "validation"
   "UNKNOWN_OPERATOR" "validation"
   "INVALID_JOIN" "validation"
   "MISSING_ON" "validation"
   "MISSING_ROOT" "validation"
   "UNKNOWN_TREE_RELATION" "validation"
   "MISSING_RECORDS" "validation"
   "EMPTY_RECORDS" "validation"
   "MISSING_ENTITIES" "validation"
   "EMPTY_ENTITIES" "validation"
   "MISSING_RELATIONS" "validation"
   "EMPTY_RELATIONS" "validation"
   "INVALID_RELATION_NAME" "validation"
   "INVALID_SUBSCRIPTION" "validation"
   "INVALID_OPERATIONS" "validation"
   "INVALID_INTEREST" "validation"
   "EMPTY_INTEREST" "validation"
   "UNSUPPORTED_TYPE" "validation"
   "XSQL_PARSE_ERROR" "validation"
   "TEMPLATE_BAD_CTE" "validation"
   "TEMPLATE_UNBALANCED_PARENS" "validation"
   "TEMPLATE_ERROR" "validation"
   "TEMPLATE_PARAM_ERROR" "validation"
   "QUERY_NOT_SELECT" "validation"
   "PARAM_MISSING" "validation"
   "PARAM_TYPE_MISMATCH" "validation"
   "NOT_CONNECTED" "validation"
   "XID_REQUIRED" "validation"
   "NAMESPACE_REQUIRED" "validation"
   "DUPLICATE_OPERATION" "validation"
   "BATCH_MEMBER_UNRESOLVED" "validation"
   "CLAIM_METHOD_NOT_ALLOWED" "validation"
   "PASSWORD_TOO_WEAK" "validation"
   "RETURN_URL_NOT_REGISTERED" "validation"
   "LOGIN_STATE_UNKNOWN" "validation"
   "LOGIN_REQUIRES_CONFIDENTIAL_CLIENT" "validation"
   "PARAM_UNKNOWN" "validation"
   "CONFIG_ERROR" "validation"
   "NAME_REQUIRED" "validation"
   "INVALID_NAME" "validation"
   "INVALID_ACCESS" "validation"
   "FILE_TOO_LARGE" "validation"
   "UNSUPPORTED_MEDIA_TYPE" "validation"
   "RANGE_NOT_SATISFIABLE" "validation"
   "INVALID_PATH" "validation"
   "INVALID_SETTINGS" "validation"
   "INVALID_QUERY" "validation"
   "METHOD_NOT_ALLOWED" "validation"
   "INVALID_SLICE" "validation"
   ;; not_found
   "UNKNOWN_ENTITY" "not_found"
   "UNKNOWN_RELATION" "not_found"
   "UNKNOWN_TEMPLATE_RELATION" "not_found"
   "HISTORY_UNAVAILABLE" "not_found"
   "NOT_FOUND" "not_found"
   "FILE_NOT_FOUND" "not_found"
   "FOLDER_NOT_FOUND" "not_found"
   "VERSION_EXPIRED" "not_found"
   "FILES_UNAVAILABLE" "not_found" ; the files module is not running — retrying can't fix it
   "AUDIT_UNAVAILABLE" "not_found" ; file writes need audit history and none is running
   ;; conflict
   "FK_VIOLATION" "conflict"
   "MODEL_BUSY" "conflict"
   "UNIQUE_VIOLATION" "conflict"
   "CHECK_VIOLATION" "conflict"
   "NOT_NULL_VIOLATION" "conflict"
   "FILE_NAME_TAKEN" "conflict"
   "FILE_CHANGED" "conflict"
   "FILE_NOT_READY" "conflict"
   "FOLDER_NAME_TAKEN" "conflict"
   "FOLDER_NOT_EMPTY" "conflict"
   "FOLDER_CYCLE" "conflict"
   ;; rate_limit
   "TIMEOUT" "rate_limit"
   ;; network
   "NETWORK_ERROR" "network"
   "TRANSPORT_ERROR" "network"
   ;; internal
   "INTERNAL_ERROR" "internal"
   "OPERATION_ERROR" "internal"
   "HTTP_ERROR" "internal"
   "OPERATION_FAILED" "internal"
   "USERINFO_FAILED" "internal"})

(def retryable-categories #{"network" "rate_limit" "internal"})

(defn category
  [code]
  (get categories code "internal"))

(defn retryable?
  [{:keys [code retryable]}]
  (if (boolean? retryable)
    retryable
    (and (contains? categories code)
         (contains? retryable-categories (category code)))))

(defn ex-info
  "clojure.core/ex-info, plus :category and :retryable from (:code data); the server's own :retryable wins."
  ([message data] (ex-info message data nil))
  ([message data cause]
   (clojure.core/ex-info message
                         (assoc data :category (category (:code data)) :retryable (retryable? data))
                         cause)))

(def refusal-codes
  {"invalid_client" "INVALID_CLIENT"
   "invalid_scope" "INVALID_SCOPE"
   "invalid_target" "INVALID_AUDIENCE"})

(defn token-refusal
  "The ex-info for a refused /oauth/token mint, typed by its RFC 6749 / RFC 8707 error."
  [status {:keys [error error_description] :as oauth} body]
  (if-not (and (string? error) (seq error))
    (ex-info (str "Token request failed: HTTP " status)
             {:code "TOKEN_ERROR" :status status :body body})
    (let [code (get refusal-codes error "TOKEN_ERROR")]
      (ex-info (str "Token request refused (" status "): " error
                    (when (and (string? error_description) (seq error_description))
                      (str " — " error_description)))
               (cond-> {:code code :status status :body body :details oauth}
                 (= code "INVALID_CLIENT")
                 (assoc :hint "check the client id and secret, and that the client is active"))))))
