(ns plumcp.core.schema.json-rpc
  "JSON-RPC utility functions."
  (:require
   [clojure.set :as set]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]))


(defn add-jsonrpc-id
  "Add ID to JSON-RPC request or response."
  [m id]
  (assoc m :id id))


(defn jsonrpc-failure
  "Create a JSON-RPC failure response."
  ([error-code error-message error-data]
   {:jsonrpc sd/jsonrpc-version
    :error {:code error-code
            :message error-message
            :data error-data}})
  ([error-code error-message]
   (jsonrpc-failure error-code error-message {})))


(defn jsonrpc-success
  "Create a JSON-RPC success response."
  ([result]
   {:jsonrpc sd/jsonrpc-version
    :result result})
  ([id result]
   {:jsonrpc sd/jsonrpc-version
    :id id
    :result result}))


(defn jsonrpc-request-or-notification?
  "Return true if given map is possibly a JSON-RPC request/notification,
   false otherwise. Keys expected in a request/notification:
   :jsonrpc
   :method
   :id (in request only)"
  [m]
  (and (map? m)
       (= (:jsonrpc m) "2.0")
       (let [method (:method m)]
         (and (string? method)
              (seq method)))))


(defn jsonrpc-notification?
  "Return true if given map is a JSON-RPC notification, false otherwise.
   Keys expected in a notification:
   :jsonrpc
   :method"
  [m]
  (and (jsonrpc-request-or-notification? m)
       (not (contains? m :id))))


(defn jsonrpc-request?
  "Return true if given map is a JSON-RPC request, false otherwise.
   Keys expected in a request:
   :jsonrpc
   :id
   :method
   :params (optional)"
  [m]
  (and (jsonrpc-request-or-notification? m)
       (let [id (:id m)]
         (or (integer? id)
             (and (string? id)
                  (seq id))))))


(defn jsonrpc-error?
  "Return true if given map is a JSON-RPC error, false otherwise."
  [m]
  (and (map? m)
       (:error m)))


(defn jsonrpc-result?
  "Return true if given map is a JSON-RPC result, false otherwise."
  [m]
  (and (map? m)
       (not (:error m))
       (:result m)))


(defn jsonrpc-response?
  "Return true if given map is a JSON-RPC response, false otherwise."
  [m]
  (and (map? m)
       (or (:result m)
           (:error m))))


(defn request-params-meta
  [jsonrpc-request]
  (get-in jsonrpc-request [:params
                           :_meta]))


(def jsonrpc-request-keys #{:jsonrpc
                            :id
                            :params})


(def jsonrpc-response-keys #{:jsonrpc
                             :id
                             :result
                             :error})


(def jsonrpc-all-keys (set/union jsonrpc-request-keys
                                 jsonrpc-response-keys))


(defn select-jsonrpc-keys
  [m]
  (select-keys m jsonrpc-all-keys))


(defn remove-jsonrpc-keys
  [m]
  (apply dissoc m jsonrpc-all-keys))


(defn jsonrpc-trim-response
  [jsonrpc-response]
  (cond
    (map? jsonrpc-response) (select-jsonrpc-keys jsonrpc-response)
    (vector? jsonrpc-response) (mapv jsonrpc-trim-response
                                     jsonrpc-response)
    :else (u/expected! jsonrpc-response
                       "a JSON-RPC response map or vector")))
