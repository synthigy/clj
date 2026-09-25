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
   ;; iam
   "FORBIDDEN" "iam"
   "FORBIDDEN_OP" "iam"
   "ENTITY_FORBIDDEN" "iam"
   "ENTITY_NOT_READABLE" "iam"
   "RELATION_NOT_READABLE" "iam"
   ;; validation
   "NO_ENDPOINT" "validation"
   "INVALID_BODY" "validation"
   "NO_OPERATIONS" "validation"
   "UNKNOWN_OP" "validation"
   "UNKNOWN_OPERATOR" "validation"
   "MISSING_ON" "validation"
   "MISSING_ROOT" "validation"
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
   ;; not_found
   "UNKNOWN_ENTITY" "not_found"
   "UNKNOWN_RELATION" "not_found"
   "UNKNOWN_TEMPLATE_RELATION" "not_found"
   "HISTORY_UNAVAILABLE" "not_found"
   ;; conflict
   "FK_VIOLATION" "conflict"
   "UNIQUE_VIOLATION" "conflict"
   "CHECK_VIOLATION" "conflict"
   "NOT_NULL_VIOLATION" "conflict"
   ;; rate_limit
   "TIMEOUT" "rate_limit"
   ;; network
   "NETWORK_ERROR" "network"
   "TRANSPORT_ERROR" "network"
   ;; internal
   "INTERNAL_ERROR" "internal"
   "OPERATION_ERROR" "internal"
   "HTTP_ERROR" "internal"})

(def retryable-categories #{"network" "rate_limit" "internal"})

(defn category
  [code]
  (get categories code "internal"))

(defn ex-info
  "clojure.core/ex-info, plus :category and :retryable derived from (:code data)."
  ([message data] (ex-info message data nil))
  ([message data cause]
   (let [cat (category (:code data))]
     (clojure.core/ex-info message
                           (assoc data :category cat :retryable (contains? retryable-categories cat))
                           cause))))
