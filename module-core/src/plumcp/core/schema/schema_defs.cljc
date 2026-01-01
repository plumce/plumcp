(ns plumcp.core.schema.schema-defs
  "MCP JSON-RPC Schema
   Ref:
   1. https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/schema/2025-06-18/schema.ts
   2. https://github.com/modelcontextprotocol/modelcontextprotocol/tree/main/schema/2025-03-26/schema.ts

   Adapted from the TypeScript file referenced above. This schema is in
   raw Malli format. Malli dependency/API is not required/referenced."
  (:require
   [plumcp.core.schema.schema-util :as su]))


(def protocol-version-2025-11-25 "2025-11-25")  ; not implemented yet
(def protocol-version-2025-06-18 "2025-06-18")
(def protocol-version-2025-03-26 "2025-03-26")
(def protocol-version-2024-11-05 "2024-11-05")

(def protocol-versions-supported (-> [protocol-version-2025-06-18
                                      protocol-version-2025-03-26]
                                     sort
                                     reverse))
(def protocol-version-max (first protocol-versions-supported))
(def jsonrpc-version "2.0")


;; ----- Error codes -----


;; as per JSON-RPC spec
(def error-code-parse-error -32700)
(def error-code-invalid-request -32600)
(def error-code-method-not-found -32601)
(def error-code-invalid-params -32602)
(def error-code-internal-error -32603)
;; -32000 to -32099: Reserved for implementation-defined server-errors.

;; MCP-specific
(def error-code-request-timed-out -32001)
(def error-code-resource-not-found -32002)


;; ----- HTTP headers -----


(def mcp-session-id-header "Mcp-Session-Id")
(def mcp-session-id-header-lower "mcp-session-id")
(def mcp-last-event-id-header "Last-Event-ID")
(def mcp-last-event-id-header-lower "last-event-id")


;; ----- HTTP Auth URIs -----


(def uri-oauth-protected-resource   "/.well-known/oauth-protected-resource")
(def uri-oauth-authorization-server "/.well-known/oauth-authorization-server")


;; --- method names ---


;; Client/Server requests expecting result
(def method-ping "ping")

;; Client-requests expecting result
(def method-initialize "initialize")
(def method-prompts-list "prompts/list")
(def method-resources-list "resources/list")
(def method-tools-list "tools/list")
(def method-resources-templates-list "resources/templates/list")
(def method-tools-call "tools/call")
(def method-resources-read "resources/read")
(def method-prompts-get "prompts/get")
(def method-completion-complete "completion/complete")

;; Server-requests expecting result
(def method-sampling-createMessage "sampling/createMessage")
(def method-roots-list "roots/list")
(def method-elicitation-create "elicitation/create")

;; Methods without schema
(def method-logging-list "logging/list")

;; Methods without response
(def method-resources-subscribe "resources/subscribe")
(def method-resources-unsubscribe "resources/unsubscribe")
(def method-logging-setLevel "logging/setLevel")

;; Notifications
(def method-notifications-initialized "notifications/initialized")
(def method-notifications-cancelled "notifications/cancelled")
(def method-notifications-progress "notifications/progress")
(def method-notifications-resources-list_changed "notifications/resources/list_changed")
(def method-notifications-resources-updated "notifications/resources/updated")
(def method-notifications-message "notifications/message")
(def method-notifications-prompts-list_changed "notifications/prompts/list_changed")
(def method-notifications-tools-list_changed "notifications/tools/list_changed")
(def method-notifications-roots-list_changed "notifications/roots/list_changed")


;; --- Result keys ---


(def result-key-prompts :prompts)
(def result-key-tools :tools)
(def result-key-resources :resources)
(def result-key-resource-templates :resourceTemplates)


;; --- Roles ---


(def role-user "user")
(def role-assistant "assistant")


;; --- Structures ---


(def ProgressToken
  "A progress token, used to associate progress notifications with the
   original request."
  [:or :string number?])

