(ns plumcp.core.dev.api
  "Public API for core-dev module. Add-ons for development support."
  (:require
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.dev.schema-malli :as sm]))


(defn update-schemas [methods-map]
  (reduce-kv (fn [m method-name impl-fn]
               (->> impl-fn
                    (sm/wrap-schema-check method-name)
                    (assoc m method-name)))
             {}
             methods-map))

(def client-options
  "MCP-client development support options for use with:
   zoomcp.core.public.mcp-client/make-mcp-client
   zoomcp.core.public.mcp-client/make-client"
  {:mcp-methods-wrapper update-schemas
   :var-handler sm/wrap-var-kwargs-schema-check
   :traffic-logger blogger/client-logger})


(def server-options
  "MCP-server development support options for use with:
   zoomcp.core.public.mcp-server/run-mcp-server
   zoomcp.core.public.mcp-server/run-server"
  {:mcp-methods-wrapper update-schemas
   :var-handler sm/wrap-var-kwargs-schema-check
   :traffic-logger blogger/server-logger})
