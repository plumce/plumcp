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
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.support.banner-print :as bp]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.key-lookup :as kl])
  #?(:cljs (:require-macros [plumcp.core.api.mcp-client])))


;; --- MCP Initilization / de-initialization / handshake ---


(defn ^{:see [sd/JSONRPCResponse
              sd/InitializeResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} initialize-and-notify!
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
                    cs/on-jsonrpc-response]} {:as options}]
  (uab/let-await [init-result (cs/caching-initialize! client options)]
    (when init-result
      (cs/set-initialize-result! client init-result)
      (cs/notify-initialized client))
    init-result))


(defn ^{:see [sd/InitializeResult]} get-initialize-result
  "Return the initialization result from the server."
  [client]
  (-> (cs/?client-cache client)
      cs/?cc-initialize-result))


(defn disconnect!
  "Disconnect and destroy the session."
  [client]
  (cs/set-initialize-result! client nil)  ; erase init result
  (when-let [list-notifier (-> (cs/?client-cache client)
                               cs/?cc-listnotif-worker)]
    (u/background
      (p/stop! list-notifier)))
  (when-let [heartbeat-chk (-> (cs/?client-cache client)
                               cs/?cc-heartbeat-worker)]
    (u/background
      (p/stop! heartbeat-chk)))
  ;; wipe client state to remove self and virtual thread references
  (cs/reset-client-cache-atom! (cs/?client-cache client))
  (p/stop-client-transport! (cs/?transport client) false))


;; --- MCP requests expecting result ---


(defn ^{:see [sd/JSONRPCResponse
              sd/ListPromptsResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} list-prompts
  "Return (from cache if available, else fetch from server) the list of
   MCP prompts (value in CLJ, js/Promise in CLJS) supported by the
   server on success, nil on error (printed to STDERR). Caching is
   applied only if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:keys [on-result]
                                       :or {on-result sd/result-key-prompts}
                                       :as options}]
  (if-let [prompts (-> (cs/get-from-cache client cs/?cc-prompts-list)
                       on-result)]
    (uab/async-val prompts)
    (cs/caching-list-prompts client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/GetPromptResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} get-prompt
  "Get the prompt identified by name from the server, returning prompt
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
        (cs/async-get-prompt client
                             prompt-or-template-name prompt-args
                             return))
      (cs/on-jsonrpc-response "get-prompt" options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourcesResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} list-resources
  "Return (from cache if available, else fetch from server) the list of
   MCP resources (value in CLJ, js/Promise in CLJS) supported by the
   server on success, nil on error (printed to STDERR). Caching is
   applied only if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:keys [on-result]
                                       :or {on-result sd/result-key-resources}
                                       :as options}]
  (if-let [resources (-> (cs/get-from-cache client cs/?cc-resources-list)
                         on-result)]
    (uab/async-val resources)
    (cs/caching-list-resources client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourceTemplatesResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} list-resource-templates
  "Return (from cache if available, else fetch from server) the list of
   MCP resource templates (value in CLJ, js/Promise in CLJS) supported
   by the server on success, nil on error (printed to STDERR). Caching
   is applied only if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:keys [on-result]
                                       :or {on-result
                                            sd/result-key-resource-templates}
                                       :as options}]
  (if-let [templates (-> client
                         (cs/get-from-cache cs/?cc-resource-templates-list)
                         on-result)]
    (uab/async-val templates)
    (cs/caching-list-resource-templates client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ReadResourceResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} read-resource
  "Read the resource identified by the URI on the server, returning the
   result of reading the resource (value in CLJ, js/Promise in CLJS) on
   success, nil on error (printed to STDERR).
   Arguments:
   `resource-uri` is the resource URI
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client resource-uri
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cs/async-read-resource client
                                resource-uri
                                return))
      (cs/on-jsonrpc-response "read-resource" options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListToolsResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} list-tools
  "Return (from cache if available, else fetch from server) the list of
   MCP tools (value in CLJ, js/Promise in CLJS) supported by the server
   on success, nil on error (printed to STDERR). Caching is applied only
   if enabled.
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:keys [on-result]
                                       :or {on-result sd/result-key-tools}
                                       :as options}]
  (if-let [tools (-> (cs/get-from-cache client cs/?cc-tools-list)
                     on-result)]
    (uab/async-val tools)
    (cs/caching-list-tools client options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/CallToolResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} call-tool
  "Call the tool identified by tool-name on the server, returning the
   call-tool result (value in CLJ, js/Promise in CLJS) on success, nil
   on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client tool-name tool-args
   & ^{:see [uab/as-async
             cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cs/async-call-tool client
                            tool-name tool-args
                            return))
      (cs/on-jsonrpc-response "call-tool" options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/CompleteResult
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} complete
  "Get completion suggestion from the server, returning completion
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
        (cs/async-complete client
                           complete-request
                           return))
      (cs/on-jsonrpc-response "complete" options)))


