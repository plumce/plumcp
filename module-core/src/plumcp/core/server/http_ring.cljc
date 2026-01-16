;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.http-ring
  "Create MCP server using Streamable HTTP transport with Ring adapter."
  (:require
   [plumcp.core.impl.server-handler :as sh]
   [plumcp.core.server.http-ring-transport :as hrt]
   [plumcp.core.support.http-server :as hs]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.deps.runtime :as rt]))


(defn make-ring-handler
  "Make an MCP Ring handler from given server-runtime, MCP-JSON-RPC-handler
   and options. Wrap given JSON-RPC handler with MCP addons (session, SSE,
   logging, etc.)"
  [runtime
   mcp-jsonrpc-handler
   & {:keys [uri-set
             auth-options
             default-handler]
      :or {uri-set #{"/mcp" "/mcp/"}
           default-handler (let [txt (str "Invalid request - you have "
                                          "reached an invalid endpoint")]
                             (fn [_]
                               {:status 400
                                :headers {"Content-Type" "text/plain"}
                                :body txt}))}
      :as options}]
  (if (uab/awaitable? auth-options)
    (uab/let-await [auth-options auth-options]
      (->> (assoc options  :auth-options auth-options)
           (make-ring-handler runtime mcp-jsonrpc-handler)))
    (-> (partial hrt/fallback-mcp-handler uri-set)
        (hrt/wrap-mcp-response mcp-jsonrpc-handler options)
        (hrt/wrap-delete-handler)
        (hrt/wrap-oauth auth-options)
        (sh/wrap-traffic-logger)
        (sh/wrap-mcp-session hrt/ring-session-request
                             hrt/ring-session-response)
        (hrt/wrap-json-request)
        (hrt/wrap-json-response)
        (hrt/wrap-route-match uri-set
                              {:methods [:get
                                         :delete
                                         :post]
                               :get-uri-routes (get auth-options
                                                    :well-known-routes)
                               :on-uri-mismatch default-handler})
        (sh/wrap-exception-catching)
        (rt/wrap-runtime runtime))))


#?(:clj
   (defn wrap-request-body-reader
     "Ring middleware to add the `:on-msg` entry to the Ring request if
      required. You may want this when using a Ring adapter (server)."
     [handler]
     (fn request-body-reading-handler [request]
       (if (or (contains? request :on-msg)
               (not (contains? #{:post :put} (:request-method request))))
         (handler request)
         (-> request
             (assoc :on-msg (fn on-msg [on-message]
                              (-> (:body request)
                                  slurp
                                  on-message)))
             (handler))))))


(defn run-ring-server
  "Run the Ring server using the MCP Ring-handler and server options."
  [handler & {:keys [ring-server]
              :or {ring-server hs/run-http-server}
              :as options}]
  (uab/may-await [handler handler]
    (try
      (ring-server handler options)
      (catch #?(:clj Exception :cljs js/Error) e
        (if (= (ex-message e) "Address already in use")
          (u/throw! (format "Port %d already in use"
                            (:port options 3000)))
          (throw e))))))
