;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.client-method
  "MCP Client methods implementation."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


;; --- JSON-RPC Response-handling utility ---


;; Print to STDERR


(defn on-error-print
  "Given the :error strucure of an JSON-RPC error response, print the
   error to STDERR."
  [client-op-name jsonrpc-error]
  (-> "[%s] Client operation error:"
      (format client-op-name)
      (u/eprintln jsonrpc-error)))


(defn on-timeout-print
  "Print a client-operation timeout error message to STDERR."
  [client-op-name _]
  (-> "[%] Client operation timed out"
      (format client-op-name)
      u/eprintln))


(defn on-unknown-print
  "Print error message, because unknown response is passed, to STDERR."
  [client-op-name unknown-response]
  (-> "[%s] Unknwon response in client operation:"
      (format client-op-name)
      (u/eprintln unknown-response)))


(defn on-jsonrpc-response-print
  "Options to use when you want to simply print error."
  [client-op-name]
  {:on-error (partial on-error-print client-op-name)
   :on-timeout (partial on-timeout-print client-op-name)
   :on-unknown (partial on-unknown-print client-op-name)})


;; Throw exception


(defn on-error-throw!
  "Given the :error strucure of an JSON-RPC error response, throw an
   exception."
  [client-op-name {:keys [code message data]}]
  (u/throw! (-> "Client operation % error:"
                (format client-op-name)
                (str message))
            (merge {:error-code code}
                   data)))


(defn on-timeout-throw!
  "Throw a client-operation timeout exception."
  [client-op-name _]
  (u/throw! (-> "Client operation %s timed out"
                (format client-op-name))))


(defn on-unknown-throw!
  "Throw exception because an unknown response is passed."
  [client-op-name unknown-response]
  (-> "[on-jsonrpc-response] Unknwon response in client operation"
      (str client-op-name)
      (u/throw! {:unknown-response unknown-response})))


(defn on-jsonrpc-response-throw!
  "Options to use when you want to throw exceptions on error."
  [client-op-name]
  {:on-error (partial on-error-throw! client-op-name)
   :on-timeout (partial on-timeout-throw! client-op-name)
   :on-unknown (partial on-unknown-throw! client-op-name)})


;; On JSON-RPC response


(defn on-jsonrpc-response
  "Process JSON-RPC response to derive the final output.
   :on-result     - (fn [result])
   :on-error      - (fn [client-op-name jsonrpc-error])
   :timeout-value - same value that you pass to `uab/as-async`
   :on-timeout    - (fn [client-op-name timeout-value])
   :on-unknown    - (fn [client-op-name unknown-response])"
  [async-jsonrpc-response
   client-op-name
   & ^{:see [on-jsonrpc-response-throw!]}
   {:keys [on-result
           ^{:see [on-error-throw!]} on-error
           ^{:see [uab/as-async]} timeout-value
           ^{:see [on-timeout-throw!]} on-timeout
           ^{:see [on-unknown-throw!]} on-unknown]
    :or {on-result identity
         on-error (partial on-error-print client-op-name)
         timeout-value (u/uuid-v4) ; only caller-supplied value matches
         on-timeout (partial on-timeout-print client-op-name)
         on-unknown (partial on-unknown-print client-op-name)}}]
  (uab/let-await [jsonrpc-response async-jsonrpc-response]
    (condp u/invoke jsonrpc-response
      jr/jsonrpc-result? (-> jsonrpc-response
                             jr/jsonrpc-result
                             on-result)
      jr/jsonrpc-error? (-> jsonrpc-response
                            jr/jsonrpc-error
                            on-error)
      #(= % timeout-value) (on-timeout jsonrpc-response)
      (on-unknown jsonrpc-response))))


;; --- Client utility functions ---


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


;; --- ASYNC client functions ---


;; ASYNC result/response utility


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


;; ASYNC initilization / de-initialization / handshake


(defn async-initialize!
  "Send initialize request to the MCP server, and setup a session on
   success. The JSON-RPC response is passed to `on-jsonrpc-response`.
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


(defn async-initialize-and-notify!
  "Send initialize request to the MCP server and on success, setup a
   session and notify the MCP server of a successful initialization
   after caching the initialize result."
  [client]
  (async-initialize! client
                     (-> (fn [result]
                           (-> (cs/?client-cache client)
                               (cs/?cc-initialize-result result))
                           (notify-initialized client))
                         on-result->on-response)))


;; ASYNC MCP requests expecting result


(defn on-tools->on-result
  "Given a (fn [tools]) return (fn [jsonrpc-result])."
  [f]
  (cs/destructure-result f sd/result-key-tools))


(defn async-list-tools
  "Fetch the list of MCP tools supported by the server. The JSON-RPC
   response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListToolsResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-tools->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-tools-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


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


(defn async-ping
  "Send a ping request to the server. The JSON-RPC response is passed to
   `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/JSONRPCError
                  on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-ping-request)]
    (cs/send-request-to-server client request on-jsonrpc-response)))


;; --- Synchronous client functions ---


;; Utilities


(defn get-from-cache
  [client getter]
  (-> (cs/?client-cache client)
      getter))


(defn set-into-cache
  [payload client setter]
  (when (-> client
            cs/?cache-primitives?)  ; is caching primitives allowed?
    (-> (cs/?client-cache client)
        (setter payload))))


;; Operations


(defn ^{:see [sd/JSONRPCResponse
              sd/InitializeResult
              sd/JSONRPCError
              async-initialize!
              on-jsonrpc-response
              on-jsonrpc-response-throw!]} initialize!
  "Send initialize request to the MCP server and setup a session
   returning initialize result (value in CLJ, js/Promise in CLJS) on
   success, nil on error (printed to STDERR).
   Options:
   - see plumcp.core.util.async-bridge/as-async
   - see plumcp.core.client.client-support/on-jsonrpc-response
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-initialize! client
                           return))
      (on-jsonrpc-response "initialize"
                           (-> options
                               (dissoc :on-result)))))
