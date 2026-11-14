;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.impl.era26-impl-method
  "Implementation of the client and server MCP methods."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.era26-entity-gen :as e26eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.impl.impl-method :as im]
   [plumcp.core.schema.era26-schema-defs :as e26sd]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.schema.schema-support :as ss]
   [plumcp.core.util :as u]))


(def default-cacheable-result (e26eg/make-cacheable-result))


(def cacheable-result-methods
  #{^{:see [e26sd/DiscoverResult]} e26sd/method-server-discover
    ^{:see [e26sd/ListPromptsResult]} sd/method-prompts-list
    ^{:see [e26sd/ListResourcesResult]} sd/method-resources-list
    ^{:see [e26sd/ListResourceTemplatesResult]} sd/method-resources-templates-list
    ^{:see [e26sd/ListToolsResult]} sd/method-tools-list
    ^{:see [e26sd/ReadResourceResult]} sd/method-resources-read})


(defn as-e26-result
  "Given a (potentially Era25) result add the missing bits to make it
   Era26 compliant."
  [result jsonrpc-request]
  (let [e26-result (u/assoc-missing
                    result
                    :resultType e26sd/result-type-complete
                    :_meta (let [server-info (rt/?server-info jsonrpc-request)]
                             (e26eg/make-result-meta-object
                              {:server-info server-info})))]
    (if (contains? cacheable-result-methods (:method jsonrpc-request))
      (merge default-cacheable-result
             e26-result)
      e26-result)))


(defn ^{:async true
        :see [im/make-result]} make-e26result
  "Given {:result <result>} or a JSON-RPC response map, adjust the
   result map as per the Era26 structure."
  [result-or-jsonrpc-response jsonrpc-request]
  (let [result-or-jsonrpc-response (-> result-or-jsonrpc-response
                                       u/do-await)]
    (or (u/only-when jr/jsonrpc-response? result-or-jsonrpc-response)
        (update result-or-jsonrpc-response :result
                as-e26-result jsonrpc-request))))


;; --- Handshake ---


(defn ^:async server-discover
  "Server allowing to be discovered by a client. Check for allowed
   protocol version and respond accordingly."
  [^{:see [e26sd/DiscoverRequest
           e26sd/DiscoverResult
           e26sd/DiscoverResultResponse]}
   {request-id :id
    :as jsonrpc-request}]
  (let [supported-versions e26sd/protocol-versions-supported]
    ;; check whether the client protocol version is an allowed one
    (if (ss/era26-request? jsonrpc-request)
      ;; protocol version is a match
      (let [server-capabilities (-> (rt/?server-capabilities jsonrpc-request)
                                    (ic/get-server-capability-declaration
                                     {:mcp-era e26sd/mcp-era26}))
            server-instructions (rt/?server-instructions jsonrpc-request)]
        (-> (e26eg/make-discover-result
             {:supported-versions supported-versions
              :server-capabilities server-capabilities
              :instructions server-instructions})
            im/make-result
            (make-e26result jsonrpc-request)
            u/do-await))
      ;; error response
      (eg/make-jsonrpc-error-response
       request-id
       e26sd/error-code-unsupported-protocol-version
       (str "Allowed protocol versions: "
            supported-versions)))))
