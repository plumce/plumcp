;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.client-support
  (:require
   [plumcp.core.api.capability :as cs]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.impl.impl-support :as is]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.key-lookup :as kl])
  #?(:cljs (:require-macros [plumcp.core.client.client-support
                             :refer [defcckey]])))


;; ----- Client-context keys -----


(kl/defkey ?capabilities {})
(kl/defkey ?transport {})
(kl/defkey ?send-message {})
(kl/defkey ?on-message {})
(kl/defkey ?client-cache {})  ; English word meaning, is (atom <map>)
(kl/defkey ?run-list-notifier {:default nil})
(kl/defkey ?run-heartbeat-chk {:default nil})
(kl/defkey ?cache-primitives? {:default true})


;; ----- Client K/V cache -----


(defmacro defcckey
  "Define a client-cache key (fn) that accesses K/V pairs in an atom
   holding a map. This macro is a derivate of `defkey`."
  ([fn-name options]
   (assert (symbol? fn-name) "Fn name should be a symbol")
   (assert (nil? (namespace fn-name)) "Fn name symbol should have no namespace")
   (assert (map? options) "Options must be a map")
   `(kl/defkey ~fn-name ~(-> {:get (symbol #'kl/?atom-get)
                              :assoc (symbol #'kl/?atom-assoc)
                              :update (symbol #'kl/?atom-update)}
                             (merge options))))
  ([fn-name doc options]
   `(defcckey ~fn-name ~(assoc options :doc doc))))


(defcckey ?cc-client-context {;; Circular reference, so store as thunk
                              ;; Else printing throws StackOverflowError
                              :get kl/?atom-get-invoke
                              :assoc kl/?atom-assoc-thunk
                              :update kl/?atom-update-thunk})
(defcckey ?cc-initialize-result {:default nil})
(defcckey ?cc-session-context {:default {}})
(defcckey ?cc-prompts-list {:default nil})
(defcckey ?cc-resources-list {:default nil})
(defcckey ?cc-resource-templates-list {:default nil})
(defcckey ?cc-tools-list {:default nil})
(defcckey ?cc-pending-client-requests {:default {}}) ; {<req-id> {:ts <ms> :callback <fn> :progress {}}}
(defcckey ?cc-pending-server-requests {:default {}}) ; {<req-id> {:ts <ms>}}
(defcckey ?cc-listnotif-worker {:default nil})
(defcckey ?cc-heartbeat-worker {:default nil})


(defn init-client-cache-atom
  [client-cache-atom]
  (doto client-cache-atom
    (?cc-pending-client-requests {})
    (?cc-pending-server-requests {})
    (?cc-session-context {})
    (?cc-client-context nil)))


(defn make-client-cache-atom
  []
  (doto (atom {})
    (init-client-cache-atom)))


;; ----- Heartbeat running -----


(defn run-heartbeat
  "Run a heartbeat system between client and server to keep connection
   alive until disconnected. Sends a ping to the server in a loop."
  [client ping ^long seconds condition-fn]
  (let [millis (* 1000 seconds)
        loop? (volatile! true)
        check (fn thisfn []
                (when (and (deref loop?)
                           (condition-fn))
                  (uab/let-await [_ (ping client)]
                    #?(:cljs (js/setTimeout thisfn
                                            millis)
                       :clj (do
                              (Thread/sleep millis)
                              (recur))))))]
    (u/background
      {:delay-millis millis}
      (check))
    (reify p/IStoppable
      (stop! [_] (vreset! loop? false)))))


;; ----- Client operations -----


(defn get-session-context
  [client]
  (-> (?client-cache client)
      ?cc-session-context))


(defn set-session-context!
  [client new-session-context]
  (-> (?client-cache client)
      (?cc-session-context new-session-context)))


(def key-mcp-session-id
  "Key in JSON-RPC response map to refer to MCP Session ID."
  :mcp-session-id)


(defn assoc-session-context [client jsonrpc-message]
  (-> jsonrpc-message
      (assoc key-mcp-session-id (get-session-context client))))


