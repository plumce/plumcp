;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.mcp-client
  "MCP Client implementation.
   Ref: https://github.com/cyanheads/model-context-protocol-resources/blob/main/guides/mcp-client-development-guide.md"
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.support.banner-print :as bp]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.key-lookup :as kl])
  #?(:cljs (:require-macros [plumcp.core.api.mcp-client])))


(declare make-client)


(defn ^{:see [make-client]} make-mcp-client
  "Make MCP client based on the given options, returning a client
   instance. See `make-client` for detailed docs."
  [client-options]
  (let [{:keys [runtime
                jsonrpc-handler
                client-transport
                client-context
                run-list-notifier?
                list-notifier-options
                print-banner?]
         :or {run-list-notifier? true
              list-notifier-options {}
              print-banner? true}
         :as options} (cs/make-client-options client-options)]
    (u/expected! runtime map? ":runtime to be a map")
    (u/expected! jsonrpc-handler fn? ":jsonrpc-handler to be a function")
    (u/expected! client-transport #(satisfies? p/IClientTransport %)
                 ":client-transport to be a valid client transport")
    (let [client-context (-> (or client-context
                                 (-> options
                                     cs/make-base-client-context))
                             (cs/?capabilities (rt/?client-capabilities
                                                runtime))
                             (cs/wrap-transport client-transport)
                             (rt/upsert-runtime runtime))
          run-list-notifier (when run-list-notifier?
                              (fn []
                                (cap/run-list-changed-notifier
                                 (-> runtime
                                     (kl/?get rt/?client-capabilities)
                                     cap/get-client-listed-capabilities)
                                 (fn [notification]
                                   (cs/send-notification-to-server
                                    client-context
                                    notification))
                                 list-notifier-options)))
          client-context (-> client-context
                             (cs/?run-list-notifier run-list-notifier))
          client-cache-atom (cs/?client-cache client-context)
          client-info (rt/?client-info client-context)]
      (when run-list-notifier
        (cs/?cc-list-notifier client-cache-atom nil))
      ;; patch the client-context-atom
      (cs/?cc-client-context client-cache-atom client-context)
      ;; start the transport
      (p/start-client-transport client-transport
                                (cs/?on-message client-context))
      (when print-banner?
        (as-> {:role :client
               :transport-info (-> client-transport
                                   p/client-transport-info)} $
          (merge $ options)
          (bp/print-banner client-info $)))
      client-context)))


