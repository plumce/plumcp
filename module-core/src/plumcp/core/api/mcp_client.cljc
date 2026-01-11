(ns plumcp.core.api.mcp-client
  "MCP Client implementation.
   Ref: https://github.com/cyanheads/model-context-protocol-resources/blob/main/guides/mcp-client-development-guide.md"
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocols :as p]
   [plumcp.core.constant :as const]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.support.banner-print :as bp]
   [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.api.mcp-client])))


(defn make-mcp-client
  [client-options]
  (let [{:keys [runtime
                jsonrpc-handler
                client-transport
                client-context
                print-banner?]
         :or {print-banner? true}
         :as options} (cs/make-client-options client-options)]
    (u/expected! runtime map? ":runtime to be a map")
    (u/expected! jsonrpc-handler fn? ":jsonrpc-handler to be a function")
    (u/expected! client-transport #(satisfies? p/IClientTransport %)
                 ":client-transport to be a valid client transport")
    (let [client-options (merge {:role :client
                                 :transport (p/get-client-transport-id
                                             client-transport)}
                                options)
          loaded-context (-> (or client-context
                                 (-> {:jsonrpc-handler jsonrpc-handler}
                                     (merge client-options)
                                     cs/make-base-client-context))
                             (assoc :capabilities (rt/?client-capabilities
                                                   runtime))
                             (cs/wrap-transport client-transport)
                             (rt/upsert-runtime runtime))
          client-context (dissoc loaded-context :client-context-atom)]
      ;; patch the client-context-atom
      (reset! (:client-context-atom loaded-context) client-context)
      ;; start the transport
      (p/start-client-transport client-transport
                                (:on-message client-context))
      (when print-banner?
        (bp/print-banner client-options))
      client-context)))


