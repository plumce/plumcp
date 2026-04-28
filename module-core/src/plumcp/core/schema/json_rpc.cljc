;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.schema.json-rpc
  "JSON-RPC utility functions."
  (:require
   [clojure.set :as set]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]))


(defn pred-valid-method?
  [m]
  (let [method (:method m)]
    (and (string? method)
         (seq method))))


(defn pred-valid-id?
  [m]
  (let [id (:id m)]
    (or (integer? id)
        (and (string? id)
             (seq id)))))


(defn pred-notification?
  [m]
  (:method m))


(defn pred-request?
  [m]
  (and (:method m)
       (:id m)))


(defn pred-response?
  [m]
  (or (:result m)
      (:error m)))


(defn ^{:see [pred-request?
              pred-notification?
              pred-response?]} jsonrpc-message?
  "Return true if given argument (map) is a JSON-RPC message, false
   otherwise. Pass predicate for a specific check as follows:
   | JSON-RPC message      | Predicate            |
   |-----------------------|----------------------|
   | JSON-RPC request      | `pred-request?`      |
   | JSON-RPC notification | `pred-notification?` |
   | JSON-RPC Response     | `pred-response?`     |"
  ([m pred]
   (and (map? m)
        (= (:jsonrpc m) sd/jsonrpc-version)
        (pred m)))
  ([m]
   (jsonrpc-message? m (some-fn pred-request?
                                pred-notification?
                                pred-response?))))


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
       (= (:jsonrpc m) sd/jsonrpc-version)
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
       (= (:jsonrpc m) sd/jsonrpc-version)
       (:error m)))


(defn jsonrpc-error
  "Return error from a failure JSON-RPC response."
  ([jsonrpc-response not-found]
   (if (jsonrpc-error? jsonrpc-response)
     (:error jsonrpc-response)
     not-found))
  ([jsonrpc-response]
   (jsonrpc-error jsonrpc-response nil)))


(defn jsonrpc-result?
  "Return true if given map is a JSON-RPC result, false otherwise."
  [m]
  (and (map? m)
       (= (:jsonrpc m) sd/jsonrpc-version)
       (not (:error m))
       (:result m)))


(defn jsonrpc-result
  "Return result from a success JSON-RPC response."
  ([jsonrpc-response not-found]
   (if (jsonrpc-result? jsonrpc-response)
     (:result jsonrpc-response)
     not-found))
  ([jsonrpc-response]
   (jsonrpc-result jsonrpc-response nil)))


(defn jsonrpc-response?
  "Return true if given map is a JSON-RPC response, false otherwise."
  [m]
  (and (map? m)
       (= (:jsonrpc m) sd/jsonrpc-version)
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
