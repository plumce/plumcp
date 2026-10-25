;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.schema.era26-schema-defs
  "MCP JSON-RPC Schema - exclusive bits (delta) for MCP 2026-07-28 spec.
   Era 2026-07-28:
   https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/schema/2026-07-28/schema.ts"
  (:require
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.schema.schema-util :as su]))


(def protocol-version-2026-07-28 "2026-07-28")
(def protocol-versions-supported (-> [protocol-version-2026-07-28]
                                     sort
                                     reverse
                                     vec))
(def protocol-versions-supported-set (set protocol-versions-supported))
(def protocol-version-max (first protocol-versions-supported))


;; MCP Methods
(def method-server-discover "server/discover")
(def method-subscriptions-listen "subscriptions/listen")
(def method-notifications-subscriptions-acknowledged
  "notifications/subscriptions/acknowledged")


(def MetaObject
  "Represents the contents of a `_meta` field, which clients and servers
   use to attach additional metadata to their interactions.
   Certain key names are reserved by MCP for protocol-level metadata;
   implementations MUST NOT make assumptions about values at these keys.
   Additionally, specific schema definitions may reserve particular names
   for purpose-specific metadata, as declared in those definitions.
   Valid keys have two segments:
   **Prefix:**
   - Optional — if specified, MUST be a series of _labels_ separated by
     dots (`.`), followed by a slash (`/`).
   - Labels MUST start with a letter and end with a letter or digit.
     Interior characters may be letters, digits, or hyphens (`-`).
   - Implementations SHOULD use reverse DNS notation (e.g.,
     `com.example/` rather than `example.com/`).
   - Any prefix where the second label is `modelcontextprotocol` or
     `mcp` is **reserved** for MCP use. For example:
     `io.modelcontextprotocol/`,
     `dev.mcp/`,
     `org.modelcontextprotocol.api/`, and
     `com.mcp.tools/` are all reserved.
     However, `com.example.mcp/` is NOT reserved, as the second label is
     `example`.

   **Name:**
   - Unless empty, MUST start and end with an alphanumeric character
     (`[a-z0-9A-Z]`).
   - Interior characters may be alphanumeric, hyphens (`-`), underscores
     (`_`), or dots (`.`).

   @see [General fields: `_meta`](/specification/2026-07-28/basic/index#meta)
   for more details."
  sd/attr-map)


(def ClientCapabilities
  "Capabilities a client may support. Known capabilities are defined
   here, in this schema, but this is not a closed set: any client can
   define its own, additional capabilities.

   @category `server/discover`"
  (su/ts-object
   ;; Experimental, non-standard capabilities that the client supports.
   :experimental? sd/attr-map
   ;; Present if the client supports listing roots.
   ;;
   ;; @deprecated Deprecated as of protocol version 2026-07-28 (SEP-2577).
   ;; Remains in the specification for at least twelve months; see the
   ;; deprecated features registry.
   ;;
   ;; @example Roots — minimum baseline support
   ;;
   :roots? sd/attr-map ; {}
   ;; Present if the client supports sampling from an LLM.
   ;;
   ;; @deprecated Deprecated as of protocol version 2026-07-28 (SEP-2577).
   ;; Remains in the specification for at least twelve months; see the
   ;; deprecated features registry.
   ;;
   ;; @example Sampling — minimum baseline support
   ;;
   ;; @example Sampling — tool use support
   ;;
   ;; @example Sampling — context inclusion support (deprecated)
   :sampling {;; Whether the client supports context inclusion via
              ;; `includeContext` parameter. If not declared, servers
              ;; SHOULD only use `includeContext: "none"` (or omit it).
              :context? sd/attr-map
              ;; Whether the client supports tool use via `tools` and
              ;; `toolChoice` parameters.
              :tools? sd/attr-map}
   ;; Present if the client supports elicitation from the server.
   ;;
   ;; @example Elicitation — form and URL mode support
   ;;
   ;; @example Elicitation — form mode only (implicit)
   :elicitation? {:form? sd/attr-map
                  :url? sd/attr-map}
   ;; Optional MCP extensions that the client supports. Keys are extension
   ;; identifiers (e.g., "io.modelcontextprotocol/oauth-client-credentials"),
   ;; and values are per-extension settings objects. An empty object indicates
   ;; support with no settings.
   ;;
   ;; Keys MUST follow the {@link MetaObject | `_meta` key naming rules}, with a
   ;; mandatory prefix.
   ;;
   ;; @example Extensions — MCP Apps (UI) extension with MIME type support
   :extensions? sd/attr-map))


(def ServerCapabilities
  "Capabilities that a server may support. Known capabilities are defined here,
   in this schema, but this is not a closed set: any server can define its own,
   additional capabilities.

   @category `server/discover`"
  (su/ts-object
   ;; Experimental, non-standard capabilities that the server supports.
   :experimental? sd/attr-map
   ;; Present if the server supports sending log messages to the client.
   ;;
   ;; @deprecated Deprecated as of protocol version 2026-07-28 (SEP-2577).
   ;; Remains in the specification for at least twelve months; see the
   ;; deprecated features registry.
   ;;
   ;; @example Logging — minimum baseline support
   :logging? sd/attr-map
   ;; Present if the server supports argument autocompletion suggestions.
   ;;
   ;; @example Completions — minimum baseline support
   :completions? sd/attr-map
   ;; Present if the server offers any prompt templates.
   ;;
   ;; @example Prompts — minimum baseline support
   ;;
   ;; @example Prompts — list changed notifications
   :prompts? {;; Whether this server supports notifications for changes
              ;; to the prompt list.
              :listChanged? :boolean}
   ;; Present if the server offers any resources to read.
   ;;
   ;; @example Resources — minimum baseline support
   ;;
   ;; @example Resources — subscription to individual resource updates (only)
   ;;
   ;; @example Resources — list changed notifications (only)
   ;;
   ;; @example Resources — all notifications
   :resources? {;; Whether this server supports subscribing to resource updates.
                :subscribe? :boolean
                ;; Whether this server supports notifications for changes
                ;; to the resource list.
                :listChanged? :boolean}
   ;; Present if the server offers any tools to call.
   ;;
   ;; @example Tools — minimum baseline support
   ;;
   ;; @example Tools — list changed notifications
   :tools? {;; Whether this server supports notifications for changes to
            ;; the tool list.
            :listChanged? :boolean}
   ;; Optional MCP extensions that the server supports. Keys are extension
   ;; identifiers (e.g., "io.modelcontextprotocol/tasks"), and values are
   ;; per-extension settings objects. An empty object indicates support with
   ;; no settings.
   ;;
   ;; Keys MUST follow the {@link MetaObject | `_meta` key naming rules},
   ;; with a mandatory prefix.
   ;;
   ;; @example Extensions — Tasks extension support
   :extensions? sd/attr-map))


(def key-protocol-version :io.modelcontextprotocol/protocolVersion)
(def key-client-info :io.modelcontextprotocol/clientInfo)
(def key-client-capabilities :io.modelcontextprotocol/clientCapabilities)
(def key-log-level :io.modelcontextprotocol/logLevel)


(def RequestMetaObject
  "Extends {@link MetaObject} with additional request-specific fields.
   All key naming rules from `MetaObject` apply.
   
   @see {@link MetaObject} for key naming rules and reserved prefixes.
   @see [General fields: `_meta`](/specification/2026-07-28/basic/index#meta)
   for more details."
  (su/ts-object
   ;; If specified, the caller is requesting out-of-band progress
   ;; notifications for this request (as represented by notifications/progress).
   ;; The value of this parameter is an opaque token that will be attached to
   ;; any subsequent notifications. The receiver is not obligated to provide
   ;; these notifications
   :progressToken? sd/ProgressToken
   ;; The MCP Protocol Version being used for this request. Required.
   ;;
   ;; For the HTTP transport, this value MUST match the `MCP-Protocol-Version`
   ;; header; otherwise the server MUST return a `400 Bad Request`. If the
   ;; server does not support the requested version, it MUST return an
   ;; {@link UnsupportedProtocolVersionError} .
   key-protocol-version :string
   ;; Identifies the client software making the request. Clients SHOULD
   ;; include this field on every request unless specifically configured not
   ;; to do so.
   ;;
   ;; The {@link Implementation} schema requires `name `and `version `; other
   ;; fields are optional.
   ;;
   ;; The value is self-reported by the client and is not verified by the
   ;; protocol. It is intended for display, logging, and debugging. Servers
   ;; SHOULD NOT use it to change their behavior, and SHOULD NOT rely on it for
   ;; security decisions.
   (su/optkey key-client-info) sd/Implementation
   ;; The client's capabilities for this specific request. Required.
   ;;
   ;; Capabilities are declared per-request rather than once at initialization;
   ;; an empty object means the client supports no optional capabilities.
   ;; Servers MUST NOT infer capabilities from prior requests.
   key-client-capabilities ClientCapabilities
   ;; The desired log level for this request. Optional.
   ;;
   ;; If absent, the server MUST NOT send any
   ;; {@link LoggingMessageNotification | notifications/message}
   ;; notifications for this request. The client opts in to log messages by
   ;; explicitly setting a level. Replaces the former `logging/setLevel` RPC.
   ;;
   ;; @deprecated Deprecated as of protocol version 2026-07-28 (SEP-2577).
   ;; Remains in the specification for at least twelve months; see the
   ;; deprecated features registry.
   (su/optkey key-log-level) sd/LoggingLevel))


(def key-subscription-id :io.modelcontextprotocol/subscriptionId)


(def NotificationMetaObject
  "Extends {@link MetaObject} with additional notification-specific
   fields. All key naming rules from `MetaObject` apply.
   
   @see {@link MetaObject} for key naming rules and reserved prefixes.
   @see [General fields: `_meta`](/specification/2026-07-28/basic/index#meta)
   for more details.
   @category Common Types"
  (su/ts-object
   ;; Identifies the subscription stream a notification was delivered on. The
   ;; server MUST include this key on every notification delivered via a
   ;; {@link SubscriptionsListenRequest | subscriptions/listen} stream, so the
   ;; client can correlate the notification with the originating subscription.
   ;; The key is absent on notifications not delivered via a subscription
   ;; stream (e.g. progress notifications for an in-flight request), which is
   ;; why it is optional here.
   ;;
   ;; The value is the JSON-RPC ID of the `subscriptions/listen` request that
   ;; opened the stream.
   (su/optkey key-subscription-id) sd/RequestId))


(def key-server-info :io.modelcontextprotocol/serverInfo)


(def ResultMetaObject
  "Extends {@link MetaObject} with additional result-specific fields.
   All key naming rules from `MetaObject` apply.
   
   @see {@link MetaObject} for key naming rules and reserved prefixes.
   @see [General fields: `_meta`](/specification/2026-07-28/basic/index#meta)
   for more details.
   @category Common Types"
  (su/ts-object
   ;; Identifies the server software producing the response. Servers SHOULD
   ;; include this field on every response unless specifically configured not
   ;; to do so.
   ;;
   ;; The {@link Implementation} schema requires `name` and `version`; other
   ;; fields are optional.
   ;;
   ;; The value is self-reported by the server and is not verified by the
   ;; protocol. It is intended for display, logging, and debugging. Clients
   ;; SHOULD NOT use it to change their behavior, and SHOULD NOT rely on it for
   ;; security decisions.
   (su/optkey key-server-info) sd/Implementation))


(def RequestParams
  "Common params for any request."
  (su/ts-object
   :_meta RequestMetaObject))


(def Request
  (su/ts-object
   :method :string
   :params? RequestParams))


(def NotificationParams
  "Common params for any notification."
  (su/ts-object
   :_meta? NotificationMetaObject))


(def Notification
  (su/ts-object
   :method :string
   ;; Allow unofficial extensions of `Notification.params` without impacting
   ;; `NotificationParams`.
   :params? NotificationParams))


(def result-type-complete "complete")
(def result-type-input-required "input_required")


(def ResultType
  "Indicates the type of a {@link Result} object, allowing the client to
   determine how to parse the response.

   complete - the request completed successfully and the result contains
              the final content.
   input_required - the request requires additional input and the result
                    contains an {@link InputRequiredResult} object with
                    instructions for the client to provide additional
                    input before retrying the original request.
   @category Common Types"
  [:or
   [:= result-type-complete]
   [:= result-type-input-required]
   :string])


(def Result
  "Common result fields."
  (su/ts-object
   :_meta? ResultMetaObject
   ;; Indicates the type of the result, which allows the client to determine
   ;; how to parse the result object.
   ;;
   ;; Servers implementing this protocol version MUST include this field.
   ;; For backward compatibility, when a client receives a result from a
   ;; server implementing an earlier protocol version (which does not include
   ;; `resultType`), the client MUST treat the absent field as `"complete"`.
   :resultType ResultType))


(def JSONRPCRequest
  "A request that expects a response.
   
   @category JSON-RPC"
  (su/ts-extends
   [Request]
   :jsonrpc [:= sd/jsonrpc-version]
   :id sd/RequestId))


(def JSONRPCNotification
  "A notification which does not expect a response.
   
   @category JSON-RPC"
  (su/ts-extends
   [Notification]
   :jsonrpc [:= sd/jsonrpc-version]))


(def JSONRPCResultResponse
  "A successful (non-error) response to a request.
   
   @category JSON-RPC"
  (su/ts-object
   :jsonrpc [:= sd/jsonrpc-version]
   :id sd/RequestId
   :result Result))


(def ParseError
  "A JSON-RPC error indicating that invalid JSON was received by the
   server. This error is returned when the server cannot parse the JSON
   text of a message.
   
   @see {@link https://www.jsonrpc.org/specification#error_object | JSON-RPC 2.0 Error Object}

   @example Invalid JSON

   @category Errors"
  (su/ts-extends
   [sd/MCPError]
   :code [:= sd/error-code-parse-error]))


(def InvalidRequestError
  "A JSON-RPC error indicating that the request is not a valid request
   object. This error is returned when the message structure does not
   conform to the JSON-RPC 2.0 specification requirements for a request
   (e.g., missing required fields like `jsonrpc` or `method`, or using
   invalid types for these fields).
   
   @see {@link https://www.jsonrpc.org/specification#error_object | JSON-RPC 2.0 Error Object}

   @category Errors"
  (su/ts-extends
   [sd/MCPError]
   :code [:= sd/error-code-invalid-request]))


(def MethodNotFoundError
  "A JSON-RPC error indicating that the requested method does not exist
   or is not available.
   
   In MCP, a server returns this error when a client invokes a method
   the server does not implement — either a genuinely unknown method, or
   one gated behind a server capability the server did not advertise
   (e.g., calling `prompts/list` when the `prompts` capability was not
   advertised).

   A request that requires a client capability the client did not declare
   is signalled instead by {@link MissingRequiredClientCapabilityError}
   (`-32021`).

   @see {@link https://www.jsonrpc.org/specification#error_object | JSON-RPC 2.0 Error Object}

   @example Prompts not supported

   @category Errors"
  (su/ts-extends
   [sd/MCPError]
   :code [:= sd/error-code-method-not-found]))


(def InvalidParamsError
  "A JSON-RPC error indicating that the method parameters are invalid or
   malformed.
   
   In MCP, this error is returned in various contexts when request
   parameters fail validation:

   - **Tools**: Unknown tool name or invalid tool arguments
   - **Prompts**: Unknown prompt name or missing required arguments
   - **Pagination**: Invalid or expired cursor values
   - **Logging**: Invalid log level
   - **Elicitation**: Server requests an elicitation mode not declared
                      in client capabilities
   - **Sampling**: Missing tool result or tool results mixed with other
                   content

   @see {@link https://www.jsonrpc.org/specification#error_object | JSON-RPC 2.0 Error Object}

   @example Unknown tool

   @example Invalid tool arguments

   @example Unknown prompt

   @example Invalid cursor

   @category Errors"
  (su/ts-extends
   [sd/MCPError]
   :code [:= sd/error-code-invalid-params]))


(def MCPInternalError
  "A JSON-RPC error indicating that an internal error occurred on the
   receiver. This error is returned when the receiver encounters an
   unexpected condition that prevents it from fulfilling the request.
   
   @see {@link https://www.jsonrpc.org/specification#error_object | JSON-RPC 2.0 Error Object}

   @example Unexpected error

   @category Errors"
  (su/ts-extends
   [sd/MCPError]
   :code [:= sd/error-code-internal-error]))


;; --------------------------------------------
;; MCP error codes.
;;
;; JSON-RPC 2.0 reserves `-32000` to `-32099` for implementation-defined
;; server errors. MCP partitions that range:
;;
;; - `-32000` to `-32019`: implementation-defined. Existing SDKs and
;;   implementations use codes here for their own purposes; the specification
;;   will never define codes in this sub-range, and receivers must not assign
;;   cross-implementation semantics to them.
;; - `-32020` to `-32099`: reserved for error codes defined by the MCP
;;   specification. Every code allocated here is recorded in this file.
;;   Codes are allocated sequentially starting at `-32020` and proceeding
;;   toward `-32099`.
;;
;; Codes defined by earlier protocol versions remain reserved and are never
;; reused: `-32002` (resource not found, 2025-11-25 and earlier; replaced by
;; `-32602`) and `-32042` (URL elicitation required, 2025-11-25 only).
;; --------------------------------------------


(def ^:const error-code-header-mismatch
  "Error code (-32020) returned when the HTTP headers of a request do
   not match the corresponding values in the request body, or required
   headers are missing or malformed."
  -32020)


(def ^:const error-code-missing-required-client-capability
  "Error code (-32021) returned when a server requires a client
   capability that was not declared in the request's `clientCapabilities`."
  -32021)


(def ^:const error-code-unsupported-protocol-version
  "Error code (-32022) returned when the request's protocol version is
   not supported by the server."
  -32022)


(def HeaderMismatchErrorOnly
  "Error definition for HeaderMismatchError."
  (su/ts-extends
   [sd/MCPError]
   :code [:= error-code-header-mismatch]))


(def HeaderMismatchError
  "Returned when a server rejects a request because the values in the HTTP
   headers do not match the corresponding values in the request body, or
   because required headers are missing or malformed. For HTTP, the response
   status code MUST be `400 Bad Request`.

   @example Header mismatch"
  (su/ts-extends
   [sd/JSONRPCErrorResponse]
   :error HeaderMismatchErrorOnly))


(def UnsupportedProtocolVersionErrorOnly
  "Error definition for UnsupportedProtocolVersionError."
  (su/ts-extends
   [sd/MCPError]
   :code [:= error-code-unsupported-protocol-version]
   :data (su/ts-object
          ;; Protocol versions the server supports. The client should choose
          ;; a mutually supported version from this list and retry.
          :supported [:vector :string]
          ;; The protocol version that was requested by the client.
          :requested :string)))


(def UnsupportedProtocolVersionError
  "Returned when the request's protocol version is unknown to the server or
   unsupported (e.g., a known experimental or draft version the server has
   chosen not to implement). For HTTP, the response status code MUST be
   `400 Bad Request`.

   @example Unsupported protocol version

   @category Errors"
  (su/ts-extends
   [sd/JSONRPCErrorResponse]
   :error UnsupportedProtocolVersionErrorOnly))


(def MissingRequiredClientCapabilityErrorOnly
  "Error definition for MissingRequiredClientCapabilityError."
  (su/ts-extends
   [sd/MCPError]
   :code [:= error-code-missing-required-client-capability]
   :data (su/ts-object
          ;; The capabilities the server requires from the client to
          ;; process this request.
          :requiredCapabilities ClientCapabilities)))


(def MissingRequiredClientCapabilityError
  "Returned when processing a request requires a capability the client did not
   declare in `clientCapabilities`. For HTTP, the response status code MUST be
   `400 Bad Request`.

   @example Missing elicitation capability

   @category Errors"
  (su/ts-extends
   [sd/JSONRPCErrorResponse]
   :error MissingRequiredClientCapabilityErrorOnly))


(def EmptyResult
  "A result that indicates success but carries no data.
   
   @category Common Types"
  Result)


(def InputRequest
  [:or
   sd/CreateMessageRequest
   sd/ListRootsRequest
   sd/ElicitRequest])


(def InputResponse
  [:or
   sd/CreateMessageResult
   sd/ListRootsResult
   sd/ElicitResult])


(def InputRequests
  "A map of server-initiated requests that the client must fulfill.
   Keys are server-assigned identifiers; values are the request objects.

   @example Elicitation and sampling input requests

   @category Multi Round-Trip"
  [:map-of sd/attr-key InputRequest])


(def InputResponses
  "A map of client responses to server-initiated requests.
   Keys correspond to the keys in the {@link InputRequests} map;
   values are the client's result for each request.

   @example Elicitation and sampling input responses

   @category Multi Round-Trip"
  [:map-of sd/attr-key InputResponse])


(def InputRequiredResult
  "An InputRequiredResult sent by the server to indicate that additional
   input is needed before the request can be completed.

   At least one of `inputRequests` or `requestState` MUST be present.
   @example InputRequiredResult with elicitation and sampling input requests
                                and request state

   @example InputRequiredResult with request state only (load shedding)

   @category Multi Round-Trip"
  (su/ts-extends
   [Result]
   ;; Requests issued by the server that must be complete before the
   ;; client can retry the original request.
   :inputRequests? InputRequests
   ;; Request state to be passed back to the server when the client
   ;; retries the original request.
   ;; Note: The client must treat this as an opaque blob; it must not
   ;; interpret it in any way.
   :requestState? :string))


(def InputResponseRequestParams
  "Request parameter type that includes input responses and request state.
   These parameters may be included in any client-initiated request."
  (su/ts-extends
   [RequestParams]
   ;; New field to carry the responses for the server's requests from the
   ;; InputRequiredResult message.  For each key in the response's inputRequests
   ;; field, the same key must appear here with the associated response.
   :inputResponses? InputResponses
   ;; Request state passed back to the server from the client.
   :requestState? :string))


(def DiscoverRequest
  "A request from the client asking the server to advertise its supported
   protocol versions, capabilities, and other metadata. Servers **MUST**
   implement `server/discover`. Clients **MAY** call it but are not required
   to — version negotiation can also happen inline via per-request `_meta`.

   @example Discover request

   @category `server/discover`"
  (su/ts-extends
   [JSONRPCRequest]
   :method [:= method-server-discover]
   :params RequestParams))


(def CacheableResult
  "A result that supports a time-to-live (TTL) hint for client-side caching."
  (su/ts-extends
   [Result]
   ;; A hint from the server indicating how long (in milliseconds) the
   ;; client MAY cache this response before re-fetching. Semantics are
   ;; analogous to HTTP Cache-Control max-age.
   ;;
   ;; - If 0, The response SHOULD be considered immediately stale,
   ;;   The client MAY re-fetch every time the result is needed.
   ;; - If positive, the client SHOULD consider the result fresh for this many
   ;;   milliseconds after receiving the response.
   ;;
   ;; @minimum 0
   :ttlMs number?
   ;; Indicates the intended scope of the cached response, analogous to HTTP
   ;; `Cache-Control: public` vs `Cache-Control: private`.
   ;;
   ;; - `"public"`: The response does not contain user-specific data. Any
   ;;   client or intermediary (e.g., shared gateway, caching proxy) MAY cache
   ;;   the response and serve it across authorization contexts.
   ;; - `"private"`: The response MAY be cached and reused only within the
   ;;   same authorization context. Caches MUST NOT be shared across
   ;;   authorization contexts (e.g., a different access token requires a
   ;;   different cache).
   :cacheScope [:enum "public" "private"]))


(def DiscoverResult
  "The result returned by the server for a
   {@link DiscoverRequest | server/discover} request.
   
   @example Server capabilities discovery

   @category `server/discover`"
  (su/ts-extends
   [CacheableResult]
   ;; MCP Protocol Versions this server supports. The client should choose a
   ;; version from this list for use in subsequent requests.
   :supportedVersions [:vector :string]
   ;; The capabilities of the server.
   :capabilities ServerCapabilities
   ;; Natural-language guidance describing the server and its features.

   ;; This can be used by clients to improve an LLM's understanding of
   ;; available tools (e.g., by including it in a system prompt) . It should
   ;; focus on information that helps the model use the server effectively
   ;; and should not duplicate information already in tool descriptions.
   :instructions? :string))


(def DiscoverResultResponse
  " A successful response from the server for a
   {@link DiscoverRequest | server/discover} request.

   @example Discover result response

   @category `server/discover`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result DiscoverResult))


(defn update-meta
  "Patch :_meta into (older) destination schema from source schema."
  [into-schema from-schema from-schema-name]
  (if-let [new-meta (su/find-schema-entry from-schema :_meta)]
    (let [new-entity (->> (constantly new-meta)
                          (su/update-schema-entry into-schema :_meta))]
      ;(u/dprint (format "Haystack ():" from-schema-name) from-schema)
      ;(u/dprint "Needle:" new-meta)
      ;(u/dprint "Grafted:" new-entity)
      new-entity)
    (u/expected! from-schema (format ":_meta in %s to be found"
                                     from-schema-name))))


(def ListResourcesResult
  "The result returned by the server for a
   {@link ListResourcesRequest | resources/list} request.

   @example Resources list with cursor and TTL

   @category `resources/list`"
  (su/ts-extends
   [(update-meta sd/PaginatedResult
                 #_<-from- CacheableResult "CacheableResult")
    CacheableResult]
   :resources [:vector sd/Resource]))


(def ListResourcesResultResponse
  "A successful response from the server for a
   {@link ListResourcesRequest | resources/list} request.

   @example List resources result response

   @category `resources/list`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result ListResourcesResult))


(def ListResourceTemplatesResult
  "The result returned by the server for a
   {@link ListResourceTemplatesRequest | resources/templates/list} request.
   
   @example Resource templates list with cursor and TTL

   @category `resources/templates/list`"
  (su/ts-extends
   [(update-meta sd/PaginatedResult
                 #_<-from- CacheableResult "CacheableResult")
    CacheableResult]
   :resourceTemplates [:vector sd/ResourceTemplate]))


(def ListResourceTemplatesResultResponse
  "A successful response from the server for a
   {@link ListResourceTemplatesRequest | resources/templates/list} request.

   @example List resource templates result response

   @category `resources/templates/list`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result ListResourceTemplatesResult))


(def ResourceRequestParams
  "Common params for resource-related requests."
  (su/ts-extends
   [RequestParams]
   ;; The URI of the resource. The URI can use any protocol; it is up to
   ;; the server how to interpret it.
   ;;
   ;; @format uri
   :uri :string))


(def ReadResourceRequestParams
  "Parameters for a `resources/read` request.
   
   @category `resources/read`"
  (su/ts-extends
   [ResourceRequestParams
    InputResponseRequestParams]))


(def ReadResourceRequest
  "Sent from the client to the server, to read a specific resource URI.
   
   @example Read resource request

   @category `resources/read`"
  (su/ts-extends
   [JSONRPCRequest]
   :method [:= sd/method-resources-read]
   :params ReadResourceRequestParams))


(def ReadResourceResult
  "The result returned by the server for a
   {@link ReadResourceRequest | resources/read} request.

   @example File resource contents

   @category `resources/read`"
  (su/ts-extends
   [CacheableResult]
   :contents [:or sd/TextResourceContents sd/BlobResourceContents]))


(def ReadResourceResultResponse
  "A successful response from the server for a
   {@link ReadResourceRequest | resources/read} request.

   @example Read resource result response

   @example Read resource result response with TTL

   @category `resources/read`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result [:or ReadResourceResult InputRequiredResult]))


(def ResourceListChangedNotification
  "An optional notification from the server to the client, informing it
   that the list of resources it can read from has changed. This is only
   delivered on a
   {@link SubscriptionsListenRequest | subscriptions/listen} stream when
   the client requested it via the `resourcesListChanged` filter field.

   @example Resources list changed

   @category `notifications/resources/list_changed`"
  (su/ts-extends
   [JSONRPCNotification]
   :method [:= sd/method-notifications-resources-list_changed]
   :params? NotificationParams))


(def SubscriptionFilter
  "* The set of notification types a client may opt in to on a
 * {@link SubscriptionsListenRequest | subscriptions/listen} request.
 *
 * Each notification type is **opt-in**; the server **MUST NOT** send
 * notification types the client has not explicitly requested here.
 *
 * @category `subscriptions/listen`"
  (su/ts-object
   ;; If true, receive
   ;; {@link ToolListChangedNotification | notifications/tools/list_changed}.
   :toolsListChanged? :boolean
   ;; If true, receive
   ;; {@link PromptListChangedNotification | notifications/prompts/list_changed}.
   :promptsListChanged? :boolean
   ;; If true, receive
   ;; {@link ResourceListChangedNotification | notifications/resources/list_changed}.
   :resourcesListChanged? :boolean
   ;; Subscribe to
   ;; {@link ResourceUpdatedNotification | notifications/resources/updated}
   ;; for these resource URIs.
   ;; Replaces the former `resources/subscribe` RPC.
   :resourceSubscriptions? [:vector :string]))


(def SubscriptionsListenRequestParams
  "Parameters for a {@link SubscriptionsListenRequest | subscriptions/listen}
   request.

   @category `subscriptions/listen`"
  (su/ts-extends
   [RequestParams]
   ;; The notifications the client opts in to on this stream. The server
   ;; **MUST NOT** send notification types the client has not explicitly
   ;; requested.
   :notifications SubscriptionFilter))


(def SubscriptionsListenRequest
  "Sent from the client to open a long-lived channel for receiving
   notifications outside the context of a specific request. Replaces the
   previous HTTP GET endpoint and ensures consistent behavior between
   HTTP and STDIO.

   @example Listen for tools and resource list changes

   @category `subscriptions/listen`"
  (su/ts-extends
   [JSONRPCRequest]
   :method [:= method-subscriptions-listen]
   :params SubscriptionsListenRequestParams))


(def SubscriptionsListenResultMetaObject
  "Extends {@link ResultMetaObject} with the subscription-stream
   identifier carried by a {@link SubscriptionsListenResult}. All key
   naming rules from `MetaObject` apply.
   
   @see {@link MetaObject} for key naming rules and reserved prefixes.
   @category `subscriptions/listen`"
  (su/ts-extends
   [ResultMetaObject]
   ;; Identifies the subscription stream this response closes, so the
   ;; client can correlate it with the originating subscription —
   ;; mirroring the same key on the stream's notifications. The value is
   ;; the JSON-RPC ID of the `subscriptions/listen` request that opened
   ;; the stream (and equals this response's `id`).
   key-subscription-id sd/RequestId))


(def SubscriptionsListenResult
  "The response to a
   {@link SubscriptionsListenRequest | subscriptions/listen} request,
   signalling that the subscription has ended gracefully (for example,
   during server shutdown). Because the listen stream is long-lived,
   this result is sent only when the server tears the subscription down;
   an abrupt transport close carries no response. The result body is
   otherwise empty.

   @example Subscription closed gracefully

   @category `subscriptions/listen`"
  (su/ts-extends
   [Result]
   :_meta SubscriptionsListenResultMetaObject))


(def SubscriptionsListenResultResponse
  "A successful response from the server for a
   {@link SubscriptionsListenRequest | subscriptions/listen} request,
   sent when the server tears the subscription down gracefully.

   @example Subscription closed gracefully response

   @category `subscriptions/listen`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result SubscriptionsListenResult))


(def SubscriptionsAcknowledgedNotificationParams
  "Parameters for a
   {@link SubscriptionsAcknowledgedNotification | notifications/subscriptions/acknowledged}
   notification.

   @category `notifications/subscriptions/acknowledged`"
  (su/ts-extends
   [NotificationParams]
   ;; The subset of requested notification types the server agreed to
   ;; honor. Only includes notification types the server actually
   ;; supports; if the client requested an unsupported type (e.g.,
   ;; `promptsListChanged` when the server has no prompts), it is
   ;; omitted from this set.
   :notifications SubscriptionFilter))


(def SubscriptionsAcknowledgedNotification
  "Sent by the server to acknowledge that a
   {@link SubscriptionsListenRequest | subscriptions/listen}
   subscription has been established and to report which notification
   types it agreed to honor.

   This notification MUST be the first message the server sends carrying
   the subscription's ID in `io.modelcontextprotocol/subscriptionId`.
   The server MUST NOT send any notification on the subscription before
   acknowledging it. On stdio, where every subscription shares one
   channel, this ordering is defined per subscription ID and not per
   channel: messages belonging to other subscriptions MAY be interleaved
   before it.

   @example Listen acknowledged

   @category `notifications/subscriptions/acknowledged`"
  (su/ts-extends
   [JSONRPCNotification]
   :method [:= method-notifications-subscriptions-acknowledged]
   :params SubscriptionsAcknowledgedNotificationParams))


(def ResourceUpdatedNotificationParams
  "Parameters for a `notifications/resources/updated` notification.
   
   @example File resource updated

   @category `notifications/resources/updated`"
  (su/ts-extends
   [NotificationParams]
   ;; The URI of the resource that has been updated. This might be a
   ;; sub-resource of the one that the client actually subscribed to.
   ;;
   ;; @format uri
   :uri :string))


(def ResourceUpdatedNotification
  "A notification from the server to the client, informing it that a
   resource has changed and may need to be read again. This is only sent
   for resources the client opted in to via the `resourceSubscriptions`
   field of a {@link SubscriptionsListenRequest | subscriptions/listen}
   request.

   @example File resource updated notification

   @category `notifications/resources/updated`"
  (su/ts-extends
   [JSONRPCNotification]
   :method [:= sd/method-notifications-resources-updated]
   :params ResourceUpdatedNotificationParams))


(def ListPromptsResult
  "The result returned by the server for a
   {@link ListPromptsRequest | prompts/list} request.

   @example Prompts list with cursor and TTL

   @category `prompts/list`"
  (su/ts-extends
   [(update-meta sd/PaginatedResult
                 #_<-from- CacheableResult "CacheableResult")
    CacheableResult]
   :prompts [:vector sd/Prompt]))


(def ListPromptsResultResponse
  "A successful response from the server for a
   {@link ListPromptsRequest | prompts/list} request.

   @example List prompts result response

   @category `prompts/list`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result ListPromptsResult))


(def GetPromptRequestParams
  "Parameters for a `prompts/get` request.
   
   @example Get code review prompt

   @category `prompts/get`"
  (su/ts-extends
   [InputResponseRequestParams]
   ;; The name of the prompt or prompt template.
   :name :string
   ;; Arguments to use for templating the prompt.
   :arguments? sd/attr-map))


(def GetPromptRequest
  "* Used by the client to get a prompt provided by the server.
 *
 * @example Get prompt request
 * {@includeCode ./examples/GetPromptRequest/get-prompt-request.json}
 *
 * @category `prompts/get`"
  (su/ts-extends
   [JSONRPCRequest]
   :method [:= sd/method-prompts-get]
   :params GetPromptRequestParams))


(def GetPromptResultResponse
  "* A successful response from the server for a {@link GetPromptRequest | prompts/get} request.
 *
 * @example Get prompt result response
 * {@includeCode ./examples/GetPromptResultResponse/get-prompt-result-response.json}
 *
 * @category `prompts/get`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result [:or
            sd/GetPromptResult
            InputRequiredResult]))


(def ListToolsResult
  "The result returned by the server for a
   {@link ListToolsRequest | tools/list} request.

   @example Tools list with cursor and TTL

   @category `tools/list`"
  (su/ts-extends
   [(update-meta sd/PaginatedResult
                 #_<-from- CacheableResult "CacheableResult")
    CacheableResult]
   :tools [:vector sd/Tool]))


(def ListToolsResultResponse
  "A successful response from the server for a
   {@link ListToolsRequest | tools/list} request.

   @example List tools result response

   @category `tools/list`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result ListToolsResult))


(def CallToolResultResponse
  "A successful response from the server for a
   {@link CallToolRequest | tools/call} request.

   @example Call tool result response

   @category `tools/call`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result [:or
            sd/CallToolResult
            InputRequiredResult]))


(def CallToolRequestParams
  "Parameters for a `tools/call` request.
   
   @example `get_weather` tool call params

   @example Tool call params with progress token

   @category `tools/call`"
  (su/ts-extends
   [InputResponseRequestParams]
   ;; The name of the tool
   :name :string
   ;; Arguments to use for the tool call.
   ;; Keys must be keywords here, because tool impl destructures them
   :arguments? [:map-of :keyword :any]))


(def CallToolRequest
  "Used by the client to invoke a tool provided by the server.
   
   @example Call tool request

   @category `tools/call`"
  (su/ts-extends
   [JSONRPCRequest]
   :method [:= sd/method-tools-call]
   :params CallToolRequestParams))


(def ToolListChangedNotification
  "An optional notification from the server to the client, informing it
   that the list of tools it offers has changed. This is only delivered
   on a {@link SubscriptionsListenRequest | subscriptions/listen} stream
   when the client requested it via the `toolsListChanged` filter field.

   @example Tools list changed

   @category `notifications/tools/list_changed`"
  (su/ts-extends
   [JSONRPCNotification]
   :method [:= sd/method-notifications-tools-list_changed]
   :params? NotificationParams))


;; ------------------
;; Tool
;; A tool definition in MCP 2026-07-28 spec has adopted full JSON-schema
;; with recursive schema definition and all bells and whistles. However,
;; the implementation here is still carrying over the schema supported
;; as of the MCP 2025-11-25 spec, for ease of implementation.
;; Future reference: https://json-schema.org/
;; ------------------


(def CompleteResultResponse
  "A successful response from the server for a
   {@link CompleteRequest | completion/complete} request.

   @example Completion result response

   @category `completion/complete`"
  (su/ts-extends
   [JSONRPCResultResponse]
   :result sd/CompleteResult))


(def ElicitRequestURLParams
  "The parameters for a request to elicit information from the user via
   a URL in the client.

   @example Elicit sensitive data

   @category `elicitation/create`"
  (su/ts-object
   ;; The elicitation mode.
   :mode [:= "url"]
   ;; The message to present to the user explaining why the interaction
   ;; is needed.
   :message :string
   ;; The URL that the user should navigate to.
   ;;
   ;; @format uri
   :url :string))


(def ElicitRequestParams
  "The parameters for a request to elicit additional information from
   the user via the client.

   @category `elicitation/create`"
  [:or
   sd/ElicitRequestFormParams
   ElicitRequestURLParams])


(def ElicitRequest
  "A request from the server to elicit additional information from the
   user via the client.

   @example Elicitation request

   @category `elicitation/create`"
  (su/ts-object
   :method [:= sd/method-elicitation-create]
   :params ElicitRequestParams))


;; ----- Client messages -----


(def ClientRequest
  [:or
   DiscoverRequest
   sd/CompleteRequest
   GetPromptRequest
   sd/ListPromptsRequest
   sd/ListResourcesRequest
   sd/ListResourceTemplatesRequest
   ReadResourceRequest
   SubscriptionsListenRequest
   CallToolRequest
   sd/ListToolsRequest])


(def ClientNotification
  sd/CancelledNotification)


(def ClientResult
  EmptyResult)


;; ----- Server messages -----


(def ServerNotification
  [:or
   sd/CancelledNotification
   sd/ProgressNotification
   sd/LoggingMessageNotification
   ResourceUpdatedNotification
   ResourceListChangedNotification
   ToolListChangedNotification
   sd/PromptListChangedNotification
   SubscriptionsAcknowledgedNotification])


(def ServerResult
  [:or
   EmptyResult
   DiscoverResult
   sd/CompleteResult
   sd/GetPromptResult
   ListPromptsResult
   ListResourceTemplatesResult
   ListResourcesResult
   ReadResourceResult
   SubscriptionsListenResult
   sd/CallToolResult
   ListToolsResult
   InputRequiredResult])


;; ----- Method-names and Schema -----


(def mcp-methods
  "Map of MCP method-name to its schema, as listed below:
   [request-schema response-schema] - request and response schema available
   [request-schema]                 - when only request schema is available
   []                               - no schema is available"
  {;; --- both request/response schema available ---
   ;; handled by server
   method-server-discover [DiscoverRequest
                           DiscoverResultResponse]
   sd/method-resources-list [sd/ListResourcesRequest
                             ListResourcesResultResponse]
   sd/method-resources-templates-list [sd/ListResourceTemplatesRequest
                                       ListResourceTemplatesResultResponse]
   sd/method-resources-read [ReadResourceRequest
                             ReadResourceResultResponse]
   method-subscriptions-listen [SubscriptionsListenRequest
                                SubscriptionsListenResultResponse]
   sd/method-prompts-list [sd/ListPromptsRequest
                           ListPromptsResultResponse]
   sd/method-prompts-get [GetPromptRequest
                          GetPromptResultResponse]
   sd/method-tools-list [sd/ListToolsRequest
                         ListToolsResultResponse]
   sd/method-tools-call [CallToolRequest
                         CallToolResultResponse]
   sd/method-completion-complete [sd/CompleteRequest
                                  CompleteResultResponse]
   ;; handled by client
   #_deprecated sd/method-roots-list [sd/ListRootsRequest
                                      sd/ListRootsResult]
   #_deprecated sd/method-sampling-createMessage [sd/CreateMessageRequest
                                                  sd/CreateMessageResult]
   sd/method-elicitation-create [ElicitRequest
                                 sd/ElicitResult]
   ;; notifications - without response schema
   sd/method-notifications-cancelled [sd/CancelledNotification]
   sd/method-notifications-progress [sd/ProgressNotification]
   sd/method-notifications-resources-list_changed [ResourceListChangedNotification]
   method-notifications-subscriptions-acknowledged [SubscriptionsAcknowledgedNotification]
   sd/method-notifications-resources-updated [ResourceUpdatedNotification]
   sd/method-notifications-prompts-list_changed [sd/PromptListChangedNotification]
   sd/method-notifications-tools-list_changed [ToolListChangedNotification]
   #_deprecated sd/method-notifications-message [sd/LoggingMessageNotification]
   ;;
   })
