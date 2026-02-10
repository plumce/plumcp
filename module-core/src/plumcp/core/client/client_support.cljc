;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.client-support
  (:require
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.impl.impl-support :as is]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(defn get-session-context
  [client]
  @(:session-context-atom client))


(defn set-session-context!
  [client new-session-context]
  (reset! (:session-context-atom client) new-session-context))


(def key-mcp-session-id
  "Key in JSON-RPC response map to refer to MCP Session ID."
  :mcp-session-id)


(defn assoc-session-context [client jsonrpc-message]
  (-> jsonrpc-message
      (assoc key-mcp-session-id (get-session-context client))))


(defn get-message-session-context [jsonrpc-response]
  (select-keys jsonrpc-response [key-mcp-session-id]))


(defn send-message-to-server
  [{:keys [pending-client-requests-atom  ; {<req-id> {:ts <ms> :callback <fn>}}
           pending-server-requests-atom  ; {<req-id> {:ts <ms>}}
           session-context-atom
           send-message]
    :as _context}
   jsonrpc-message]
  (let [jsonrpc-message (merge jsonrpc-message @session-context-atom)]
    (if-let [id (:id jsonrpc-message)]
      (cond
        ;; request (to server)
        (jr/jsonrpc-request? jsonrpc-message)
        (do
          (swap! pending-client-requests-atom
                 assoc-in [id :ts] (u/now-millis))
          (send-message jsonrpc-message))
        ;; response (to server)
        (jr/jsonrpc-response? jsonrpc-message)
        (do
          (swap! pending-server-requests-atom
                 dissoc id)
          (send-message jsonrpc-message))
        ;; notification
        :else
        (send-message jsonrpc-message))
      (send-message jsonrpc-message))))


(defn on-message-received-from-server
  [{:keys [pending-client-requests-atom  ; {<req-id> {:ts <ms> :callback <fn>}}
           pending-server-requests-atom  ; {<req-id> {:ts <ms>}}
           session-context-atom
           client-context-atom
           on-request
           on-success
           on-failure
           on-notification]
    :as _context}
   jsonrpc-message]
  (uab/may-await [jsonrpc-message jsonrpc-message]
    (let [client-context (deref client-context-atom)
          jsonrpc-message-with-deps (-> jsonrpc-message
                                        (rt/copy-runtime client-context)
                                        (rt/?client-context client-context))]
      (if-let [id (:id jsonrpc-message)]
        (cond
          ;; request (from server)
          (jr/jsonrpc-request? jsonrpc-message)
          (do
            (swap! pending-server-requests-atom
                   assoc-in [id :ts] (u/now-millis))
            (try
              (uab/may-await [response (on-request jsonrpc-message-with-deps)]
                (rs/log-outgoing-jsonrpc-response client-context
                                                  response)
                (send-message-to-server client-context response))
              (finally
                (swap! pending-server-requests-atom
                       dissoc id))))
          ;; response (from server)
          (jr/jsonrpc-response? jsonrpc-message)
          (let [callback (get-in @pending-client-requests-atom
                                 [id :callback])]
            (swap! pending-client-requests-atom
                   dissoc id)
            (if (some? callback)  ; found a registered callback?
              (callback jsonrpc-message)
              (if (jr/jsonrpc-error? jsonrpc-message)
                (on-failure jsonrpc-message)
                (on-success jsonrpc-message))))
          ;; notification
          :else
          (on-notification jsonrpc-message-with-deps))
        (on-notification jsonrpc-message-with-deps)))))


(defn make-base-client-context
  "Make base client context from the given handler options:
   :jsonrpc-handler (fn [jsonrpc-message]) - used as fallback handler
   :on-request (fn [jsonrpc-request]) - called upon receiving a request
   :on-success (fn [jsonrpc-success-response]) - on successful response
   :on-failure (fn [jsonrpc-failure-response]) - on failure response
   :on-notification (fn [jsonrpc-notification]) - on notification
   The default implementation only prints them out."
  [{:keys [jsonrpc-handler]
    :as options}]
  (u/expected! jsonrpc-handler fn? ":jsonrpc-handler to be a function")
  (let [{:keys [on-request
                on-success
                on-failure
                on-notification]
         :or {on-request jsonrpc-handler
              on-success (fn [jsonrpc-message]
                           (u/eprintln "[JSON-RPC Received Response-Success]"
                                       jsonrpc-message))
              on-failure (fn [jsonrpc-message]
                           (u/eprintln "[JSON-RPC Received Response-Failure]"
                                       jsonrpc-message))
              on-notification jsonrpc-handler}} options
        pending-client-requests-atom (atom {})
        pending-server-requests-atom (atom {})
        session-context-atom (atom {})
        client-context-atom (atom nil)]
    {:capabilities cap/default-client-capabilities
     :pending-client-requests-atom pending-client-requests-atom
     :pending-server-requests-atom pending-server-requests-atom
     :send-message (fn [jsonrpc-message]
                     (u/eprintln "[JSON-RPC Sending Message]" jsonrpc-message))
     :on-message (fn [jsonrpc-message]
                   (on-message-received-from-server
                    {:pending-client-requests-atom pending-client-requests-atom
                     :pending-server-requests-atom pending-server-requests-atom
                     :session-context-atom session-context-atom
                     :client-context-atom client-context-atom
                     :on-request on-request
                     :on-success on-success
                     :on-failure on-failure
                     :on-notification on-notification}
                    jsonrpc-message))
     :session-context-atom session-context-atom
     :client-context-atom client-context-atom  ; forward self reference
     :transport nil}))


