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
   [plumcp.core.schema.json-rpc :as jr]
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
