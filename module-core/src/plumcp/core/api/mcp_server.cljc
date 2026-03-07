;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.mcp-server
  "MCP Server implementation.
   Ref: https://github.com/cyanheads/model-context-protocol-resources/blob/main/guides/mcp-server-development-guide.md"
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.http-ring :as http-ring]
   [plumcp.core.server.server-support :as ss]
   [plumcp.core.server.stdio-server :as stdio-server]
   [plumcp.core.support.banner-print :as bp]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.key-lookup :as kl])
  #?(:cljs (:require-macros [plumcp.core.api.mcp-server])))


;; --- Server-runtime utility ---


;; Request utility


(defn get-request-params-meta
  "Every MCP request potentially includes optional metadata, which
   you may extract from args passed to a server handler function."
  [kwargs]
  (rt/?request-params-meta kwargs))


(defn get-request-id
  "Every MCP request includes a request ID, which you may extract from
   the keyword-args passed to a server handler function."
  [kwargs]
  (rt/?request-id kwargs))


(defn get-callback-context
  "When an MCP response is received by the server, it is tagged with the
   callback context that accompanied the request it was sent with. The
   callback context is returned."
  [result]
  (rt/?callback-context result))


(defn make-callback-context
  "Make the callback context to accompany a request to client. Optional
   arg `kv-map` data should be JSON-serializable."
  ([callback-name kv-map]
   (assoc kv-map
          rs/callback-name-key (u/as-str callback-name)))
  ([callback-name]
   {rs/callback-name-key (u/as-str callback-name)}))


(defn get-runtime
  [args]
  (rt/get-runtime args))


(defn remove-runtime
  [context]
  (rt/dissoc-runtime context))


;; Notifications


(defn send-notification-to-client
  "Send a notification to the client."
  [context ^{:see [eg/make-cancellation-notification
                   eg/make-progress-notification]} notification]
  (rs/send-notification-to-client context notification))


