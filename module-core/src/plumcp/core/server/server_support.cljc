;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.server-support
  (:require
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.impl-support :as is]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u]))


(defn copy-result-deps
  [args jsonrpc-response request-context]
  (-> args
      (rt/copy-runtime jsonrpc-response)
      (rt/?request-id (:id jsonrpc-response))
      (rt/?request-context request-context)))


(defn make-jsonrpc-response-handler
  "Make a handler for JSON-RPC responses sent by the MCP client to the
   MCP server."
  [callback-name-handler-map]
  (fn jsonrpc-response-handler [jsonrpc-response]
    (let [request-id (:id jsonrpc-response)]
      (rs/log-incoming-jsonrpc-response jsonrpc-response)
      (if-some [request-context (rs/extract-pending-request-context
                                 jsonrpc-response request-id)]
        (if-let [error (:error jsonrpc-response)]
          {} ; because error received
          (if-some [callback-handler (->> (get request-context :callback-name)
                                          (get callback-name-handler-map))]
            (-> (:result jsonrpc-response)
                (copy-result-deps jsonrpc-response request-context)
                (callback-handler))
            (-> (jr/jsonrpc-failure sd/error-code-internal-error
                                    (str "No callback-handler found for request-ID "
                                         request-id)
                                    {:id request-id})
                (jr/add-jsonrpc-id request-id))))
        (-> (jr/jsonrpc-failure sd/error-code-invalid-params
                                (str "No response awaited for request-ID "
                                     request-id)
                                {:id request-id})
            (jr/add-jsonrpc-id request-id))))))


(defn make-server-jsonrpc-message-handler
  "Create a function `(fn [jsonrpc-message])->jsonrpc-return-value` to
   accept and handle JSON-RPC messages (request/notification/response)
   for the server."
  [{:keys [request-methods-wrapper
           notification-method-handlers
           default-notification-handler
           callback-handlers]
    :or {request-methods-wrapper identity
         notification-method-handlers is/server-received-notification-handlers
         default-notification-handler u/nop
         callback-handlers {}}}]
  (let [request-handler (-> is/mcp-server-methods
                            request-methods-wrapper
                            is/make-dispatching-jsonrpc-request-handler)
        notification-handler (is/make-dispatching-jsonrpc-notification-handler
                              notification-method-handlers
                              default-notification-handler)
        response-handler (-> callback-handlers
                             make-jsonrpc-response-handler)]
    (is/make-jsonrpc-message-handler request-handler
                                     notification-handler
                                     response-handler)))


(defn make-server-options
  "Make server options from given input map, returning an output map:
   | Keyword-option        | Default  | Description                        |
   |-----------------------|----------|------------------------------------|
   |:info                  |          | see p.c.a.entity-support/make-info |
   |:instructions          |          | Server instructions for MCP client |
   |:capabilities          | Required | Given/made from :primitives        |
   |:primitives            | --       | Given/made from :vars              |
   |:vars                  | --       | To make primitives                 |
   |:traffic-logger        | No-op    | MCP transport traffic logger       |
   |:runtime               | --       | Made from :impl,:capabilities,:tr..|
   |:override              | {}       | Merged into final runtime          |
   |:mcp-methods-wrapper   | No-op    | Wraper for MCP-methods impl        |
   |:jsonrpc-handler       | --       | Made from impl and options         |

   Option kwargs when JSON-RPC handler is constructed:
   |Keyword option               |Default|Description                      |
   |-----------------------------|-------|---------------------------------|
   |:callback-handlers           |{}     |For old req, get from :primitives|
   |:request-methods-wrapper     |No-op  |MCP request-methods impl wrapper |
   |:default-notification-handler|No-op  |Notif handler (fn [notif-msg])   |

   The returned output map contains the following keys:
   :runtime          Server runtime map
   :jsonrpc-handler  JSON-RPC handler fn"
  [{:keys [^{:see [es/make-info]}
           info
           instructions
           capabilities
           primitives
           vars
           traffic-logger
           runtime
           override
           mcp-methods-wrapper
           jsonrpc-handler]
    :or {traffic-logger stl/compact-server-traffic-logger
         override {}
         mcp-methods-wrapper identity}
    :as server-options}]
  (when-not (or (u/non-empty-map? capabilities)
                (u/non-empty-map? primitives)
                (not-empty vars))
    (u/throw! "Expected :capabilities, :primitives or :vars to be non-empty"
              {:capabilities capabilities
               :primitives primitives
               :vars vars}))
  (let [emsg "You must supply either of :capabilities, :primitives, or :vars"
        get-primitives (fn []
                         (or primitives
                             (some-> vars
                                     (vs/vars->server-primitives server-options))
                             (u/throw! emsg server-options)))
        get-capabilities (fn []
                           (or capabilities
                               (some-> (get-primitives)
                                       vs/primitives->fixed-server-capabilities)))
        get-runtime (fn []
                      (-> runtime
                          (or (-> {}
                                  (cond-> info (rt/?server-info info)
                                          instructions (rt/?server-instructions
                                                        instructions))
                                  (rt/?server-capabilities (get-capabilities))
                                  (rt/?traffic-logger traffic-logger)
                                  (rt/get-runtime)))
                          (merge override)))
        get-jsonrpc-handler (fn []
                              (or jsonrpc-handler
                                  (-> server-options
                                      (u/assoc-some :request-methods-wrapper
                                                    mcp-methods-wrapper)
                                      (u/assoc-some :callback-handlers
                                                    (:callbacks primitives))
                                      make-server-jsonrpc-message-handler)))]
    (-> server-options
        (merge {:runtime (get-runtime)
                :jsonrpc-handler (get-jsonrpc-handler)}))))