(defn get-message-session-context [jsonrpc-response]
  (select-keys jsonrpc-response [key-mcp-session-id]))


(defn send-message-to-server
  [client jsonrpc-message]
  (let [client-cache-atom (?client-cache client)
        send-message (?send-message client)
        jsonrpc-message (->> (?cc-session-context client-cache-atom)
                             (merge jsonrpc-message))]
    (if-let [id (:id jsonrpc-message)]
      (cond
        ;; request (to server)
        (jr/jsonrpc-request? jsonrpc-message)
        (do
          (?cc-pending-client-requests client-cache-atom
                                       assoc-in [id :ts] (u/now-millis))
          (send-message jsonrpc-message))
        ;; response (to server)
        (jr/jsonrpc-response? jsonrpc-message)
        (do
          (?cc-pending-server-requests client-cache-atom
                                       dissoc id)
          (send-message jsonrpc-message))
        ;; notification
        :else
        (send-message jsonrpc-message))
      (send-message jsonrpc-message))))


(defn on-message-received-from-server
  [{:keys [client-cache-atom
           on-request
           on-success
           on-failure
           on-notification]}
   jsonrpc-message]
  (uab/may-await [jsonrpc-message jsonrpc-message]
    (let [client-context (?cc-client-context client-cache-atom)
          jsonrpc-message-with-deps (-> jsonrpc-message
                                        (rt/copy-runtime client-context)
                                        (rt/?client-context client-context))]
      (if-let [id (:id jsonrpc-message)]
        (cond
          ;; request (from server)
          (jr/jsonrpc-request? jsonrpc-message)
          (do
            (?cc-pending-server-requests client-cache-atom
                                         assoc-in [id :ts] (u/now-millis))
            (try
              (uab/may-await [response (on-request jsonrpc-message-with-deps)]
                (rs/log-outgoing-jsonrpc-response client-context
                                                  response)
                (send-message-to-server client-context response))
              (finally
                (?cc-pending-server-requests client-cache-atom
                                             dissoc id))))
          ;; response (from server)
          (jr/jsonrpc-response? jsonrpc-message)
          (let [callback (-> (?cc-pending-client-requests client-cache-atom)
                             (get-in [id :callback]))]
            (?cc-pending-client-requests client-cache-atom
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
        client-cache-atom (make-client-cache-atom)]
    (-> {}
        (?capabilities ic/default-client-capabilities)
        (?send-message (fn [jsonrpc-message]
                         (u/eprintln "[Dummy:JSON-RPC Sending Message]"
                                     jsonrpc-message)))
        (?client-cache client-cache-atom)
        (?on-message (fn [jsonrpc-message]
                       (on-message-received-from-server
                        {:client-cache-atom client-cache-atom
                         :on-request on-request
                         :on-success on-success
                         :on-failure on-failure
                         :on-notification on-notification}
                        jsonrpc-message)))
        (?transport nil))))


(defn send-request-to-server
  [client message callback]
  (u/expected! (:id message) some? "message :id to be string or integer")
  ;; register the callback
  (-> (?client-cache client)
      (?cc-pending-client-requests assoc-in
                                   [(:id message) :callback] callback))
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
      (?transport transport)
      (?send-message (fn [jsonrpc-message]
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


(defn wrap-session-setting
  [callback on-session-context]
  (fn session-setting-callback [jsonrpc-message]
    (when-let [session-context (->> jsonrpc-message
                                    get-message-session-context
                                    (u/only-when seq))]
      (on-session-context session-context))
    (callback jsonrpc-message)))


;; --- JSON-RPC Response-handling utility ---


;; Print to STDERR


(defn on-error-print
  "Given the :error strucure of an JSON-RPC error response, print the
   error to STDERR."
  ([client-op-name request-id jsonrpc-error]
   (-> "[Op=%s, ID=%s] Client operation error:"
       (format client-op-name request-id)
       (u/eprintln jsonrpc-error)))
  ([request-id jsonrpc-error]
   (-> "[ID=%s] Client operation error:"
       (format request-id)
       (u/eprintln jsonrpc-error))))


(defn on-timeout-print
  "Print a client-operation timeout error message to STDERR."
  ([client-op-name request-id _]
   (-> "[Op=%s, ID=%s] Client operation timed out"
       (format client-op-name request-id)
       u/eprintln))
  ([request-id _]
   (-> "[ID=%s] Client operation timed out"
       (format request-id)
       u/eprintln)))


(defn on-unknown-print
  "Print error message, because unknown response is passed, to STDERR."
  ([client-op-name request-id unknown-response]
   (-> "[Op=%s, ID=%s] Unknwon response in client operation:"
       (format client-op-name request-id)
       (u/eprintln unknown-response)))
  ([request-id unknown-response]
   (-> "[ID=%s] Unknwon response in client operation:"
       (format request-id)
       (u/eprintln unknown-response))))


(defn on-jsonrpc-response-error-print
  "Options to use when you want to simply print error."
  ([client-op-name]
   {:on-error (partial on-error-print client-op-name)
    :on-timeout (partial on-timeout-print client-op-name)
    :on-unknown (partial on-unknown-print client-op-name)})
  ([]
   {:on-error on-error-print
    :on-timeout on-timeout-print
    :on-unknown on-unknown-print}))


;; Throw exception


(defn on-error-throw!
  "Given the :error strucure of an JSON-RPC error response, throw an
   exception."
  ([client-op-name request-id {:keys [code message data]}]
   (u/throw! (-> "Client operation %s (ID=%s) error: "
                 (format client-op-name request-id)
                 (str message))
             (merge {:error-code code
                     :id request-id}
                    data)))
  ([request-id {:keys [code message data]}]
   (u/throw! (-> "Client operation (ID=%s) error: "
                 (format request-id)
                 (str message))
             (merge {:error-code code
                     :id request-id}
                    data))))


(defn on-timeout-throw!
  "Throw a client-operation timeout exception."
  ([client-op-name request-id _]
   (u/throw! (-> "Client operation %s (ID=%s) timed out"
                 (format client-op-name request-id))
             {:id request-id}))
  ([request-id _]
   (u/throw! (-> "Client operation (ID=%s) timed out"
                 (format request-id))
             {:id request-id})))


(defn on-unknown-throw!
  "Throw exception because an unknown response is passed."
  ([client-op-name request-id unknown-response]
   (-> "Unknwon JSON-RPC response in client operation (Op=%s, ID=%s)"
       (format client-op-name request-id)
       (u/throw! {:unknown-response unknown-response
                  :id request-id})))
  ([request-id unknown-response]
   (-> "Unknwon JSON-RPC response in client operation (ID=%s)"
       (format request-id)
       (u/throw! {:unknown-response unknown-response
                  :id request-id}))))


(defn on-jsonrpc-response-error-throw!
  "Options to use when you want to throw exceptions on error."
  ([client-op-name]
   {:on-error (partial on-error-throw! client-op-name)
    :on-timeout (partial on-timeout-throw! client-op-name)
    :on-unknown (partial on-unknown-throw! client-op-name)})
  ([]
   {:on-error on-error-throw!
    :on-timeout on-timeout-throw!
    :on-unknown on-unknown-throw!}))


;; On JSON-RPC response


(defn on-jsonrpc-response
  "Process JSON-RPC response to derive the final output.
   :on-result     - (fn [result])
   :on-error      - (fn [id jsonrpc-error])
   :timeout-value - same value that you pass to `uab/as-async`
   :on-timeout    - (fn [id timeout-value])
   :on-unknown    - (fn [id unknown-response])"
  [^{:see [sd/JSONRPCResponse]} async-jsonrpc-response
   client-op-name
   & ^{:see [on-jsonrpc-response-error-throw!]}
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
      jr/jsonrpc-error? (->> jsonrpc-response
                             jr/jsonrpc-error
                             (on-error (:id jsonrpc-response)))
      #(= % timeout-value) (-> (:id jsonrpc-response)
                               (on-timeout jsonrpc-response))
      (-> (:id jsonrpc-response)
          (on-unknown jsonrpc-response)))))


;; --- Client utility functions ---


(defn ^{:see [sd/InitializeResult]} set-initialize-result!
  "Set the initialization result from the server."
  [client init-result]
  (-> (?client-cache client)
      (?cc-initialize-result init-result)))


(defn notify-initialized
  "Notify the MCP server of a successful initialization.
   See: `initialize-and-notify!`"
  [client]
  (let [notification (eg/make-initialized-notification)]
    (send-notification-to-server client notification)
    (p/upon-handshake-success (?transport client)
                              (get-session-context client))
    (let [client-cache-atom (?client-cache client)]
      ;; run list-change notifier for this connection
      (when-let [run-list-notifier (?run-list-notifier client)]
        (->> (run-list-notifier client)
             (?cc-listnotif-worker client-cache-atom)))
      ;; run heartbeat check for this connection
      (when-let [run-heartbeat-chk (?run-heartbeat-chk client)]
        (->> (run-heartbeat-chk client)
             (?cc-heartbeat-worker client-cache-atom))))))


;; --- ASYNC client functions ---


;; ASYNC result/response utility


(defn on-result->on-response
  "Make a JSON-RPC response handler from given JSON-RPC result handler
   (and JSON-RPC error handler)."
  ([on-result on-error-response]
   (fn jsonrpc-result-callback [jsonrpc-message]
     (if (jr/jsonrpc-result? jsonrpc-message)
       (on-result (:result jsonrpc-message))
       (on-error-response jsonrpc-message))))
  ([on-result]
   (on-result->on-response on-result error-logger)))


;; ASYNC initilization / de-initialization / handshake


(defn async-initialize!
  "Send initialize request to the MCP server, and setup a session on
   success. The JSON-RPC response is passed to `on-jsonrpc-response`.
   See: `initialize-and-notify!`"
  [client ^{:see [sd/JSONRPCResponse
                  sd/InitializeResult
                  sd/JSONRPCError
                  on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-initialize-request
                 sd/protocol-version-max
                 (-> (?capabilities client)
                     ic/get-client-capability-declaration)
                 (rt/?client-info client))
        setter (partial set-session-context! client)]
    (as-> on-jsonrpc-response $
      (wrap-session-setting $ setter)
      (send-request-to-server client request $))))


(defn async-initialize-and-notify!
  "Send initialize request to the MCP server and on success, setup a
   session and notify the MCP server of a successful initialization
   after caching the initialize result."
  [client]
  (async-initialize! client
                     (-> (fn [result]
                           (set-initialize-result! client result)
                           (notify-initialized client))
                         on-result->on-response)))


;; ASYNC MCP requests expecting result


(defn on-tools->on-result
  "Given a (fn [tools]) return (fn [jsonrpc-result])."
  [f]
  (destructure-result f sd/result-key-tools))


(defn async-list-tools
  "Fetch the list of MCP tools supported by the server. The JSON-RPC
   response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListToolsResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-tools->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-tools-request)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn async-call-tool
  "Call the MCP tool on the server. Arguments:
   `tool-name`  is the name of the tool to be called
   `tool-args` is the map of args for calling the tool
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client tool-name tool-args
   ^{:see [sd/JSONRPCResponse
           sd/CallToolResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-call-tool-request tool-name
                                           tool-args)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn on-resources->on-result
  "Given a (fn [resources]) return (fn [jsonrpc-result])."
  [f]
  (destructure-result f sd/result-key-resources))


(defn async-list-resources
  "Return the list of MCP resources supported by the server. The
   JSON-RPC response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListResourcesResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-resources->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-resources-request)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn on-resource-templates->on-result
  "Given a (fn [resource-templates]) return (fn [jsonrpc-result])."
  [f]
  (destructure-result f sd/result-key-resource-templates))


(defn async-list-resource-templates
  "Return the list of MCP resource templates supported by the server.
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListResourceTemplatesResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-resource-templates->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-resource-templates-request)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn async-read-resource
  "Read the resource identified by the URI on the server. Arguments:
   `resource-uri` is the resource URI
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client resource-uri
   ^{:see [sd/JSONRPCResponse
           sd/ReadResourceResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-read-resource-request resource-uri)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn on-prompts->on-result
  "Given a (fn [prompts]) return (fn [jsonrpc-result])."
  [f]
  (destructure-result f sd/result-key-prompts))


(defn async-list-prompts
  "List the MCP prompts supported by the server. The JSON-RPC response
   is passed to `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/ListPromptsResult
                  sd/JSONRPCError
                  on-result->on-response
                  on-prompts->on-result]} on-jsonrpc-response]
  (let [request (eg/make-list-prompts-request)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn async-get-prompt
  "Get the MCP prompt identified by name on the server. Arguments:
   `prompt-or-template-name` is prompt or template name
   `prompt-args` is the map of prompt/template args
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client prompt-or-template-name prompt-args
   ^{:see [sd/JSONRPCResponse
           sd/GetPromptResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-get-prompt-request prompt-or-template-name
                                            {:args prompt-args})]
    (send-request-to-server client request
                            on-jsonrpc-response)))


(defn async-complete
  "Send a completion request to the server. Arguments:
   `complete-request` is the completion request
   `on-success-result` is called with success result
   `on-error-response` is called with error response
   The JSON-RPC response is passed to `on-jsonrpc-response`."
  [client ^{:see [eg/make-complete-request]} complete-request
   ^{:see [sd/JSONRPCResponse
           sd/CompleteResult
           sd/JSONRPCError
           on-result->on-response]} on-jsonrpc-response]
  (send-request-to-server client complete-request
                          on-jsonrpc-response))


(defn async-ping
  "Send a ping request to the server. The JSON-RPC response is passed to
   `on-jsonrpc-response`."
  [client ^{:see [sd/JSONRPCResponse
                  sd/JSONRPCError
                  on-result->on-response]} on-jsonrpc-response]
  (let [request (eg/make-ping-request)]
    (send-request-to-server client request
                            on-jsonrpc-response)))


;; --- Synchronous client functions ---


;; Utilities


(defn get-from-cache
  "Apply getter to the client cache."
  [client getter]
  (-> (?client-cache client)
      getter))


(defn set-into-cache
  "Apply setter to client-cache and payload when caching is enabled."
  [payload client setter]
  (when (-> client
            ?cache-primitives?)  ; is caching primitives allowed?
    (-> (?client-cache client)
        (setter payload))))


;; Low level functions to send client request and obtain result


(defn request->response
  "Given an MCP (JSON-RPC) request, send it to the server and return the
   MCP (JSON-RPC) response as a value in CLJ, or as js/Promise in CLJS.
   Options:
   - see `plumcp.core.util.async-bridge/as-async`"
  ^{:see [on-jsonrpc-response]}
  [request client & options]
  (uab/as-async
    [return]
    options
    (send-request-to-server client request
                            return)))


(defn response->result-or-nil
  "Given an MCP (JSON-RPC) response (value in CLJ, js/Promise in CLJS)
   extract/return result on success, print error/return nil otherwise.
   Return a value in CLJ, or a js/Promise in CLJS."
  [jsonrpc-response client-op-name & options]
  (as-> (on-jsonrpc-response-error-print client-op-name) $
    (merge $ options)
    (on-jsonrpc-response jsonrpc-response client-op-name $)))


(defn response->result-or-throw!
  "Given an MCP (JSON-RPC) response (value in CLJ, js/Promise in CLJS)
   extract/return result on success, throw error otherwise.
   Return a value in CLJ, or a js/Promise in CLJS.
   Options:
   - see on-jsonrpc-response"
  [jsonrpc-response client-op-name & options]
  (as-> (on-jsonrpc-response-error-throw! client-op-name) $
    (merge $ options)
    (on-jsonrpc-response jsonrpc-response client-op-name $)))


;; Operations


(defn ^{:see [sd/JSONRPCResponse
              sd/InitializeResult
              sd/JSONRPCError
              async-initialize!
              on-jsonrpc-response
              on-jsonrpc-response-error-throw!]} caching-initialize!
  "Send initialize request to the MCP server and setup a session
   returning initialize result (value in CLJ, js/Promise in CLJS) on
   success, nil on error (printed to STDERR). Initialize result is
   cached is caching is enabled.
   Options:
   - see `plumcp.core.util.async-bridge/as-async`
   - see `on-jsonrpc-response`
   - kwarg `:on-result` is ignored"
  [client & ^{:see [uab/as-async
                    on-jsonrpc-response]} {:as options}]
  (-> (uab/as-async
        [return]
        options
        (async-initialize! client
                           return))
      (on-jsonrpc-response "initialize"
                           (-> options
                               (dissoc :on-result)))))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListPromptsResult
              sd/JSONRPCError
              on-jsonrpc-response
              on-jsonrpc-response-error-throw!]} caching-list-prompts
  "Fetch (from server) and return the list of MCP prompts (value in CLJ,
   js/Promise in CLJS) supported by the server on success, nil on error
   (printed to STDERR). On success, prompts list is cached if caching is
   enabled.
   Options:
   - see `plumcp.core.util.async-bridge/as-async`
   - see `on-jsonrpc-response`"
  [client
   & ^{:see [uab/as-async
             on-jsonrpc-response]} {:keys [on-result]
                                    :or {on-result sd/result-key-prompts}
                                    :as options}]
  (let [result-handler (fn [result]
                         (->> ?cc-prompts-list
                              (set-into-cache result client))
                         (on-result result))]
    (-> (uab/as-async
          [return]
          options
          (async-list-prompts client
                              return))
        (on-jsonrpc-response "list-prompts"
                             (-> options
                                 (assoc :on-result result-handler))))))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourcesResult
              sd/JSONRPCError
              on-jsonrpc-response
              on-jsonrpc-response-error-throw!]} caching-list-resources
  "Fetch (from server) and return the list of MCP resources (value in
   CLJ, js/Promise in CLJS) supported by the server on success, nil on
   error (printed to STDERR). On success, resources list is cached if
   caching is enabled.
   Options:
   - see `plumcp.core.util.async-bridge/as-async`
   - see `on-jsonrpc-response`"
  [client
   & ^{:see [uab/as-async
             on-jsonrpc-response]} {:keys [on-result]
                                    :or {on-result sd/result-key-resources}
                                    :as options}]
  (let [result-handler (fn [result]
                         (->> ?cc-resources-list
                              (set-into-cache result client))
                         (on-result result))]
    (-> (uab/as-async
          [return]
          options
          (async-list-resources client
                                return))
        (on-jsonrpc-response "list-resources"
                             (-> options
                                 (assoc :on-result result-handler))))))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListResourceTemplatesResult
              sd/JSONRPCError
              on-jsonrpc-response
              on-jsonrpc-response-error-throw!]} caching-list-resource-templates
  "Fetch (from server) and return the list of MCP resource templates
   (value in CLJ, js/Promise in CLJS) supported by the server on success,
   nil on error (printed to STDERR). On success, resource templates list
   is cached if caching is enabled.
   Options:
   - see `plumcp.core.util.async-bridge/as-async`
   - see `on-jsonrpc-response`"
  [client
   & ^{:see [uab/as-async
             on-jsonrpc-response]} {:keys [on-result]
                                    :or {on-result
                                         sd/result-key-resource-templates}
                                    :as options}]
  (let [result-handler (fn [result]
                         (->> ?cc-resource-templates-list
                              (set-into-cache result client))
                         (on-result result))]
    (-> (uab/as-async
          [return]
          options
          (async-list-resource-templates client
                                         return))
        (on-jsonrpc-response "list-resource-templates"
                             (-> options
                                 (assoc :on-result result-handler))))))


(defn ^{:see [sd/JSONRPCResponse
              sd/ListToolsResult
              sd/JSONRPCError
              on-jsonrpc-response
              on-jsonrpc-response-error-throw!]} caching-list-tools
  "Fetch (from server) and return the list of MCP tools (value in CLJ,
   js/Promise in CLJS) supported by the server on success, nil on error
   (printed to STDERR). On success, tools list is cached if caching is
   enabled.
   Options:
   - see `plumcp.core.util.async-bridge/as-async`
   - see `on-jsonrpc-response`"
  [client
   & ^{:see [uab/as-async
             on-jsonrpc-response]} {:keys [on-result]
                                    :or {on-result sd/result-key-tools}
                                    :as options}]
  (let [result-handler (fn [result]
                         (->> ?cc-tools-list
                              (set-into-cache result client))
                         (on-result result))]
    (-> (uab/as-async
          [return]
          options
          (async-list-tools client
                            return))
        (on-jsonrpc-response "list-tools"
                             (-> options
                                 (assoc :on-result result-handler))))))


;; --- Notification Handling ---


;; Helper fns


(defn jsonrpc-message-with-deps->client
  "Given a jsonrpc-message with dependencies, extractreturn the client."
  [jsonrpc-message-with-deps]
  (-> (rt/?client-context jsonrpc-message-with-deps)
      ?client-cache
      ?cc-client-context))


(defn fetch-prompts
  "Given a JSON-RPC message with dependencies fetch and return a list of
   prompts (value in CLJ, js/Promise in CLJS). Useful to fetch prompts
   on list-changed notification."
  [jsonrpc-message-with-deps & {:keys [on-prompts]
                                :or {on-prompts identity}
                                :as options}]
  (uab/let-await [prompts (-> jsonrpc-message-with-deps
                              jsonrpc-message-with-deps->client
                              (caching-list-prompts options))]
    (on-prompts prompts)))


(defn fetch-resources
  "Given a JSON-RPC message with dependencies fetch and return a vector
   of [resources resource-templates] (value in CLJ, js/Promise in CLJS).
   Useful to fetch resources and resource-templates on list-changed
   notification."
  [jsonrpc-message-with-deps & {:keys [on-resources
                                       on-resource-templates]
                                :or {on-resources identity
                                     on-resource-templates identity}
                                :as options}]
  (let [client (-> jsonrpc-message-with-deps
                   jsonrpc-message-with-deps->client)]
    (uab/let-await [resources (caching-list-resources client options)
                    templates (caching-list-resource-templates client
                                                               options)]
      [(on-resources resources)
       (on-resource-templates templates)])))


(defn fetch-tools
  "Given a JSON-RPC message with dependencies fetch and return a list of
   tools (value in CLJ, js/Promise in CLJS). Useful to fetch tools on
   list-changed notification."
  [jsonrpc-message-with-deps & {:keys [on-tools]
                                :or {on-tools identity}
                                :as options}]
  (uab/let-await [tools (-> jsonrpc-message-with-deps
                            jsonrpc-message-with-deps->client
                            (caching-list-tools options))]
    (on-tools tools)))


(defn cancel-server-request
  "Cancel server request (to client) based on a CanelledNotification
   received from server."
  [^{:see [sd/CancelledNotification]} jsonrpc-message-with-deps]
  (let [client-cache-atom (-> jsonrpc-message-with-deps
                              jsonrpc-message-with-deps->client
                              ?client-cache)
        id (get-in jsonrpc-message-with-deps [:params :requestId])]
    (?cc-pending-server-requests client-cache-atom
                                 dissoc id)))


(defn update-client-request-progress
  "Update progress of pending client request based on the received
   ProgressNotification."
  [^{:see [sd/ProgressNotification]} jsonrpc-message-with-deps]
  (let [client-cache-atom (-> jsonrpc-message-with-deps
                              jsonrpc-message-with-deps->client
                              ?client-cache)
        id (get-in jsonrpc-message-with-deps [:params :progressToken])
        progress (-> jsonrpc-message-with-deps
                     (get :params)
                     (select-keys [:progress
                                   :total
                                   :message]))]
    ;; There is a minor race condition here between the ID-exists check
    ;; and updating progress, which is worth avoiding orphaned progress
    (when (-> (?cc-pending-client-requests client-cache-atom)
              (contains? id))
      (?cc-pending-client-requests client-cache-atom
                                   update id
                                   assoc :progress progress))))


(def log-levels-upper
  "Upper-case log levels map for lookup."
  {"emergency" "EMERGENCY"
   "alert"     "ALERT"
   "critical"  "CRITICAL"
   "error"     "ERROR"
   "warning"   "WARNING"
   "notice"    "NOTICE"
   "info"      "INFO"
   "debug"     "DEBUG"})


(defn log-message
  "Log server-sent message."
  [^{:see [sd/LoggingMessageNotification]} jsonrpc-message-with-deps]
  (let [{:keys [level logger data]} (:params jsonrpc-message-with-deps)
        upper-level (get log-levels-upper level level)
        message (if (string? data) data (u/pprint-str data))]
    (if (some? logger)
      (-> "[%s][%s] %s"
          (format upper-level logger message)
          u/eprintln)
      (-> "[%s] %s"
          (format upper-level message)
          u/eprintln))))


;; Notification handler map


(def client-notification-handlers
  {;; -- received by both client and server --
   sd/method-notifications-cancelled cancel-server-request
   sd/method-notifications-progress update-client-request-progress
   ;; -- received by client --
   sd/method-notifications-message log-message
   sd/method-notifications-resources-updated u/nop  ; ignore
   ;; list-changed
   sd/method-notifications-prompts-list_changed fetch-prompts
   sd/method-notifications-resources-list_changed fetch-resources
   sd/method-notifications-tools-list_changed fetch-tools})


;; --- Client options making ---


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
   | Keyword-option       |Default| Description                          |
   |----------------------|-------|--------------------------------------|
   |:info                 |       |see p.c.a.entity-support/make-info    |
   |:capabilities         |Default|Given/made from :primitives           |
   |:primitives           |--     |Given/made from :vars                 |
   |:vars                 |--     |To make primitives                    |
   |:traffic-logger       |No-op  |MCP transport traffic logger          |
   |:notification-handlers|{}     |Map notification methodName->handlerFn|
   |:runtime              |--     |Made from :impl,:capabilities,:traff..|
   |:override             |{}     |Merged into final runtime             |
   |:mcp-methods-wrapper  |No-op  |Wraper for MCP-methods impl           |
   |:jsonrpc-handler      |--     |Made from impl and options            |

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
           notification-handlers
           runtime
           override
           jsonrpc-handler
           mcp-methods-wrapper]
    :or {traffic-logger stl/compact-client-traffic-logger
         notification-handlers {}
         override {}
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
                                       cs/primitives->client-capabilities)
                               ic/default-client-capabilities))
        get-runtime (fn []
                      (-> runtime
                          (or (-> {}
                                  (cond-> info (rt/?client-info info))
                                  (rt/?client-capabilities (get-capabilities))
                                  (rt/?traffic-logger traffic-logger)
                                  (rt/?notification-handlers merge
                                                             client-notification-handlers
                                                             notification-handlers)
                                  (rt/get-runtime)))
                          (merge override)))
        get-jsonrpc-handler (fn []
                              (or jsonrpc-handler
                                  (make-client-jsonrpc-message-handler
                                   (assoc client-options
                                          :request-methods-wrapper
                                          mcp-methods-wrapper))))]
    (-> client-options
        (merge {:runtime (get-runtime)
                :jsonrpc-handler (get-jsonrpc-handler)}))))