(defmacro ^{:see [make-mcp-client]} make-client
  "Make MCP client (context map) using given (or deduced) options.
   | Option keyword       | Default | Description                        |
   |----------------------|---------|------------------------------------|
   |:info                 |Required |see p.c.api.entity-support/make-info|
   |:capabilities         |         |Supplied or made from :primitives   |
   |:primitives           |         |Supplied or made from :vars         |
   |:vars                 |         |Supplied/discovered from hinted vars|
   |:ns (read literally)  |Caller ns|Supplied/discovered from hinted vars|
   |:traffic-logger       |         |No-op by default                    |
   |:runtime              |         |Made from :info,:capabilities,:tra..|
   |:override             | {}      |Merged into final runtime           |
   |:mcp-methods-wrapper  |         |No-op by default                    |
   |:jsonrpc-handler      |         |Impl+made with :schema-check-wrapper|
   |:client-transport     |Required |Protocol p/IClientTransport instance|
   |:client-context       |         |Base client context                 |
   |:print-banner?        |  True   |Print a library banner if true      |
   |:run-list-notifier?   |  True   |Run list-changed notifier if true   |
   |:list-notifier-options|  {}     |Option map for list-changed notifier|

   Dependency map (left/key depends upon the right/vals):
   {:ring-handler    [:runtime :jsonrpc-handler]
    :stdio-handler   [:runtime :jsonrpc-handler]
    :runtime         [:info :capabilities :traffic-logger]
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


;; --- Client utility ---


(defn on-result->on-response
  "Make a JSON-RPC response handler from given JSON-RPC result handler
   (and JSON-RPC error handler)."
  ([on-result on-error-response]
   (fn jsonrpc-result-callback [jsonrpc-message]
     (if (jr/jsonrpc-result? jsonrpc-message)
       (on-result (:result jsonrpc-message))
       (on-error-response jsonrpc-message))))
  ([on-result]
   (on-result->on-response on-result cs/error-logger)))


;; --- initilization / de-initialization / handshake ---


(defn async-initialize!
  "Send initialize request to the MCP server, and setup a session on
   success result. Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response
   See: `initialize-and-notify!`"
  [client ^{:see [sd/JSONRPCResponse
                  sd/InitializeResult
                  sd/JSONRPCError
                  on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-initialize-request
                 sd/protocol-version-max
                 (-> (cs/?capabilities client)
                     cap/get-client-capability-declaration)
                 (rt/?client-info client))
        setter (partial cs/set-session-context! client)]
    (as-> on-jsonrpc-response $
      (cs/wrap-session-setting $ setter)
      (cs/send-request-to-server client request $))))


(defn ^{:see [sd/JSONRPCResponse
              sd/InitializeResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} initialize!
  "Synchronous version of `async-initialize!` that returns initialize
   result (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-initialize! client
                           return))
      (cs/on-jsonrpc-response "initialize"
                              options)))


(defn notify-initialized
  "Notify the MCP server of a successful initialization.
   See: `initialize-and-notify!`"
  [client]
  (let [notification (eg/make-initialized-notification)]
    (cs/send-notification-to-server client notification)
    (p/upon-handshake-success (cs/?transport client)
                              (cs/get-session-context client))
    ;; run list-change notifier for this connection
    (when-let [run-list-notifier (cs/?run-list-notifier client)]
      (let [client-cache-atom (cs/?client-cache client)
            list-notifier (run-list-notifier)]
        (cs/?cc-list-notifier client-cache-atom list-notifier)))))


(defn async-initialize-and-notify!
  "Send initialize request to the MCP server and on success, setup a
   session and notify the MCP server of a successful initialization."
  [client]
  (async-initialize! client
                     (-> (fn [result]
                           (-> (cs/?client-cache client)
                               (cs/?cc-initialize-result result))
                           (notify-initialized client))
                         on-result->on-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/InitializeResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} initialize-and-notify!
  "Synchronous version of `async-initialize-and-notify!` that returns
   initialize result (value in CLJ, js/Promise in CLJS) on success, nil
   on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (uab/let-await [init-result (initialize! client options)]
    (when init-result
      (-> (cs/?client-cache client)
          (cs/?cc-initialize-result init-result))
      (notify-initialized client))
    init-result))


(defn ^{:see [sd/InitializeResult]} get-initialize-result
  "Return the initialization result from the server."
  [client]
  (-> (cs/?client-cache client)
      cs/?cc-initialize-result))


(defn disconnect!
  "Disconnect and destroy the session."
  [client]
  (try
    (when-let [list-notifier (-> (cs/?client-cache client)
                                 cs/?cc-list-notifier)]
      (p/stop! list-notifier))
    (finally
      (p/stop-client-transport! (cs/?transport client) false))))


;; --- MCP requests expecting result ---


(defn on-tools->on-result
  "Given a (fn [tools]) return (fn [jsonrpc-result])."
  [f]
  (cs/destructure-result f sd/result-key-tools))


(defn async-list-tools
  "Return the list of MCP tools supported by the server. The JSON-RPC
   response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListToolsResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-tools->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-tools-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListToolsResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} list-tools
  "Synchronous version of `async-list-tools` that returns tools (value
   in CLJ, js/Promise in CLJS) on success, nil on error (printed to
   STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-list-tools client
                          return))
      (cs/on-jsonrpc-response "list-tools"
                              (-> {:on-result sd/result-key-tools}
                                  (merge options)))))


(defn async-call-tool
  "Call the MCP tool on the server. Arguments:
   `tool-name`  is the name of the tool to be called
   `tool-args` is the map of args for calling the tool
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client tool-name tool-args
   ^{:see [sd/JSONRPCResponse
           sd/CallToolResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-call-tool-request tool-name
                                           tool-args)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/CallToolResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} call-tool
  "Synchronous version of `async-call-tool` that returns call-tool
   result (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client tool-name tool-args
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-call-tool client
                         tool-name tool-args
                         return))
      (cs/on-jsonrpc-response "call-tool"
                              options)))


(defn on-resources->on-result
  "Given a (fn [resources]) return (fn [jsonrpc-result])."
  [f]
  (cs/destructure-result f sd/result-key-resources))


