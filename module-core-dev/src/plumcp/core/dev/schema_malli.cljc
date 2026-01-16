;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.dev.schema-malli
  "Malli related schema utility functions."
  (:require
   [bling.explain :as be]
   [malli.core :as mc]
   [malli.error :as me]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(defn bling-explain [schema data]
  (-> schema
      (be/explain-malli data)))


(defn explain [schema data]
  (-> schema
      (mc/explain data)
      me/humanize))


(defn throw-if-invalid! [schema data]
  (or (mc/validate schema data)
      (u/throw! "Malli-schema validation error" (explain schema data))))


(defn request-validation-error [data request]
  {:error {:code sd/error-code-invalid-params
           :message "JSON-RPC Request validation error"
           :data data
           :request request}})


(defn wrap-when-request-schema [request-schema handler]
  (if request-schema
    (fn mcp-request-valiating-handler [jsonrpc-request]
      (if (mc/validate request-schema jsonrpc-request)
        (handler jsonrpc-request)
        (request-validation-error (explain request-schema jsonrpc-request)
                                  jsonrpc-request)))
    handler))


(defn response-validation-error [data response]
  {:error {:code sd/error-code-internal-error
           :message "JSON-RPC Response validation error"
           :data data
           :response response}})


(defn wrap-when-result-schema [result-schema handler]
  (if result-schema
    (fn mcp-result-validating-handler [jsonrpc-request]
      (uab/may-await [response (handler jsonrpc-request)]
        (let [error (:error response)
              result (:result response)]
          (if (or error
                  (mc/validate result-schema result))
            response
            (response-validation-error (explain result-schema result)
                                       response)))))
    handler))


(defn wrap-schema-check
  ([method-name handler]
   (let [[request-spec result-spec] (get sd/mcp-methods method-name)
         request-schema (when request-spec (mc/schema request-spec))
         result-schema (when result-spec (mc/schema result-spec))]
     (->> handler
          (wrap-when-request-schema request-schema)
          (wrap-when-result-schema result-schema))))
  ([handler]
   (fn schema-checking-mcp-client [jsonrpc-request]
     (let [method-name (:method jsonrpc-request)
           new-handler (wrap-schema-check method-name handler)]
       (new-handler jsonrpc-request)))))


(defn wrap-var-kwargs-schema-check
  "Turn a kwarg-accepting defn var into schema-checking `(fn [kwargs])`
   that returns JSON-RPC error response when the params are incorrect
   as per the schema. If no schema is specified, return var as it is."
  [var-instance & {:keys [handler schema-key]
                   :or {handler identity
                        schema-key :malli}}]
  (let [arglists (-> var-instance
                     meta
                     :arglists)]
    (vs/validate-kwargs! arglists)
    (if-let [malli-spec (-> arglists
                            ffirst
                            meta
                            (get schema-key))]
      (let [kwargs-schema (mc/schema malli-spec)
            kwargs-handler (-> var-instance
                               handler)]
        (fn schema-checking-handler [kwargs]
          (if (mc/validate kwargs-schema kwargs)
            (kwargs-handler kwargs)
            (let [explanation (explain kwargs-schema kwargs)]
              (jr/jsonrpc-failure sd/error-code-invalid-params
                                  (u/pprint-str explanation))))))
      var-instance)))
