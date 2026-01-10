(ns plumcp.core.api.mcp-server
  (:require
   [plumcp.core.server.http-ring :as http-ring]
   [plumcp.core.server.server-support :as ss]
   [plumcp.core.server.stdio-server :as stdio-server]
   [plumcp.core.support.banner-print :as bp]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab])
  #?(:cljs (:require-macros [plumcp.core.api.mcp-server])))


(def default-transport :stdio)


(defn run-mcp-server
  [server-options]
  (let [{:keys [runtime
                jsonrpc-handler
                ring-handler   ; only for Streaming-HTTP transport
                stdio-handler  ; only for STDIO transport
                transport
                print-banner?]
         :or {transport default-transport
              print-banner? true}
         :as options} (ss/make-server-options server-options)]
    (u/expected! runtime map? ":runtime to be a map")
    (u/expected! jsonrpc-handler fn? ":jsonrpc-handler to be a function")
    (let [options (merge {:role :server
                          :transport default-transport}
                         options)
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
                  (when print-banner? (bp/print-banner options))
                  (stdio-handler))
        "http"  (uab/may-await [ring-handler (get-ring-handler)
                                ring-server (http-ring/run-ring-server
                                             ring-handler options)]
                  (when print-banner? (bp/print-banner options))
                  ring-server)
        (u/expected! transport "transport to be :stdio or :http")))))


(defmacro run-server
  "Run MCP server using given (or deduced) options.
   | Option keyword           | Default | Description                          |
   |--------------------------|---------|--------------------------------------|
   |:capabilities             |         |Supplied or made from :primitives     |
   |:primitives               |         |Supplied or made from :vars           |
   |:vars                     |         |Supplied/discovered/made from :ns     |
   |:ns (read literally)      |Caller ns|(Vector of) Namespaces to find vars in|
   |:traffic-logger           |         |No-op by default                      |
   |:runtime                  |         |made from :capabilities/traffic-logger|
   |:mcp-methods-wrapper      |identity |Middleware `(fn [handlers])->handlers`|
   |:jsonrpc-handler          |         |Impl+made with :schema-check-wrapper  |
   |:transport                | :stdio  |Either of :stdio, :http               |
   |:ring-handler  (for HTTP) |         |Made from :runtime, :jsonrpc-handler  |
   |:stdio-handler (for STDIO)|         |Made from :runtime, :jsonrpc-handler  |
   |:print-banner?            |  True   |Print a library banner if true        |

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
      (run-mcp-server (merge {:vars default-vars#}
                             ~options))))
  ([]
   `(run-server {})))
