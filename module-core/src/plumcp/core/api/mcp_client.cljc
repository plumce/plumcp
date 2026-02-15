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


;; --- initilization / de-initialization / handshake ---


(defn async-initialize!
  "Send initialize request to the MCP server, and setup a session on
   success result. Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response
   See: `initialize-and-notify!`"
  ([client
    ^{:see [sd/InitializeResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-initialize-request
                  sd/protocol-version-max
                  (-> (cs/?capabilities client)
                      cap/get-client-capability-declaration)
                  (rt/?client-info client))
         setter (partial cs/set-session-context! client)]
     (as-> on-success-result $
       (cs/on-result-callback $ on-error-response)
       (cs/wrap-session-setting $ setter)
       (cs/send-request-to-server client request $))))
  ([client
    ^{:see [sd/InitializeResult]} on-initialize-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error initializing connection")
                    error-response))
        (async-initialize! client
                           on-initialize-result))))


(defn ^{:see [sd/InitializeResult
              sd/JSONRPCError]} initialize!
  "Synchronous version of `async-initialize!` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-initialize! client
                       return reject)))


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
                     (fn [result]
                       (-> (cs/?client-cache client)
                           (cs/?cc-initialize-result result))
                       (notify-initialized client))))


(defn ^{:see [sd/InitializeResult
              sd/JSONRPCError]} initialize-and-notify!
  "Synchronous version of `async-initialize-and-notify!` that returns
   result (value in CLJ, js/Promise in CLJS) on success, or throws on
   error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/let-await [init-result (initialize! client options)]
    (-> (cs/?client-cache client)
        (cs/?cc-initialize-result init-result))
    (notify-initialized client)
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


(defn async-list-tools
  "Return the list of MCP tools supported by the server. Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client
    ^{:see [sd/ListToolsResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-list-tools-request)]
     (as-> on-success-result $
       (cs/destructure-result $ sd/result-key-tools)
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client
    ^{:see [sd/ListToolsResult]} on-list-tools-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error listing tools")
                    error-response))
        (async-list-tools client on-list-tools-result))))


(defn ^{:see [sd/ListToolsResult
              sd/JSONRPCError]} list-tools
  "Synchronous version of `async-list-tools` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-list-tools client
                      return reject)))


(defn async-call-tool
  "Call the MCP tool on the server. Arguments:
  `tool-name`  is the name of the tool to be called
   `tool-args` is the map of args for calling the tool
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client tool-name tool-args
    ^{:see [sd/CallToolResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-call-tool-request tool-name
                                            tool-args)]
     (as-> on-success-result $
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client tool-name tool-args
    ^{:see [sd/CallToolResult]} on-call-tool-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error calling tool")
                    error-response))
        (async-call-tool client
                         tool-name tool-args
                         on-call-tool-result))))


(defn ^{:see [sd/CallToolResult
              sd/JSONRPCError]} call-tool
  "Synchronous version of `async-call-tool` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client tool-name tool-args & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-call-tool client
                     tool-name tool-args
                     return reject)))


(defn async-list-resources
  "Return the list of MCP resources supported by the server. Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client
    ^{:see [sd/ListResourcesResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-list-resources-request)]
     (as-> on-success-result $
       (cs/destructure-result $ sd/result-key-resources)
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client
    ^{:see [sd/ListResourcesResult]} on-list-resources-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error listing resources")
                    error-response))
        (async-list-resources client
                              on-list-resources-result))))


(defn ^{:see [sd/ListResourcesResult
              sd/JSONRPCError]} list-resources
  "Synchronous version of `async-list-resources` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-list-resources client
                          return reject)))


(defn async-list-resource-templates
  "Return the list of MCP resource templates supported by the server.
   Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client
    ^{:see [sd/ListResourceTemplatesResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-list-resource-templates-request)]
     (as-> on-success-result $
       (cs/destructure-result $ sd/result-key-resource-templates)
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client
    ^{:see [sd/ListResourceTemplatesResult]} on-list-resource-templates-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error listing resource templates")
                    error-response))
        (async-list-resource-templates client
                                       on-list-resource-templates-result))))


