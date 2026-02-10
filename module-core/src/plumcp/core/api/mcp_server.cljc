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
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocol :as p]
   [plumcp.core.server.http-ring :as http-ring]
   [plumcp.core.server.server-support :as ss]
   [plumcp.core.server.stdio-server :as stdio-server]
   [plumcp.core.support.banner-print :as bp]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.key-lookup :as kl])
  #?(:cljs (:require-macros [plumcp.core.api.mcp-server])))


(def default-transport :stdio)


(defrecord RunningServer [server list-notifier]
  p/IStoppable
  (stop! [_] (try (when list-notifier (p/stop! list-notifier))
                  (finally
                    (when server (p/stop! server))))))


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
          run-list-notifier (fn []
                              (when run-list-notifier?
                                (cap/run-list-changed-notifier
                                 (-> runtime
                                     (kl/?get rt/?server-capabilities)
                                     cap/get-server-listed-capabilities)
                                 (let [context (rt/upsert-runtime
                                                {} runtime)]
                                   (fn [notification]
                                     (rs/notify-initialized-clients
                                      context notification)))
                                 list-notifier-options)))
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
                  (->RunningServer (stdio-handler) (run-list-notifier)))
        "http"  (uab/may-await [ring-handler (get-ring-handler)
                                ring-server (http-ring/run-ring-server
                                             ring-handler options)]
                  (when print-banner? (bp/print-banner server-info options))
                  (->RunningServer ring-server (run-list-notifier)))
        (u/expected! transport "transport to be :stdio or :http")))))


(defmacro ^{:see [run-mcp-server
                  RunningServer]} run-server
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
