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
   [plumcp.core.client.client-method :as cm]
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
                cache-primitives?
                print-banner?]
         :or {run-list-notifier? true
              list-notifier-options {}
              cache-primitives? true
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
                             (cs/?cache-primitives? (boolean
                                                     cache-primitives?))
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
   |:notification-handlers|  {}     |Map notifica'n methodName->handlerFn|
   |:cache-primitives?    |  True   |Cache prompts/resource../tools list?|
   |:runtime              |         |Made from :info,:capabilities,:tra..|
   |:override             |  {}     |Merged into final runtime           |
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
    :jsonrpc-handler [:schema-check-wrapper :jsonrpc-response-handler]}

   Notification handlers example:
   {on-tools-list-changed #(fetch-tools % {:on-tools (fn [tools]
                                                       ...)})}"
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


;; --- MCP Initilization / de-initialization / handshake ---


(defn ^{:see [sd/JSONRPCResponse
              sd/InitializeResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} initialize-and-notify!
  "Send initialize request to the MCP server and on success, setup a
   session and notify the MCP server of a successful initialization
   after caching the initialize result.
   Return initialize result (value in CLJ, js/Promise in CLJS) on
   success, nil on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    cm/on-jsonrpc-response]} {:as options}]
  (uab/let-await [init-result (cm/caching-initialize! client options)]
    (when init-result
      (-> (cs/?client-cache client)
          (cs/?cc-initialize-result init-result))
      (cm/notify-initialized client))
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


(defn ^{:see [sd/JSONRPCResponse
              sd/ListPromptsResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} list-prompts
  "Return (from cache if available, else fetch from server) the list of
   MCP prompts (value in CLJ, js/Promise in CLJS) supported by the
   server on success, nil on error (printed to STDERR). Caching is
   applied only if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-method/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    cm/on-jsonrpc-response]} {:as options}]
  (if-let [prompts (cm/get-from-cache client cs/?cc-prompts-list)]
    (uab/async-val prompts)
    (cm/caching-list-prompts client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/GetPromptResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} get-prompt
  "Get the prompt identified by name from the server, returning prompt
   result (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client prompt-or-template-name prompt-args
   & ^{:see [uab/as-async
             cm/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cm/async-get-prompt client
                             prompt-or-template-name prompt-args
                             return))
      (cm/on-jsonrpc-response "get-prompt"
                              (-> options
                                  (dissoc :on-result)))))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourcesResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} list-resources
  "Return (from cache if available, else fetch from server) the list of
   MCP resources (value in CLJ, js/Promise in CLJS) supported by the
   server on success, nil on error (printed to STDERR). Caching is
   applied only if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-method/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    cm/on-jsonrpc-response]} {:as options}]
  (if-let [resources (cm/get-from-cache client cs/?cc-resources-list)]
    (uab/async-val resources)
    (cm/caching-list-resources client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourceTemplatesResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} list-resource-templates
  "Return (from cache if available, else fetch from server) the list of
   MCP resource templates (value in CLJ, js/Promise in CLJS) supported
   by the server on success, nil on error (printed to STDERR). Caching
   is applied only if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-method/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    cm/on-jsonrpc-response]} {:as options}]
  (if-let [templates (cm/get-from-cache client
                                        cs/?cc-resource-templates-list)]
    (uab/async-val templates)
    (cm/caching-list-resource-templates client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ReadResourceResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} read-resource
  "Read the resource identified by the URI on the server, returning the
   result of reading the resource (value in CLJ, js/Promise in CLJS) on
   success, nil on error (printed to STDERR).
   Arguments:
   `resource-uri` is the resource URI
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client resource-uri
   & ^{:see [uab/as-async
             cm/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cm/async-read-resource client
                                resource-uri
                                return))
      (cm/on-jsonrpc-response "read-resource"
                              (-> options
                                  (dissoc :on-result)))))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListToolsResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} list-tools
  "Return (from cache if available, else fetch from server) the list of
   MCP tools (value in CLJ, js/Promise in CLJS) supported by the server
   on success, nil on error (printed to STDERR). Caching is applied only
   if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-method/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client
   & ^{:see [uab/as-async
             cm/on-jsonrpc-response]} {:as options}]
  (if-let [tools (cm/get-from-cache client cs/?cc-tools-list)]
    (uab/async-val tools)
    (cm/caching-list-tools client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/CallToolResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} call-tool
  "Call the tool identified by tool-name on the server, returning the
   call-tool result (value in CLJ, js/Promise in CLJS) on success, nil
   on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client tool-name tool-args
   & ^{:see [uab/as-async
             cm/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cm/async-call-tool client
                            tool-name tool-args
                            return))
      (cm/on-jsonrpc-response "call-tool"
                              (-> options
                                  (dissoc :on-result)))))