(defn send-request-to-server
  [client message callback]
  (u/expected! (:id message) some? "message :id to be string or integer")
  ;; register the callback
  (swap! (:pending-client-requests-atom client)
         assoc-in [(:id message) :callback] callback)
  ;; send the message
  (rs/log-outgoing-jsonrpc-request client message)
  (send-message-to-server client message))


(defn send-notification-to-server
  [client message]
  ;; send the message
  (rs/log-outgoing-jsonrpc-notification client message)
  (send-message-to-server client message))


(defn wrap-transport
  [client-context transport]
  (-> client-context
      (assoc :transport transport
             :send-message (fn [jsonrpc-message]
                             (p/send-message-to-server transport
                                                       jsonrpc-message)))))


(defn error-logger
  ([id error]
   (u/eprintln "[JSON-RPC Error] [ID:" id "]" error))
  ([jsonrpc-message]
   (if (jr/jsonrpc-error? jsonrpc-message)
     (error-logger (:id jsonrpc-message)
                   (:error jsonrpc-message))
     (u/dprint "Unexpected JSON-RPC Message" jsonrpc-message))))


(defn destructure-result
  "Given a (fn [result-val]) `f` meant to be invoked with the value of
   given result key `k`, return a (fn [jsonrpc-result]) that accepts a
   result-map and calls `f` with the value of `k`."
  [f k]
  (fn [result]
    (if (contains? result k)
      (let [v (get result k)]
        (f v))
      (u/expected! result (str "result to have key " k)))))


(defn on-result-callback
  ([on-result fallback]
   (fn jsonrpc-result-callback [jsonrpc-message]
     (if (jr/jsonrpc-result? jsonrpc-message)
       (on-result (:result jsonrpc-message))
       (fallback jsonrpc-message))))
  ([on-result]
   (on-result-callback on-result error-logger)))


(defn wrap-session-setting
  [callback on-session-context]
  (fn session-setting-callback [jsonrpc-message]
    (when-let [session-context (->> jsonrpc-message
                                    get-message-session-context
                                    (u/only-when seq))]
      (on-session-context session-context))
    (callback jsonrpc-message)))


(defn make-client-jsonrpc-message-handler
  "Create a function `(fn [jsonrpc-message])->jsonrpc-return-value` to
   accept and handle JSON-RPC messages (request/notification/response)
   for the client."
  [{:keys [request-methods-wrapper
           notification-method-handlers
           default-notification-handler]
    :or {request-methods-wrapper identity
         notification-method-handlers is/client-received-notification-handlers
         default-notification-handler u/nop}}]
  (let [request-handler (-> is/mcp-client-methods
                            request-methods-wrapper
                            is/make-dispatching-jsonrpc-request-handler)
        notification-handler (is/make-dispatching-jsonrpc-notification-handler
                              notification-method-handlers
                              default-notification-handler)]
    (is/make-jsonrpc-message-handler request-handler
                                     notification-handler)))


(defn make-client-options
  "Make client options from given input map, returning an output map:
   | Keyword-option          | Default | Description                        |
   |-------------------------|---------|------------------------------------|
   |:info                    |         | see p.c.a.entity-support/make-info |
   |:capabilities            | Default | Given/made from :primitives        |
   |:primitives              | --      | Given/made from :vars              |
   |:vars                    | --      | To make primitives                 |
   |:traffic-logger          | No-op   | MCP transport traffic logger       |
   |:runtime                 | --      | Made from :impl,:capabilities,:tr..|
   |:mcp-methods-wrapper     | No-op   | Wraper for MCP-methods impl        |
   |:jsonrpc-handler         | --      | Made from impl and options         |

   Option kwargs when JSON-RPC handler is constructed:
   | Keyword option              | Default | Description                      |
   |-----------------------------|---------|----------------------------------|
   |:request-methods-wrapper     | No-op   | MCP request-methods impl wrapper |
   |:default-notification-handler| No-op   | Notif handler (fn [notif-msg])   |

   The returned output map contains the following keys:
   :runtime          Server runtime map
   :jsonrpc-handler  JSON-RPC handler fn"
  [{:keys [^{:see [es/make-info]}
           info
           capabilities
           primitives
           vars
           traffic-logger
           runtime
           jsonrpc-handler
           mcp-methods-wrapper]
    :or {traffic-logger stl/compact-client-traffic-logger
         mcp-methods-wrapper identity}
    :as client-options}]
  (let [get-primitives (fn []
                         (or primitives
                             (some-> vars
                                     (vs/vars->client-primitives client-options))
                             nil))
        get-capabilities (fn []
                           (or capabilities
                               (some-> (get-primitives)
                                       vs/primitives->client-capabilities)
                               cap/default-client-capabilities))
        get-runtime (fn []
                      (or runtime
                          (-> {}
                              (cond-> info (rt/?client-info info))
                              (rt/?client-capabilities (get-capabilities))
                              (rt/?traffic-logger traffic-logger)
                              (rt/get-runtime))))
        get-jsonrpc-handler (fn []
                              (or jsonrpc-handler
                                  (make-client-jsonrpc-message-handler
                                   (assoc client-options
                                          :request-methods-wrapper
                                          mcp-methods-wrapper))))]
    (-> client-options
        (merge {:runtime (get-runtime)
                :jsonrpc-handler (get-jsonrpc-handler)}))))