(defmacro make-client
  "Make MCP client (context map) using given (or deduced) options.
   | Option keyword     | Default | Description                        |
   |--------------------|---------|------------------------------------|
   |:capabilities       |         |Supplied or made from :primitives   |
   |:primitives         |         |Supplied or made from :vars         |
   |:vars               |         |Supplied/discovered from hinted vars|
   |:ns (read literally)|Caller ns|Supplied/discovered from hinted vars|
   |:traffic-logger     |         |No-op by default                    |
   |:runtime            |         |Made from :capabilities             |
   |:mcp-methods-wrapper|         |No-op by default                    |
   |:jsonrpc-handler    |         |Impl+made with :schema-check-wrapper|
   |:client-transport   |Required |Protocol p/IClientTransport instance|
   |:client-context     |         |Base client context                 |
   |:print-banner?      |  True   |Print a library banner if true      |

   Dependency map (left/key depends upon the right/vals):
   {:ring-handler    [:runtime :jsonrpc-handler]
    :stdio-handler   [:runtime :jsonrpc-handler]
    :runtime         [:capabilities :traffic-logger]
    :jsonrpc-handler [:schema-check-wrapper :jsonrpc-response-handler]}"
  ([options]
   `(let [default-vars# (or (:vars ~options)
                            ~(if-let [nses (:ns options)]
                               `(concat ~@(->> (u/as-vec nses)
                                               (mapv #(do `(u/find-vars ~%)))))
                               `(u/find-vars)))]
      (make-mcp-client (merge {:vars default-vars#}
                              ~options))))
  ([]
   `(make-client {})))


(def client-implementation
  (eg/make-implementation "PluMCP Client"
                          const/version
                          {:title (str "PluMCP Client " const/version)}))


;; --- initilization / de-initialization / handshake ---


(defn initialize!
  "Send initialize request to the MCP server, and setup a session on
   success result.
   See: `initialize-and-notify!`"
  [client
   ^{:see [sd/InitializeResult]} on-initialize-result]
  (let [request (eg/make-initialize-request
                 sd/protocol-version-max
                 (-> (:capabilities client)
                     cap/get-client-capability-declaration)
                 client-implementation)
        setter (partial cs/set-session-context! client)]
    (cs/send-request-to-server client
                               request
                               (-> on-initialize-result
                                   cs/on-result-callback
                                   (cs/wrap-session-setting setter)))))


(defn notify-initialized
  "Notify the MCP server of a successful initialization.
   See: `initialize-and-notify!`"
  [client]
  (let [notification (eg/make-initialized-notification)]
    (cs/send-message-to-server client notification)
    (p/upon-handshake-success (:transport client)
                              (cs/get-session-context client))))


(defn initialize-and-notify!
  "Send initialize request to the MCP server and on success, setup a
   session and notify the MCP server of a successful initialization."
  [client]
  (initialize! client (fn [result]
                        (notify-initialized client))))


(defn disconnect!
  "Disconnect and destroy the session."
  [client]
  (p/stop-client-transport! (:transport client) false))


;; --- MCP requests expecting result ---


(defn list-tools
  "Return the list of MCP tools supported by the server. "
  [client
   ^{:see [sd/ListToolsResult]} on-list-tools-result]
  (let [request (eg/make-list-tools-request)]
    (as-> on-list-tools-result $
      (cs/destructure-result $ sd/result-key-tools)
      (cs/on-result-callback $)
      (cs/send-request-to-server client request $))))


(defn call-tool
  "Call the MCP tool on the server."
  ([client tool-name tool-args
    ^{:see [sd/CallToolResult]} on-call-tool-result
    ^{:see [sd/JSONRPCError]} on-call-tool-error-response]
   (let [request (eg/make-call-tool-request tool-name
                                            tool-args)]
     (as-> on-call-tool-result $
       (cs/on-result-callback $ on-call-tool-error-response)
       (cs/send-request-to-server client request $))))
  ([client tool-name tool-args
    ^{:see [sd/CallToolResult]} on-call-tool-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error calling tool")
                    error-response))
        (call-tool client tool-name tool-args on-call-tool-result))))


(defn list-resources
  "Return the list of MCP resources supported by the server."
  [client
   ^{:see [sd/ListResourcesResult]} on-list-resources-result]
  (let [request (eg/make-list-resources-request)]
    (as-> on-list-resources-result $
      (cs/destructure-result $ sd/result-key-resources)
      (cs/on-result-callback $)
      (cs/send-request-to-server client request $))))


(defn list-resource-templates
  "Return the list of MCP resource templates supported by the server."
  [client
   ^{:see [sd/ListResourceTemplatesResult]} on-list-resource-templates-result]
  (let [request (eg/make-list-resource-templates-request)]
    (as-> on-list-resource-templates-result $
      (cs/destructure-result $ sd/result-key-resource-templates)
      (cs/on-result-callback $)
      (cs/send-request-to-server client request $))))


(defn read-resource
  "Read the resourced identified by the URI on the server."
  ([client resource-uri
    ^{:see [sd/ReadResourceResult]} on-read-resource-result
    ^{:see [sd/JSONRPCError]} on-read-resource-error-response]
   (let [request (eg/make-read-resource-request resource-uri)]
     (as-> on-read-resource-result $
       (cs/on-result-callback $ on-read-resource-error-response)
       (cs/send-request-to-server client request $))))
  ([client resource-uri
    ^{:see [sd/ReadResourceResult]} on-read-resource-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error reading resource")
                    error-response))
        (read-resource client resource-uri on-read-resource-result))))


(defn list-prompts
  "List the MCP prompts supported by the server."
  [client
   ^{:see [sd/ListPromptsResult]} on-list-prompts-result]
  (let [request (eg/make-list-prompts-request)]
    (as-> on-list-prompts-result $
      (cs/destructure-result $ sd/result-key-prompts)
      (cs/on-result-callback $)
      (cs/send-request-to-server client request $))))