(def Request
  (su/ts-object
   :method :string
   :params? {;; See [specification/2025-06-18/basic/index#general-fields]
             ;; for notes on _meta usage.
             :_meta? {;; If specified, the caller is requesting out-of-band
                      ;; progress notifications for this request (as
                      ;; represented by notifications/progress). The
                      ;; value of this parameter is an opaque token that
                      ;; will be attached to any subsequent
                      ;; notifications. The receiver is not obligated to
                      ;; provide these notifications.
                      :progressToken? ProgressToken}}))

(def Notification
  (su/ts-object
   :method :string
   :params? {;; See [specification/2025-06-18/basic/index#general-fields]
             ;; for notes on _meta usage.
             :_meta? [:map-of :string :any]}))

(def InitializedNotification
  "This notification is sent from the client to the server after 
   initialization has finished."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-initialized]))

(def JSONRPCNotification
  "A notification which does not expect a response."
  (su/ts-extends
   [Notification]
   :jsonrpc :string))

(def RequestId
  "A uniquely identifying ID for a request in JSON-RPC."
  [:or :string number?])

(def Result
  (su/ts-object
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def EmptyResult
  "A response that indicates success but carries no data."
  Result)


;; ----- JSON-RPC types -----


(def JSONRPCRequest
  "A request that expects a response."
  (su/ts-extends
   [Request]
   :jsonrpc :string
   :id RequestId))

(def JSONRPCResponse
  "A successful (non-error) response to a request."
  (su/ts-object
   :jsonrpc :string
   :id RequestId
   :result Result))

(def JSONRPCError
  "A response to a request that indicates an error occurred."
  (su/ts-object
   :jsonrpc :string
   :id RequestId
   :error {;; The error type that occurred.
           :code number?
           ;; A short description of the error. The message SHOULD be
           ;; limited to a concise single sentence.
           :message :string
           ;; Additional information about the error. The value of this
           ;; member is defined by the sender (e.g. detailed error
           ;; information, nested errors etc.).
           :data? :any}))

(def JSONRPCMessage
  "Refers to any valid JSON-RPC object that can be decoded off the wire,
   or encoded to be sent."
  [:or
   JSONRPCRequest
   JSONRPCNotification
   JSONRPCResponse
   JSONRPCError])


;; ----- end of JSON-RPC stuff -----


(def CancelledNotification
  "This notification can be sent by either side to indicate that it is
   cancelling a previously-issued request.
   The request SHOULD still be in-flight, but due to communication
   latency, it is always possible that this notification MAY arrive after
   the request has already finished. This notification indicates that the
   result will be unused, so any associated processing SHOULD cease.
   A client MUST NOT attempt to cancel its `initialize` request."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-cancelled]
   :params {;; The ID of the request to cancel.
            ;; This MUST correspond to the ID of a request previously
            ;; issued in the same direction.
            :requestId RequestId
            ;; An optional string describing the reason for the
            ;; cancellation. This MAY be logged or presented to the user.
            :reason? :string}))

(def ClientCapabilities
  "Capabilities a client may support. Known capabilities are defined 
   here, in this schema, but this is not a closed set: any client can
   define its own, additional capabilities."
  (su/ts-object
   ;; Experimental, non-standard capabilities that the client supports.
   :experimental? :map
   ;; Present if the client supports listing roots.
   :roots? {;; Whether the client supports notifications for changes to
            ;; the roots list.
            :listChanged? :boolean}
   ;; Present if the client supports sampling from an LLM.
   :sampling? :map
   ;; Present if the client supports elicitation from the server.
   :elicitation? :map))

(def ServerCapabilities
  "Capabilities that a server may support. Known capabilities are
   defined here, in this schema, but this is not a closed set: any
   server can define its own, additional capabilities."
  (su/ts-object
   ;; Experimental, non-standard capabilities that the server supports.
   :experimental? [:map-of :string :any]
   ;; Present if the server supports sending log messages to the client.
   :logging? :map
   ;; Present if the server supports argument autocompletion suggestions.
   :completions? :map
   ;; Present if the server offers any prompt templates.
   :prompts? {;; Whether this server supports notifications for changes
              ;; to the prompt list.
              :listChanged? :boolean}
   ;; Present if the server offers any resources to read.
   :resources? {;; Whether this server supports subscribing to resource
                ;; updates.
                :subscribe? :boolean
                ;; Whether this server supports notifications for
                ;; changes to the resource list.
                :listChanged? :boolean}
   ;; Present if the server offers any tools to call.
   :tools? {;; Whether this server supports notifications for changes to
            ;; the tool list.
            :listChanged? :boolean}))

(def BaseMetadata
  "Base interface for metadata with name (identifier) and title (display
   name) properties."
  (su/ts-object
   ;; Intended for programmatic or logical use, but used as a display
   ;; name in past specs or fallback (if title isn't present).
   :name :string
   ;; Intended for UI and end-user contexts — optimized to be human-readable
   ;; and easily understood, even by those unfamiliar with domain-specific
   ;; terminology. If not provided, the name should be used for display
   ;; (except for Tool, where `annotations.title` should be given
   ;; precedence over using `name`, if present).
   :title? :string))

(def Implementation
  "Describes the name and version of an MCP implementation, with an
   optional title for UI representation."
  (su/ts-extends
   [BaseMetadata]
   :version :string))

(def InitializeRequest
  "This request is sent from the client to the server when it first
   connects, asking it to begin initialization."
  (su/ts-extends
   [Request]
   :method [:= method-initialize]
   :params {;; The latest version of the Model Context Protocol that the
            ;; client supports. The client MAY decide to support older
            ;; versions as well.
            :protocolVersion :string
            :capabilities ClientCapabilities
            :clientInfo Implementation}))

(def InitializeResult
  "After receiving an initialize request from the client, the server
   sends this response."
  (su/ts-extends
   [Result]
   ;; The version of the Model Context Protocol that the server wants to
   ;; use. This may not match the version that the client requested. If
   ;; the client cannot support this version, it MUST disconnect.
   :protocolVersion :string
   :capabilities ServerCapabilities
   :serverInfo Implementation
   ;; Instructions describing how to use the server and its features.
   ;; This can be used by clients to improve the LLM's understanding of
   ;; available tools, resources, etc. It can be thought of like a "hint"
   ;; to the model. For example, this information MAY be added to the
   ;; system prompt.
   :instructions? :string))

(def PingRequest
  "A ping, issued by either the server or the client, to check that the
   other party is still alive. The receiver must promptly respond, or 
   else may be disconnected."
  (su/ts-extends
   [Request]
   :method [:= method-ping]))

(def ProgressNotification
  "An out-of-band notification used to inform the receiver of a progress 
   update for a long-running request."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-progress]
   :params {;; The progress token which was given in the initial request,
            ;; used to associate this notification with the request that
            ;; is proceeding.
            :progressToken ProgressToken
            ;; The progress thus far. This should increase every time
            ;; progress is made, even if the total is unknown.
            :progress number?
            ;; Total number of items to process (or total progress
            ;; required), if known.
            :total? number?
            ;; An optional message describing the current progress.
            :message? :string}))

(def Cursor
  "An opaque token used to represent a cursor for pagination."
  :string)

(def PaginatedRequest
  (su/ts-extends
   [Request]
   :params? {;; An opaque token representing the current pagination
             ;; position. If provided, the server should return results
             ;; starting after this cursor.
             :cursor? Cursor}))

(def PaginatedResult
  (su/ts-extends
   [Result]
   ;; An opaque token representing the pagination position after the last
   ;; returned result. If present, there may be more results available.
   :nextCursor? Cursor))

(def ListResourcesRequest
  "Sent from the client to request a list of resources the server has."
  (su/ts-extends
   [PaginatedRequest]
   :method [:= method-resources-list]))

(def Role
  "The sender or recipient of messages and data in a conversation."
  [:enum
   role-user
   role-assistant])

(def Annotations
  "Optional annotations for the client. The client can use annotations
   to inform how objects are used or displayed"
  (su/ts-object
   ;; Describes who the intended customer of this object or data is.
   ;; It can include multiple entries to indicate content useful for
   ;; multiple audiences (e.g., `["user", "assistant"]`).
   :audience? [:vector Role]
   ;; Describes how important this data is for operating the server.
   ;;
   ;; A value of 1 means "most important," and indicates that the data
   ;; is effectively required, while 0 means "least important," and
   ;; indicates that the data is entirely optional.
   :priority? [number? {:min 0 :max 1}]
   ;; The moment the resource was last modified, as an ISO 8601
   ;; formatted string.
   ;;
   ;; Should be an ISO 8601 formatted string (e.g. "2025-01-12T15:00:58Z")
   ;;
   ;; Examples: last activity timestamp in an open file, timestamp when
   ;; the resource was attached, etc.
   :lastModified? :string))

(def Resource
  "A known resource that the server is capable of reading."
  (su/ts-extends
   [BaseMetadata]
   ;; The URI of this resource.
   :uri :string
   ;; A description of what this resource represents.
   ;; This can be used by clients to improve the LLM's understanding of
   ;; available resources. It can be thought of like a "hint" to the model.
   :description? :string
   ;; The MIME type of this resource, if known.
   :mimeType? :string
   ;; Optional annotations for the client.
   :annotations? Annotations
   ;; The size of the raw resource content, in bytes (i.e., before base64
   ;; encoding or any tokenization), if known.
   ;; This can be used by Hosts to display file sizes and estimate
   ;; context window usage.
   :size? number?
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ListResourcesResult
  "The server's response to a resources/list request from the client."
  (su/ts-extends
   [PaginatedResult]
   :resources [:vector Resource]))

(def ListResourceTemplatesRequest
  "Sent from the client to request a list of resource templates the
   server has."
  (su/ts-extends
   [PaginatedRequest]
   :method [:= method-resources-templates-list]))

(def ResourceTemplate
  "A template description for resources available on the server."
  (su/ts-extends
   [BaseMetadata]
   ;; A URI template (according to RFC 6570) that can be used to
   ;; construct resource URIs.
   :uriTemplate :string
   ;; A description of what this template is for.
   ;; This can be used by clients to improve the LLM's understanding of
   ;; available resources. It can be thought of like a "hint" to the model.
   :description? :string
   ;; The MIME type for all resources that match this template. Thi
   ;; should only be included if all resources matching this template
   ;; have the same type.
   :mimeType? :string
   ;; Optional annotations for the client.
   :annotations? Annotations
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ListResourceTemplatesResult
  "The server's response to a resources/templates/list request from the
   client."
  (su/ts-extends
   [PaginatedResult]
   :resourceTemplates [:vector ResourceTemplate]))

(def ReadResourceRequest
  "Sent from the client to the server, to read a specific resource URI."
  (su/ts-extends
   [Request]
   :method [:= method-resources-read]
   :params {;; The URI of the resource to read. The URI can use any
            ;; protocol; it is up to the server how to interpret it.
            :uri :string}))

(def ResourceContents
  "The contents of a specific resource or sub-resource."
  (su/ts-object
   ;; The URI of this resource.
   :uri :string
   ;; The MIME type of this resource, if known.
   :mimeType? :string
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def TextResourceContents
  (su/ts-extends
   [ResourceContents]
   ;; The text of the item. This must only be set if the item can
   ;; actually be represented as text (not binary data).
   :text :string))

(def BlobResourceContents
  (su/ts-extends
   [ResourceContents]
   ;; A base64-encoded string representing the binary data of the item.
   :blob :string))

(def ReadResourceResult
  "The server's response to a resources/read request from the client."
  (su/ts-extends
   [Result]
   :contents [:vector [:or TextResourceContents BlobResourceContents]]))

(def ResourceListChangedNotification
  "An optional notification from the server to the client, informing it
   that the list of resources it can read from has changed. This may be
   issued by servers without any previous subscription from the client."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-resources-list_changed]))

(def SubscribeRequest
  "Sent from the client to request resources/updated notifications from 
   the server whenever a particular resource changes."
  (su/ts-extends
   [Request]
   :method [:= method-resources-subscribe]
   :params {;; The URI of the resource to subscribe to. The URI can use
            ;; any protocol; it is up to the server how to interpret it.
            :uri :string}))

(def UnsubscribeRequest
  "Sent from the client to request cancellation of resources/updated
   notifications from the server. This should follow a previous
   resources/subscribe request."
  (su/ts-extends
   [Request]
   :method [:= method-resources-unsubscribe]
   :params {;; The URI of the resource to unsubscribe from.
            :uri :string}))

(def ResourceUpdatedNotification
  "A notification from the server to the client, informing it that a
   resource has changed and may need to be read again. This should only
   be sent if the client previously sent a resources/subscribe request."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-resources-updated]
   :params {;; The URI of the resource that has been updated. This might
            ;; be a sub-resource of the one that the client actually
            ;; subscribed to.
            :uri :string}))


;; --- Prompts ---


(def PromptArgument
  "Describes an argument that a prompt can accept."
  (su/ts-extends
   [BaseMetadata]
   ;; A human-readable description of the argument.
   :description? :string
   ;; Whether this argument must be provided.
   :required? :boolean))

(def Prompt
  "A prompt or prompt template that the server offers."
  (su/ts-extends
   [BaseMetadata]
   ;; An optional description of what this prompt provides
   :description? :string
   ;; A list of arguments to use for templating the prompt.
   :arguments? [:vector PromptArgument]
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ListPromptsRequest
  "Sent from the client to request a list of prompts and prompt
   templates the server has."
  (su/ts-extends
   [PaginatedRequest]
   :method [:= method-prompts-list]))

(def ListPromptsResult
  "The server's response to a prompts/list request from the client."
  (su/ts-extends
   [PaginatedResult]
   :prompts [:vector Prompt]))

(def GetPromptRequest
  "Used by the client to get a prompt provided by the server."
  (su/ts-extends
   [Request]
   :method [:= method-prompts-get]
   :params {;; The name of the prompt or prompt template.
            :name :string
            ;; Arguments to use for templating the prompt.
            :arguments? [:map-of [:or :keyword :string] :string]}))

(def TextContent
  "Text provided to or from an LLM."
  (su/ts-object
   :type [:= "text"]
   ;; The text content of the message.
   :text :string
   ;; Optional annotations for the client.
   :annotations? Annotations
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ImageContent
  "An image provided to or from an LLM."
  (su/ts-object
   :type [:= "image"]
   ;; The base64-encoded image data.
   :data :string
   ;; The MIME type of the image. Different providers may support
   ;; different image types.
   :mimeType :string
   ;; Optional annotations for the client.
   :annotations? Annotations
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def AudioContent
  "Audio provided to or from an LLM."
  (su/ts-object
   :type [:= "audio"]
   ;; The base64-encoded audio data.
   :data :string
   ;; The MIME type of the audio. Different providers may support
   ;; different audio types.
   :mimeType :string
   ;; Optional annotations for the client.
   :annotations? Annotations
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def EmbeddedResource
  "The contents of a resource, embedded into a prompt or tool call
   result. It is up to the client how best to render embedded resources
   for the benefit of the LLM and/or the user."
  (su/ts-object
   :type [:= "resource"]
   :resource [:or TextResourceContents BlobResourceContents]
   ;; Optional annotations for the client.
   :annotations? Annotations
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ResourceLink
  "A resource that the server is capable of reading, included in a
   prompt or tool call result.
   Note: Resource links returned by tools are not guaranteed to appear
         in the results of `resources/list` requests."
  (su/ts-extends
   [Resource]
   :type [:= "resource_link"]))

(def ContentBlock
  [:or
   TextContent
   ImageContent
   AudioContent
   ResourceLink
   EmbeddedResource])

(def PromptMessage
  "Describes a message returned as part of a prompt.
   This is similar to `SamplingMessage`, but also supports the embedding
   of resources from the MCP server."
  (su/ts-object
   :role Role
   :content ContentBlock))

(def GetPromptResult
  "The server's response to a prompts/get request from the client."
  (su/ts-extends
   [Result]
   ;; An optional description for the prompt.
   :description? :string
   :messages [:vector PromptMessage]))

(def PromptListChangedNotification
  "An optional notification from the server to the client, informing it
   that the list of prompts it offers has changed. This may be issued by
   servers without any previous subscription from the client."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-prompts-list_changed]))


;; --- Tools ---


(def ToolAnnotations
  "Additional properties describing a Tool to clients.

   NOTE: all properties in ToolAnnotations are **hints**.
   They are not guaranteed to provide a faithful description of
   tool behavior (including descriptive properties like `title`).

   Clients should never make tool use decisions based on ToolAnnotations
   received from untrusted servers."
  (su/ts-object
   ;; A human-readable title for the tool.
   :title? :string
   ;; If true, the tool does not modify its environment.
   ;;
   ;; Default: false
   :readOnlyHint? :boolean
   ;; If true, the tool may perform destructive updates to its environment.
   ;; If false, the tool performs only additive updates.
   ;;
   ;; (This property is meaningful only when `readOnlyHint == false`)
   ;;
   ;; Default: true
   :destructiveHint? :boolean
   ;; If true, calling the tool repeatedly with the same arguments
   ;; will have no additional effect on the its environment.
   ;;
   ;; (This property is meaningful only when `readOnlyHint == false`)
   ;;
   ;; Default: false
   :idempotentHint? :boolean
   ;; If true, this tool may interact with an "open world" of external
   ;; entities. If false, the tool's domain of interaction is closed.
   ;; For example, the world of a web search tool is open, whereas that
   ;; of a memory tool is not.
   ;;
   ;; Default: true
   :openWorldHint? :boolean))

(def Tool
  "Definition for a tool the client can call."
  (su/ts-extends
   [BaseMetadata]
   ;; A human-readable description of the tool.
   ;;
   ;; This can be used by clients to improve the LLM's understanding of
   ;; available tools. It can be thought of like a "hint" to the model.
   :description? :string
   ;; A JSON Schema object defining the expected parameters for the tool.
   :inputSchema {:type [:= "object"]
                 :properties? [:map-of [:or :string :keyword] :any]
                 :required? [:vector :string]}
   ;; An optional JSON Schema object defining the structure of the tool's
   ;; output returned in the structuredContent field of a CallToolResult.
   :outputSchema? {:type [:= "object"]
                   :properties? [:map-of [:or :string :keyword] :any]
                   :required? [:vector :string]}
   ;; Optional additional tool information.
   ;; Display name precedence order is: title, annotations.title, then name.
   :annotations? ToolAnnotations
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ListToolsRequest
  "Sent from the client to request a list of tools the server has."
  (su/ts-extends
   [PaginatedRequest]
   :method [:= method-tools-list]))

(def ListToolsResult
  "The server's response to a tools/list request from the client."
  (su/ts-extends
   [PaginatedResult]
   :tools [:vector Tool]))

(def CallToolResult
  "The server's response to a tool call."
  (su/ts-extends
   [Result]
   :content [:vector ContentBlock]

   ;; Whether the tool call ended in an error.
   ;;
   ;; If not set, this is assumed to be false (the call was successful).
   ;;
   ;; Any errors that originate from the tool SHOULD be reported inside
   ;; the result object, with `isError ` set to true, _not_ as an MCP
   ;; protocol-level error response. Otherwise, the LLM would not be
   ;; able to see that an error occurred and self-correct.
   ;;
   ;; However, any errors in _finding_ the tool, an error indicating that
   ;; the server does not support tool calls, or any other exceptional
   ;; conditions, should be reported as an MCP error response.
   :isError? :boolean))

(def CallToolRequest
  "Used by the client to invoke a tool provided by the server."
  (su/ts-extends
   [Request]
   :method [:= method-tools-call]
   :params {:name :string
            :arguments? [:map-of :keyword :any]}))

(def ToolListChangedNotification
  "An optional notification from the server to the client, informing it
   that the list of tools it offers has changed. This may be issued by
   servers without any previous subscription from the client."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-tools-list_changed]))


;; ----- Logging -----


(def log-level-0-emergency "emergency") ; system is unusable
(def log-level-1-alert     "alert")     ; action must be taken immediately
(def log-level-2-critical  "critical")  ; critical conditions
(def log-level-3-error     "error")     ; error conditions
(def log-level-4-warning   "warning")   ; warning conditions
(def log-level-5-notice    "notice")    ; normal but significant condition
(def log-level-6-info      "info")      ; informational messages
(def log-level-7-debug     "debug")     ; debug-level messages

(def log-level-indices {log-level-0-emergency 0
                        log-level-1-alert     1
                        log-level-2-critical  2
                        log-level-3-error     3
                        log-level-4-warning   4
                        log-level-5-notice    5
                        log-level-6-info      6
                        log-level-7-debug     7})

(def LoggingLevel
  "The severity of a log message.

   These map to syslog message severities, as specified in RFC-5424:
   https://datatracker.ietf.org/doc/html/rfc5424#section-6.2.1"
  [:enum
   log-level-7-debug
   log-level-6-info
   log-level-5-notice
   log-level-4-warning
   log-level-3-error
   log-level-2-critical
   log-level-1-alert
   log-level-0-emergency])

(def SetLevelRequest
  "A request from the client to the server, to enable or adjust logging."
  (su/ts-extends
   [Request]
   :method [:= method-logging-setLevel]
   :params {;; The level of logging that the client wants to receive
            ;; from the server. The server should send all logs at this
            ;; level and higher (i.e., more severe) to the client as
            ;; notifications/message.
            :level LoggingLevel}))

(def LoggingMessageNotification
  "Notification of a log message passed from server to client. If no
   logging/setLevel request has been sent from the client, the server
   MAY decide which messages to send automatically."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-message]
   :params {;; The severity of this log message.
            :level LoggingLevel
            ;; An optional name of the logger issuing this message.
            :logger? :string
            ;; The data to be logged, such as a string message or an
            ;; object. Any JSON serializable type is allowed here.
            :data :any}))


;; ----- Sampling -----


(def SamplingMessage
  "Describes a message issued to or received from an LLM API."
  (su/ts-object
   :role Role
   :content [:or TextContent ImageContent AudioContent]))

(def ModelHint
  "Hints to use for model selection.

   Keys not declared here are currently left unspecified by the spec and
   are up to the client to interpret."
  (su/ts-object
   ;; A hint for a model name.
   ;;
   ;; The client SHOULD treat this as a substring of a model name; for
   ;; example:
   ;;  - `claude-3-5-sonnet` should match `claude-3-5-sonnet-20241022`
   ;;  - `sonnet` should match `claude-3-5-sonnet-20241022`,
   ;;    `claude-3-sonnet-20240229`, etc.
   ;;  - `claude` should match any Claude model
   ;;
   ;; The client MAY also map the string to a different provider's model
   ;; name or a different model family, as long as it fills a similar
   ;; niche; for example:
   ;;  - `gemini-1.5-flash` could match `claude-3-haiku-20240307`
   :name? :string))

(def ModelPreferences
  "The server's preferences for model selection, requested of the client
   during sampling.

   Because LLMs can vary along multiple dimensions, choosing the \"best\"
   model is rarely straightforward.  Different models excel in different
   areas—some are faster but less capable, others are more capable but
   more expensive, and so on. This interface allows servers to express
   their priorities across multiple dimensions to help clients make an
   appropriate selection for their use case.

   These preferences are always advisory. The client MAY ignore them. It
   is also up to the client to decide how to interpret these preferences
   and how to balance them against other considerations."
  (su/ts-object
   ;; Optional hints to use for model selection.
   ;;
   ;; If multiple hints are specified, the client MUST evaluate them in
   ;; order (such that the first match is taken).
   ;;
   ;; The client SHOULD prioritize these hints over the numeric priorities,
   ;; but MAY still use the priorities to select from ambiguous matches.
   :hints? [:vector ModelHint]

   ;; How much to prioritize cost when selecting a model. A value of 0
   ;; means cost is not important, while a value of 1 means cost is the
   ;; most important factor.
   :costPriority? [number? {:min 0 :max 1}]

   ;; How much to prioritize sampling speed (latency) when selecting a
   ;; model. A value of 0 means speed is not important, while a value of
   ;; 1 means speed is the most important factor.
   :speedPriority? [number? {:min 0 :max 1}]

   ;; How much to prioritize intelligence and capabilities when selecting
   ;; a model. A value of 0 means intelligence is not important, while a
   ;; value of 1 means intelligence is the most important factor.
   :intelligencePriority? [number? {:min 0 :max 1}]))

(def CreateMessageRequest
  "A request from the server to sample an LLM via the client. The client
   has full discretion over which model to select. The client should also
   inform the user before beginning sampling, to allow them to inspect
   the request (human in the loop) and decide whether to approve it."
  (su/ts-extends
   [Request]
   :method [:= method-sampling-createMessage]
   :params {:messages [:vector SamplingMessage]
            ;; The server's preferences for which model to select. The
            ;; client MAY ignore these preferences.
            :modelPreferences? ModelPreferences
            ;; An optional system prompt the server wants to use for
            ;; sampling. The client MAY modify or omit this prompt.
            :systemPrompt? :string
            ;; A request to include context from one or more MCP servers
            ;; (including the caller), to be attached to the prompt. The
            ;; client MAY ignore this request.
            :includeContext? [:enum "none" "thisServer" "allServers"]
            :temperature? number?
            ;; The maximum number of tokens to sample, as requested by
            ;; the server. The client MAY choose to sample fewer tokens
            ;; than requested.
            :maxTokens number?
            :stopSequences? [:vector :string]
            ;; Optional metadata to pass through to the LLM provider.
            ;; The format of this metadata is provider-specific.
            :metadata? :map}))

(def CreateMessageResult
  "The client's response to a sampling/create_message request from the
   server. The client should inform the user before returning the
   sampled message, to allow them to inspect the response (human in the
   loop) and decide whether to allow the server to see it."
  (su/ts-extends
   [Result, SamplingMessage]
   ;; The name of the model that generated the message.
   :model :string
   ;; The reason why sampling stopped, if known.
   :stopReason? [:or
                 [:= "endTurn"] [:= "stopSequence"] [:= "maxTokens"]
                 :string]))


;; ----- Autocomplete -----


(def PromptReference
  "Identifies a prompt."
  (su/ts-extends
   [BaseMetadata]
   :type [:= "ref/prompt"]))

(def ResourceTemplateReference
  "A reference to a resource or resource template definition."
  (su/ts-object
   :type [:= "ref/resource"]
   ;; The URI or URI template of the resource.
   :uri :string))

(def CompleteRequest
  "A request from the client to the server, to ask for completion
   options."
  (su/ts-extends
   [Request]
   :method [:= method-completion-complete]
   :params {:ref [:or PromptReference ResourceTemplateReference]
            ;; The argument's information
            :argument {;; The name of the argument
                       :name :string
                       ;; The value of the argument to use for
                       ;; completion matching.
                       :value :string}
            ;; Additional, optional context for completions
            :context? {;; Previously-resolved variables in a
                       ;; URI template or prompt.
                       :arguments? [:map-of :string :string]}}))

(def CompleteResult
  "The server's response to a completion/complete request"
  (su/ts-extends
   [Result]
   :completion {;; An array of completion values. Must not exceed 100 items.
                :values [:vector :string]
                ;; The total number of completion options available.
                ;; This can exceed the number of values actually sent in
                ;; the response.
                :total? number?
                ;; Indicates whether there are additional completion
                ;; options beyond those provided in the current
                ;; response, even if the exact total is unknown.
                :hasMore? :boolean}))


;; ----- Roots -----


(def ListRootsRequest
  "Sent from the server to request a list of root URIs from the client.
   Roots allow servers to ask for specific directories or files to
   operate on. A common example for roots is providing a set of
   repositories or directories a server should operate on.

   This request is typically used when the server needs to understand 
   the file system structure or access specific locations that the
   client has permission to read from."
  (su/ts-extends
   [Request]
   :method [:= method-roots-list]))

(def Root
  "Represents a root directory or file that the server can operate on."
  (su/ts-object
   ;; The URI identifying the root. This *must* start with file:// for
   ;; now. This restriction may be relaxed in future versions of the
   ;; protocol to allow other URI schemes.
   :uri :string
   ;; An optional name for the root. This can be used to provide a
   ;; human-readable identifier for the root, which may be useful for
   ;; display purposes or for referencing the root in other parts of the
   ;; application.
   :name? :string
   ;; See [specification/2025-06-18/basic/index#general-fields] for
   ;; notes on _meta usage.
   :_meta? [:map-of :string :any]))

(def ListRootsResult
  "The client's response to a roots/list request from the server. This
   result contains an array of Root objects, each representing a root
   directory or file that the server can operate on."
  (su/ts-extends
   [Result]
   :roots [:vector Root]))

(def RootsListChangedNotification
  "A notification from the client to the server, informing it that the
   list of roots has changed. This notification should be sent whenever
   the client adds, removes, or modifies any root. The server should
   then request an updated list of roots using the ListRootsRequest."
  (su/ts-extends
   [Notification]
   :method [:= method-notifications-roots-list_changed]))


;; ----- Elicitation -----


(def StringSchema
  (su/ts-object
   :type [:= "string"]
   :title? :string
   :description? :string
   :minLength? number?
   :maxLength? number?
   :format? [:enum "email" "uri" "date" "date-time"]))

(def NumberSchema
  (su/ts-object
   :type [:enum "number" "integer"]
   :title? :string
   :description? :string
   :minimum? number?
   :maximum? number?))

(def BooleanSchema
  (su/ts-object
   :type [:= "boolean"]
   :title? :string
   :description? :string
   :default? :boolean))

(def EnumSchema
  (su/ts-object
   :type [:= "string"]
   :title? :string
   :description? :string
   :enum [:vector :string]
   :enumNames? [:vector :string]  ; // Display names for enum values
   ))

(def PrimitiveSchemaDefinition
  "Restricted schema definitions that only allow primitive types
   without nested objects or arrays."
  [:or
   StringSchema
   NumberSchema
   BooleanSchema
   EnumSchema])

(def ElicitRequest
  "A request from the server to elicit additional information from the
   user via the client."
  (su/ts-extends
   [Request]
   :method [:= method-elicitation-create]
   :params {;; The message to present to the user.
            :message :string
            ;; A restricted subset of JSON Schema.
            ;; Only top-level properties are allowed, without nesting.
            :requestedSchema {:type [:= "object"]
                              :properties [:map-of [:or :keyword :string]
                                           PrimitiveSchemaDefinition]
                              :required? [:vector :string]}}))

(def elicit-action-accept "accept")
(def elicit-action-decline "decline")
(def elicit-action-cancel "cancel")

(def ElicitResult
  "The client's response to an elicitation request."
  (su/ts-extends
   [Result]
   ;; The user action in response to the elicitation.
   ;; - "accept": User submitted the form/confirmed the action
   ;; - "decline": User explicitly declined the action
   ;; - "cancel": User dismissed without making an explicit choice
   :action [:enum
            elicit-action-accept
            elicit-action-decline
            elicit-action-cancel]
   ;; The submitted form data, only present when action is "accept".
   ;; Contains values matching the requested schema.
   :content? [:map-of [:or :keyword :string]
              [:or :string number? :boolean]]))


;; ----- Client messages -----


(def ClientRequest
  [:or
   PingRequest
   InitializeRequest
   CompleteRequest
   SetLevelRequest
   GetPromptRequest
   ListPromptsRequest
   ListResourcesRequest
   ListResourceTemplatesRequest
   ReadResourceRequest
   SubscribeRequest
   UnsubscribeRequest
   CallToolRequest
   ListToolsRequest])

(def ClientNotification
  [:or
   CancelledNotification
   ProgressNotification
   InitializedNotification
   RootsListChangedNotification])

(def ClientResult
  [:or
   EmptyResult
   CreateMessageResult
   ListRootsResult
   ElicitResult])


;; ----- Server messages -----


(def ServerRequest
  [:or
   PingRequest
   CreateMessageRequest
   ListRootsRequest
   ElicitRequest])

(def ServerNotification
  [:or
   CancelledNotification
   ProgressNotification
   LoggingMessageNotification
   ResourceUpdatedNotification
   ResourceListChangedNotification
   ToolListChangedNotification
   PromptListChangedNotification])

(def ServerResult
  [:or
   EmptyResult
   InitializeResult
   CompleteResult
   GetPromptResult
   ListPromptsResult
   ListResourceTemplatesResult
   ListResourcesResult
   ReadResourceResult
   CallToolResult
   ListToolsResult])


;; --- Method-names and Schema ---


(def mcp-methods
  "Map of MCP method-name to its schema, as listed below:
   [request-schema response-schema] - request and response schema available
   [request-schema]                 - when only request schema is available
   []                               - no schema is available"
  {;; both request/response schema available
   method-initialize [InitializeRequest
                      InitializeResult]
   method-prompts-list [ListPromptsRequest
                        ListPromptsResult]
   method-prompts-get [GetPromptRequest
                       GetPromptResult]
   method-resources-list [ListResourcesRequest
                          ListResourcesResult]
   method-resources-read [ReadResourceRequest
                          ReadResourceResult]
   method-tools-list [ListToolsRequest
                      ListToolsResult]
   method-resources-templates-list [ListResourceTemplatesRequest
                                    ListResourceTemplatesResult]
   method-tools-call [CallToolRequest
                      CallToolResult]
   method-sampling-createMessage [CreateMessageRequest
                                  CreateMessageResult]
   method-completion-complete [CompleteRequest
                               CompleteResult]
   method-roots-list [ListRootsRequest
                      ListRootsResult]
   method-elicitation-create [ElicitRequest
                              ElicitResult]
   ;; without response schema
   method-ping [PingRequest]
   method-resources-subscribe [SubscribeRequest]
   method-resources-unsubscribe [UnsubscribeRequest]
   method-logging-setLevel [SetLevelRequest]
   ;; notifications - without response schema
   method-notifications-cancelled [CancelledNotification]
   method-notifications-initialized [InitializedNotification]
   method-notifications-resources-list_changed [ResourceListChangedNotification]
   method-notifications-resources-updated [ResourceUpdatedNotification]
   method-notifications-message [LoggingMessageNotification]
   method-notifications-prompts-list_changed [PromptListChangedNotification]
   method-notifications-progress [ProgressNotification]
   method-notifications-roots-list_changed [RootsListChangedNotification]
   method-notifications-tools-list_changed [ToolListChangedNotification]
   ;; without any schema whatsoever
   method-logging-list []})