(defn ^{:see [sd/JSONRPCResponse
              sd/JSONRPCError
              cs/on-jsonrpc-response
              cs/on-jsonrpc-response-error-throw!]} ping
  "Ping the server, returning the ping result (value in CLJ, js/Promise
   in CLJS) on success, nil on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response"
  [client & ^{:see [uab/as-async
                    cs/on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (cs/async-ping client
                       return))
      (cs/on-jsonrpc-response "ping" options)))


;; --- Meta functions ---


;; Request cancellation


(defn cancel-sent-request
  "Cancel the request sent to server."
  [client request-id & {:as options}]
  ;; send cancellation notification
  (->> (eg/make-cancellation-notification request-id options)
       (cs/send-notification-to-server client))
  ;; clear from pending
  (-> (cs/get-client-cache-atom client)
      (cs/remove-pending-client-request! request-id))
  nil)


(defn cancel-request-received?
  "Return true if cancellation request is received for given request ID,
   false otherwise."
  [client request-id]
  (-> (cs/get-client-cache-atom client)
      (cs/is-cancelled-request? request-id)))


;; Progress tracking


(defn register-client-request-progress-tokens
  "Register progress tokens against a request ID, so that progress
   notifications from server may update the pending-request status."
  [client request-id progress-tokens]
  (let [client-cache-atom (cs/?client-cache client)]
    ;; register for request-tracking
    (cs/?cc-progress-tracking-dict client-cache-atom
                                   u/dict-assoc
                                   request-id progress-tokens)))


(defn get-client-request-progress
  "Given a client request (to server) ID, return progress if available,
   empty {} otherwise. Return nil if no such request is pending.
   Structure of the returned progress map:
   {<progress-token> {:progress <val> ; number
                      :total <val>    ; number, optional
                      :message <val>  ; string, optional
                     }}"
  [client request-id]
  (let [client-cache-atom (cs/?client-cache client)]
    (some-> (cs/?cc-pending-client-requests client-cache-atom)
            (get request-id)
            (get :progress {}))))


;; --- MCP requests NOT expecting result ---


(defn set-log-level
  "Send set-log-level request to the server to not log anything below
   (i.e. less severe than) the given level."
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
                     (ic/get-capability-roots))]
    (let [response (->> (eg/make-list-roots-result roots)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Root capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))


(defn respond-sampling-create-message
  "Send response for sd/method-sampling-createMessage server request."
  [client request-id role content]
  (if-let [sampling (-> (cs/?capabilities client)
                        (ic/get-capability-sampling))]
    (let [response (->> (eg/make-sampling-message role content)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Sampling capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))


(defn respond-create-elicitation
  "Send response for sd/method-elicitation-create server request."
  [client request-id action elicit-options]
  (if-let [elicitation (-> (cs/?capabilities client)
                           (ic/get-capability-elicitation))]
    (let [response (->> (eg/make-elicit-result action elicit-options)
                        (jr/jsonrpc-success request-id))]
      (cs/send-message-to-server client response))
    (let [response (-> sd/error-code-invalid-request
                       (jr/jsonrpc-failure "Elicitation capability is unsupported")
                       (jr/add-jsonrpc-id request-id))]
      (cs/send-message-to-server client response))))


;; ----- Making Client -----


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
                ^long heartbeat-seconds
                cache-primitives?
                print-banner?]
         :or {run-list-notifier? true
              list-notifier-options {}
              heartbeat-seconds 0  ; disabled by default
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
                              (fn [client]
                                (ic/run-list-changed-notifier
                                 (-> runtime
                                     (kl/?get rt/?client-capabilities)
                                     ic/get-client-listed-capabilities)
                                 (fn [notification]
                                   (cs/send-notification-to-server
                                    client
                                    notification))
                                 (-> list-notifier-options
                                     (assoc :condition-fn
                                            #(-> client
                                                 get-initialize-result
                                                 some?))))))
          run-heartbeat-chk (when (and (int? heartbeat-seconds)
                                       (pos? heartbeat-seconds))
                              (fn [client]
                                (cs/run-heartbeat client
                                                  ping heartbeat-seconds
                                                  #(-> client
                                                       get-initialize-result
                                                       some?))))
          client-context (-> client-context
                             (cs/?run-list-notifier run-list-notifier)
                             (cs/?run-heartbeat-chk run-heartbeat-chk))
          client-cache-atom (cs/?client-cache client-context)
          client-info (rt/?client-info client-context)]
      (when run-list-notifier
        (cs/?cc-listnotif-worker client-cache-atom nil))
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
   |:heartbeat-seconds    | 0 (Off) |Heartbeat frequency: 0=Off, >0=On   |

   Dependency map (left/key depends upon the right/vals):
   {:ring-handler    [:runtime :jsonrpc-handler]
    :stdio-handler   [:runtime :jsonrpc-handler]
    :runtime         [:info :capabilities :traffic-logger]
    :jsonrpc-handler [:schema-check-wrapper :jsonrpc-response-handler]}

   Notification handlers example:
   {p.c.schema.schema-defs/method-notifications-tools-list_changed
    #(p.c.client.client-support/fetch-tools % {:on-tools (fn [tools]
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