(defmacro with-logger
  "Associate MCP logger (for MCP log events) in the lexical scope.
   Example:
   (with-logger [args \"discount-rate\"]  ; `args` is handler args map
     ; here all logs will inherit the :logger as bound above
     ,,,)"
  [[logger-context logger] & body]
  (assert (symbol? logger-context) "logger-context must be a symbol")
  `(let [~logger-context (rt/?mcp-logger ~logger-context
                                         (u/as-str ~logger))]
     ~@body))


(defn ^{:see [sd/LoggingLevel]} log-7-debug
  "MCP-Log at DEBUG level."
  [context entry]
  (rs/log context sd/log-level-7-debug entry))


(defn ^{:see [sd/LoggingLevel]} log-6-info
  "MCP-Log at INFO level."
  [context entry]
  (rs/log context sd/log-level-6-info entry))


(defn ^{:see [sd/LoggingLevel]} log-5-notice
  "MCP-Log at NOTICE level."
  [context entry]
  (rs/log context sd/log-level-5-notice entry))


(defn ^{:see [sd/LoggingLevel]} log-4-warning
  "MCP-Log at WARNING level."
  [context entry]
  (rs/log context sd/log-level-4-warning entry))


(defn ^{:see [sd/LoggingLevel]} log-3-error
  "MCP-Log at ERROR level."
  [context entry]
  (rs/log context sd/log-level-3-error entry))


(defn ^{:see [sd/LoggingLevel]} log-2-critical
  "MCP-Log at CRITICAL level."
  [context entry]
  (rs/log context sd/log-level-2-critical entry))


(defn ^{:see [sd/LoggingLevel]} log-1-alert
  "MCP-Log at ALERT level."
  [context entry]
  (rs/log context sd/log-level-1-alert entry))


(defn ^{:see [sd/LoggingLevel]} log-0-emergency
  "MCP-Log at EMERGENCY level."
  [context entry]
  (rs/log context sd/log-level-0-emergency entry))


;; Requests


(defn ^{:see [sd/ClientCapabilities]} get-client-capabilities
  "Get the client capabilities as received during initialization."
  [context]
  (some-> (rs/get-initialized-params context)
          :capabilities))


(defn send-request-to-client
  "Send given request to the client and tag the callback-context to the
   pending request entry. You should check if client has the respective
   capability before sending request (see `get-client-capabilities`)."
  [context ^{:see [eg/make-create-message-request  ; sampling
                   eg/make-elicit-request]} request callback-context]
  (rs/send-request-to-client context request callback-context))


;; --- Running server ---


(def default-transport :stdio)


(declare run-server)


(defn ^{:see [run-server]} run-mcp-server
  "Run MCP server based on the given options, returning a RunningServer
   instance. See `run-server` for detailed docs."
  [server-options]
  (let [{:keys [runtime
                jsonrpc-handler
                ring-handler   ; only for Streamable-HTTP transport
                stdio-handler  ; only for STDIO transport
                transport
                run-list-notifier?
                list-notifier-options
                print-banner?]
         :or {transport default-transport
              run-list-notifier? true
              list-notifier-options {}
              print-banner? true}
         :as options} (ss/make-server-options server-options)]
    (u/expected! runtime map? ":runtime to be a map")
    (u/expected! jsonrpc-handler fn? ":jsonrpc-handler to be a function")
    (let [options (merge {:role :server
                          :transport-info (if transport
                                            {:id transport}
                                            {:id default-transport})}
                         options)
          server-info (kl/?get runtime rt/?server-info)
          run-list-notifier (if run-list-notifier?
                              (partial ss/run-list-notifier
                                       runtime
                                       list-notifier-options)
                              u/nop)
          get-stdio-handler (fn []
                              (or stdio-handler
                                  (stdio-server/make-stdio-handler
                                   runtime
                                   jsonrpc-handler)))
          get-ring-handler  (fn []
                              (or ring-handler
                                  (http-ring/make-ring-handler
                                   runtime
                                   jsonrpc-handler
                                   options)))]
      (case (u/as-str transport)
        "stdio" (uab/may-await [stdio-handler (get-stdio-handler)]
                  (when print-banner? (bp/print-banner server-info options))
                  (ss/run-stdio-mcpserver stdio-handler
                                          run-list-notifier))
        "http"  (uab/may-await [ring-handler (get-ring-handler)
                                ring-server (http-ring/run-ring-server
                                             ring-handler options)]
                  (when print-banner? (bp/print-banner server-info options))
                  (ss/run-http-mcp-server ring-server
                                          run-list-notifier))
        (u/expected! transport "transport to be :stdio or :http")))))


(defmacro ^{:see [run-mcp-server]} run-server
  "Run MCP server using given (or deduced) options, returning a
   RunningServer instance.
   | Option keyword           | Default | Description                          |
   |--------------------------|---------|--------------------------------------|
   |:info                     |Required |see p.c.api.entity-support/make-info  |
   |:instructions             |         |Server instructions for the MCP client|
   |:capabilities             |         |Supplied or made from :primitives     |
   |:primitives               |         |Supplied or made from :vars           |
   |:vars                     |         |Supplied/discovered/made from :ns     |
   |:ns (read literally)      |Caller ns|(Vector of) Namespaces to find vars in|
   |:traffic-logger           |         |No-op by default                      |
   |:runtime                  |         |made from :impl,:capabilities,:traff..|
   |:override                 | {}      |Merged into final runtime             |
   |:mcp-methods-wrapper      |identity |Middleware `(fn [handlers])->handlers`|
   |:jsonrpc-handler          |         |Impl+made with :schema-check-wrapper  |
   |:transport                | :stdio  |Either of :stdio, :http               |
   |:ring-handler  (for HTTP) |         |Made from :runtime, :jsonrpc-handler  |
   |:stdio-handler (for STDIO)|         |Made from :runtime, :jsonrpc-handler  |
   |:print-banner?            |  True   |Print a library banner if true        |
   |:run-list-notifier?       |  True   |Run list-changed notifier if true     |
   |:list-notifier-options    |  {}     |Option map for list-changed notifier  |

   Dependency map (left/key depends upon the right/vals):
   {:ring-handler    [:runtime :jsonrpc-handler]
    :stdio-handler   [:runtime :jsonrpc-handler]
    :runtime         [:info :instructions :capabilities :traffic-logger]
    :jsonrpc-handler [:schema-check-wrapper :jsonrpc-response-handler]}"
  ([options]
   `(let [default-vars# (or (:vars ~options)
                            ~(if-let [nses (:ns options)]
                               `(concat ~@(->> (u/as-vec nses)
                                               (mapv #(do `(u/find-vars ~%)))))
                               `(u/find-vars)))]
      (run-mcp-server (merge {:vars default-vars#}
                             ~options))))
  ([]
   `(run-server {})))


(defn stop-server
  "Stop a running MCP server."
  [running-server]
  (p/stop! running-server))