(defn async-list-resources
  "Return the list of MCP resources supported by the server. The
   JSON-RPC response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListResourcesResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-resources->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-resources-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourcesResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} list-resources
  "Synchronous version of `async-list-resources` that returns resources
   (value in CLJ, js/Promise in CLJS) on success, nil on error (printed
   to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-list-resources client
                              return))
      (cs/on-jsonrpc-response "list-resources"
                              (-> {:on-result sd/result-key-resources}
                                  (merge options)))))


(defn on-resource-templates->on-result
  "Given a (fn [resource-templates]) return (fn [jsonrpc-result])."
  [f]
  (cs/destructure-result f sd/result-key-resource-templates))


(defn async-list-resource-templates
  "Return the list of MCP resource templates supported by the server.
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListResourceTemplatesResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-resource-templates->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-resource-templates-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourceTemplatesResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} list-resource-templates
  "Synchronous version of `async-list-resource-templates` that returns
   resource templates (value in CLJ, js/Promise in CLJS) on success, nil
   on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-list-resource-templates client
                                       return))
      (cs/on-jsonrpc-response "list-resource-templates"
                              (-> {:on-result sd/result-key-resource-templates}
                                  (merge options)))))


(defn async-read-resource
  "Read the resource identified by the URI on the server. Arguments:
   `resource-uri` is the resource URI
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client resource-uri
   ^{:see [sd/JSONRPCResponse
           sd/ReadResourceResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-read-resource-request resource-uri)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ReadResourceResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} read-resource
  "Synchronous version of `async-read-resource` that returns
   read-resource result (value in CLJ, js/Promise in CLJS) on success,
   nil on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client resource-uri
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-read-resource client
                             resource-uri
                             return))
      (cs/on-jsonrpc-response "read-resource"
                              options)))


(defn on-prompts->on-result
  "Given a (fn [prompts]) return (fn [jsonrpc-result])."
  [f]
  (cs/destructure-result f sd/result-key-prompts))


(defn async-list-prompts
  "List the MCP prompts supported by the server. The JSON-RPC response
   is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListPromptsResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-prompts->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-prompts-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListPromptsResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} list-prompts
  "Synchronous version of `async-list-prompts` that returns prompts
   (value in CLJ, js/Promise in CLJS) on success, nil on error (printed
   to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-list-prompts client
                            return))
      (cs/on-jsonrpc-response "list-prompts"
                              (-> {:on-result sd/result-key-prompts}
                                  (merge options)))))


(defn async-get-prompt
  "Get the MCP prompt identified by name on the server. Arguments:
   `prompt-or-template-name` is prompt or template name
   `prompt-args` is the map of prompt/template args
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client prompt-or-template-name prompt-args
   ^{:see [sd/JSONRPCResponse
           sd/GetPromptResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-get-prompt-request prompt-or-template-name
                                            {:args prompt-args})]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/GetPromptResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} get-prompt
  "Synchronous version of `async-get-prompt` that returns get-prompt
   result (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client prompt-or-template-name prompt-args
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-get-prompt client
                          prompt-or-template-name prompt-args
                          return))
      (cs/on-jsonrpc-response "get-prompt"
                              options)))


(defn async-complete
  "Send a completion request to the server. Arguments:
   `complete-request` is the completion request
   `on-success-result` is called with success result
   `on-error-response` is called with error response
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client ^{:see [eg/make-complete-request]} complete-request
   ^{:see [sd/JSONRPCResponse
           sd/CompleteResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (cs/send-request-to-server client complete-request
                             on-jsonrpc-response))


(defn ^{:see [sd/JSONRPCResponse
              sd/CompleteResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} complete
  "Synchronous version of `async-complete` that returns completion
   result (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client ^{:see [eg/make-complete-request]} complete-request
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-complete client
                        complete-request
                        return))
      (cs/on-jsonrpc-response "complete"
                              options)))


(defn async-ping
  "Send a ping request to the server. The JSON-RPC response is passed to
   `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/JSONRPCError
                  on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-ping-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


(defn ^{:see [sd/JSONRPCResponse
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-throw!]} ping
  "Synchronous version of `async-ping` that returns ping result
   (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-ping client
                    return))
      (cs/on-jsonrpc-response "ping"
                              options)))


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
  (if-let [roots (-> (cs/?capabilities client)
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
  (if-let [sampling (-> (cs/?capabilities client)
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
  (if-let [elicitation (-> (cs/?capabilities client)
                           (cap/get-capability-elicitation))]
    (let [response (->> (eg/make-elicit-result action elicit-options)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Elicitation capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))