(defn ^{:see [sd/ListResourceTemplatesResult
              sd/JSONRPCError]} list-resource-templates
  "Synchronous version of `async-list-resource-templates` that returns
   result (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-list-resource-templates client
                                   return reject)))


(defn async-read-resource
  "Read the resource identified by the URI on the server. Arguments:
   `resource-uri` is the resource URI
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client resource-uri
    ^{:see [sd/ReadResourceResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-read-resource-request resource-uri)]
     (as-> on-success-result $
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client resource-uri
    ^{:see [sd/ReadResourceResult]} on-read-resource-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error reading resource")
                    error-response))
        (async-read-resource client
                             resource-uri
                             on-read-resource-result))))


(defn ^{:see [sd/ReadResourceResult
              sd/JSONRPCError]} read-resource
  "Synchronous version of `async-read-resource` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client resource-uri & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-read-resource client
                         resource-uri
                         return reject)))


(defn async-list-prompts
  "List the MCP prompts supported by the server. Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client
    ^{:see [sd/ListPromptsResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-list-prompts-request)]
     (as-> on-success-result $
       (cs/destructure-result $ sd/result-key-prompts)
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client
    ^{:see [sd/ListPromptsResult]} on-list-prompts-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error listing prompts")
                    error-response))
        (async-list-prompts client
                            on-list-prompts-result))))


(defn ^{:see [sd/ListPromptsResult
              sd/JSONRPCError]} list-prompts
  "Synchronous version of `async-list-prompts` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-list-prompts client
                        return reject)))


(defn async-get-prompt
  "Get the MCP prompt identified by name on the server. Arguments:
   `prompt-or-template-name` is prompt or template name
   `prompt-args` is the map of prompt/template args
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client prompt-or-template-name prompt-args
    ^{:see [sd/GetPromptResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-get-prompt-request prompt-or-template-name
                                             {:args prompt-args})]
     (as-> on-success-result $
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client prompt-or-template-name prompt-args
    ^{:see [sd/GetPromptResult]} on-get-prompt-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error getting prompt")
                    error-response))
        (async-get-prompt client
                          prompt-or-template-name prompt-args
                          on-get-prompt-result))))


(defn ^{:see [sd/GetPromptResult
              sd/JSONRPCError]} get-prompt
  "Synchronous version of `async-get-prompt` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client prompt-or-template-name prompt-args & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-get-prompt client
                      prompt-or-template-name prompt-args
                      return reject)))


(defn async-complete
  "Send a completion request to the server. Arguments:
   `complete-request` is the completion request
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client ^{:see [eg/make-complete-request]} complete-request
    ^{:see [sd/CompleteResult]} on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (as-> on-success-result $
     (cs/on-result-callback $ on-error-response)
     (cs/send-request-to-server client complete-request $)))
  ([client ^{:see [eg/make-complete-request]} complete-request
    ^{:see [sd/CompleteResult]} on-complete-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error completing argument")
                    error-response))
        (async-complete client
                        complete-request
                        on-complete-result))))


(defn ^{:see [sd/CompleteResult
              sd/JSONRPCError]} complete
  "Synchronous version of `async-complete` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client ^{:see [eg/make-complete-request]} complete-request
   & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-complete client
                    complete-request
                    return reject)))


(defn async-ping
  "Send a ping request to the server. Arguments:
   `on-success-result` is called with success result
   `on-error-response` is called with error response"
  ([client on-success-result
    ^{:see [sd/JSONRPCError]} on-error-response]
   (let [request (eg/make-ping-request)]
     (as-> on-success-result $
       (cs/on-result-callback $ on-error-response)
       (cs/send-request-to-server client request $))))
  ([client on-ping-result]
   (->> (fn [error-response]
          (u/throw! (get-in error-response [:error :message]
                            "Error pinging MCP server")
                    error-response))
        (async-ping client
                    on-ping-result))))


(defn ^{:see [sd/JSONRPCError]} ping
  "Synchronous version of `async-ping` that returns result
   (value in CLJ, js/Promise in CLJS) on success, or throws on error.
   Options: see plumcp.core.util.async-bridge/as-async"
  [client & {:as options}]
  (uab/as-async
    [return reject]
    options
    (async-ping client
                return reject)))


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
