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
   [plumcp.core.impl.impl-method :as im]
   [plumcp.core.schema.era26-schema-defs :as e26sd]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.util :as u]))


(defn ^{:async true
        :see [im/make-result]} make-e26result
  "Given {:result <result>} or a JSON-RPC response map, adjust the
   result map as per the Era26 structure."
  [result-or-jsonrpc-response jsonrpc-request]
  (let [result-or-jsonrpc-response (-> result-or-jsonrpc-response
                                       u/do-await)]
    (or (u/only-when jr/jsonrpc-response? result-or-jsonrpc-response)
        (update result-or-jsonrpc-response :result
                u/assoc-missing
                :resultType e26sd/result-type-complete
                :_meta (let [server-info (rt/?server-info jsonrpc-request)]
                         (e26eg/make-result-meta-object
                          {:server-info server-info}))))))


;; --- Handshake ---


(defn server-discover
  "Server allowing to be discovered by a client. Check for allowed
   protocol version and respond accordingly."
  [^{:see [e26sd/DiscoverRequest
           e26sd/DiscoverResult
           e26sd/DiscoverResultResponse]}
   {params :params
    request-id :id
    :as jsonrpc-request}]
  (let [request-protocol-version (get-in params [:_meta
                                                 e26sd/key-protocol-version])
        supported-versions e26sd/protocol-versions-supported]
    ;; check whether the client protocol version is an allowed one
    (if (contains? e26sd/protocol-versions-supported-set
                   request-protocol-version)
      ;; protocol version is a match
      (let [server-capabilities (rt/?server-capabilities jsonrpc-request)
            server-instructions (rt/?server-instructions jsonrpc-request)]
        (e26eg/make-discover-result
         {:supported-versions supported-versions
          :server-capabilities server-capabilities
          :instructions server-instructions}))
      ;; error response
      (eg/make-jsonrpc-error-response
       request-id
       e26sd/error-code-unsupported-protocol-version
       (str "Allowed protocol versions: "
            supported-versions)))))
