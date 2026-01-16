;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


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
   plumcp.core.public.mcp-client/make-mcp-client
   plumcp.core.public.mcp-client/make-client"
  {:mcp-methods-wrapper update-schemas
   :var-handler sm/wrap-var-kwargs-schema-check
   :traffic-logger blogger/client-logger})


(def server-options
  "MCP-server development support options for use with:
   plumcp.core.public.mcp-server/run-mcp-server
   plumcp.core.public.mcp-server/run-server"
  {:mcp-methods-wrapper update-schemas
   :var-handler sm/wrap-var-kwargs-schema-check
   :traffic-logger blogger/server-logger})