(defn ^{:see [sd/JSONRPCResponse
              sd/CompleteResult
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} complete
  "Get completion suggestion from the server, returning completion
   result (value in CLJ, js/Promise in CLJS) on success, nil on error
   (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client ^{:see [eg/make-complete-request]} complete-request
   & ^{:see [uab/as-async
             cm/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cm/async-complete client
                           complete-request
                           return))
      (cm/on-jsonrpc-response "complete"
                              (-> options
                                  (dissoc :on-result)))))


(defn ^{:see [sd/JSONRPCResponse
              sd/JSONRPCError
              cm/on-jsonrpc-response
              cm/on-jsonrpc-response-throw!]} ping
  "Ping the server, returning the ping result (value in CLJ, js/Promise
   in CLJS) on success, nil on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    cm/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cm/async-ping client
                       return))
      (cm/on-jsonrpc-response "ping"
                              (-> options
                                  (dissoc :on-result)))))


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


;; --- Notification handler helper fns ---


(defn jsonrpc-message-with-deps->client
  "Given a jsonrpc-message with dependencies, extractreturn the client."
  [jsonrpc-message-with-deps]
  (-> (cs/?client-cache jsonrpc-message-with-deps)
      cs/?cc-client-context))


(defn fetch-prompts
  "Given a JSON-RPC message with dependencies fetch and return a list of
   prompts (value in CLJ, js/Promise in CLJS). Useful to fetch prompts
   on list-changed notification."
  [jsonrpc-message-with-deps & {:keys [on-prompts]
                                :or {on-prompts identity}
                                :as options}]
  (uab/let-await [prompts (-> jsonrpc-message-with-deps
                              jsonrpc-message-with-deps->client
                              (cm/caching-list-prompts options))]
    (on-prompts prompts)))


(defn fetch-resources
  "Given a JSON-RPC message with dependencies fetch and return a vector
   of [resources resource-templates] (value in CLJ, js/Promise in CLJS).
   Useful to fetch resources and resource-templates on list-changed
   notification."
  [jsonrpc-message-with-deps & {:keys [on-resources
                                       on-resource-templates]
                                :or {on-resources identity
                                     on-resource-templates identity}
                                :as options}]
  (let [client (-> jsonrpc-message-with-deps
                   jsonrpc-message-with-deps->client)]
    (uab/let-await [resources (cm/caching-list-resources client options)
                    templates (cm/caching-list-resource-templates client
                                                                  options)]
      [(on-resources resources)
       (on-resource-templates templates)])))


(defn fetch-tools
  "Given a JSON-RPC message with dependencies fetch and return a list of
   tools (value in CLJ, js/Promise in CLJS). Useful to fetch tools on
   list-changed notification."
  [jsonrpc-message-with-deps & {:keys [on-tools]
                                :or {on-tools identity}
                                :as options}]
  (uab/let-await [tools (-> jsonrpc-message-with-deps
                            jsonrpc-message-with-deps->client
                            (cm/caching-list-tools options))]
    (on-tools tools)))


;; --- Notification handler keys ---


;; Common for both client and server


(def ^{:see [sd/CancelledNotification]} on-cancelled
  "Key for `cancelled` notification handler fn: (fn [params])"
  sd/method-notifications-cancelled)


(def ^{:see [sd/ProgressNotification]} on-progress
  "Key for `progress` notification handler fn: (fn [params])"
  sd/method-notifications-progress)


;; Client only


(def ^{:see [sd/LoggingMessageNotification]} on-log-message
  "Key for `message` notification handler fn: (fn [params])"
  sd/method-notifications-message)


(def ^{:see [sd/PromptListChangedNotification
             sd/ListPromptsResult
             fetch-prompts]} on-prompts-list-changed
  "Key for `prompts/list_changed` notification handler fn:
   (fn [prompts])"
  sd/method-notifications-prompts-list_changed)


(def ^{:see [sd/ResourceListChangedNotification
             sd/ListResourcesResult
             sd/ListResourceTemplatesResult
             fetch-resources]} on-resources-list-changed
  "Key for `resources/list_changed` notification handler fn:
   (fn [resources resource-templates])"
  sd/method-notifications-resources-list_changed)


(def ^{:see [sd/ResourceUpdatedNotification]} on-resource-updated
  "Key for `resources/updated` notification handler fn: (fn [params])"
  sd/method-notifications-resources-updated)


(def ^{:see [sd/ToolListChangedNotification
             sd/ListToolsResult
             fetch-tools]} on-tools-list-changed
  "Key for `tools/list_changed` notification handler fn: (fn [tools])"
  sd/method-notifications-tools-list_changed)