(defn get-prompt
  "Get the MCP prompt identified by name on the server."
  ([client prompt-or-template-name prompt-args
    ^{:see [sd/GetPromptResult]} on-get-prompt-result
    ^{:see [sd/JSONRPCError]} on-get-prompt-error-response]
   (let [request (eg/make-get-prompt-request prompt-or-template-name
                                             {:args prompt-args})]
     (as-> on-get-prompt-result $
       (cs/on-result-callback $ on-get-prompt-error-response)
       (cs/send-request-to-server client request $))))
  ([client prompt-or-template-name prompt-args
    ^{:see [sd/GetPromptResult]} on-get-prompt-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error getting prompt")
                    error-response))
        (get-prompt client prompt-or-template-name prompt-args
                    on-get-prompt-result))))


(defn complete
  "Send a completion request to the server."
  ([client ^{:see [eg/make-complete-request]} the-ref arg-name arg-value
    ^{:see [sd/CompleteResult]} on-complete-result
    ^{:see [sd/JSONRPCError]} on-complete-error-response]
   (let [request (eg/make-complete-request the-ref arg-name arg-value)]
     (as-> on-complete-result $
       (cs/on-result-callback $ on-complete-error-response)
       (cs/send-request-to-server client request $))))
  ([client ^{:see [eg/make-complete-request]} the-ref arg-name arg-value
    ^{:see [sd/CompleteResult]} on-complete-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error completing argument")
                    error-response))
        (complete client the-ref arg-name arg-value
                  on-complete-result))))


(defn ping
  "Send a ping request to the server"
  [client on-ping-result]
  (let [request (eg/make-ping-request)]
    (->> on-ping-result
         cs/on-result-callback
         (cs/send-request-to-server client request))))


;; --- MCP requests NOT expecting result ---


(defn set-log-level
  "Send set-log-lvel request to the server."
  [client ^{:see [sd/log-level-0-emergency
                  sd/log-level-1-alert
                  sd/log-level-2-critical
                  sd/log-level-3-error
                  sd/log-level-4-warning
                  sd/log-level-5-notice
                  sd/log-level-6-info
                  sd/log-level-7-debug]} log-level]
  (let [request (eg/make-set-level-request log-level)]
    (cs/send-message-to-server client request)))


(defn resource-subscribe
  "Subscribe to the resource identified by the given resource-uri."
  [client resource-uri]
  (let [request (eg/make-subscribe-request resource-uri)]
    (cs/send-message-to-server client request)))


(defn resource-unsubscribe
  "Unsubscribe to the resource identified by the given resource-uri."
  [client resource-uri]
  (let [request (eg/make-unsubscribe-request resource-uri)]
    (cs/send-message-to-server client request)))


;; --- MCP notifications sent by the client ---


(defn notify-cancelled
  "Send a cancelled notification for given request-id to the server."
  [client request-id]
  (let [notification (eg/make-cancellation-notification request-id)]
    (cs/send-message-to-server client notification)))


(defn notify-progress
  "Send a progress notification for the given token to the server."
  [client progress-token progress & progress-opts]
  (let [notification (eg/make-progress-notification progress-token
                                                    progress
                                                    progress-opts)]
    (cs/send-message-to-server client notification)))


(defn notify-roots-list-changed
  "Send a roots-list-changed notification to the server."
  [client]
  (let [notification (eg/make-roots-list-changed-notification)]
    (cs/send-message-to-server client notification)))


;; --- MCP responses (sent by the client) for server-requests ---


(defn respond-roots-list
  "Send response for roots-list server request."
  [client request-id]
  (if-let [roots (-> (:capabilities client)
                     (cap/get-capability-roots))]
    (let [response (->> (eg/make-list-roots-result roots)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Root capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))


(defn respond-sampling-create-message
  "Send response for sampling-createMessage server request."
  [client request-id role content]
  (if-let [sampling (-> (:capabilities client)
                        (cap/get-capability-sampling))]
    (let [response (->> (eg/make-sampling-message role content)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Sampling capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))


(defn respond-create-elicitation
  "Send response for sampling-createMessage server request."
  [client request-id action elicit-options]
  (if-let [elicitation (-> (:capabilities client)
                           (cap/get-capability-elicitation))]
    (let [response (->> (eg/make-elicit-result action elicit-options)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Elicitation capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))
