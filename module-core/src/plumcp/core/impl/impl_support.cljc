;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.impl.impl-support
  "Metadata and segregation of MCP server methods implementation."
  (:require
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.impl-method :as im]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


;; ----- Notifications -----


(def common-bidirectional-notification-handlers
  {sd/method-notifications-cancelled im/notifications-cancelled
   sd/method-notifications-progress  im/notifications-progress})


(def server-received-notification-handlers
  (-> {;; post-initialization notification
       sd/method-notifications-initialized im/notifications-initialized
       ;; roots-list changes
       sd/method-notifications-roots-list_changed
       im/notifications-roots-list_changed}
      (merge common-bidirectional-notification-handlers)))


(def client-received-notification-handlers
  (-> {;; resources
       sd/method-notifications-resources-list_changed
       im/notifications-resources-list_changed
       sd/method-notifications-resources-updated
       im/notifications-resources-updated
       ;; prompts
       sd/method-notifications-prompts-list_changed
       im/notifications-prompts-list_changed
       ;; tools-list changed
       sd/method-notifications-tools-list_changed
       im/notifications-tools-list_changed
       ;; log messages
       sd/method-notifications-message im/notifications-message}
      (merge common-bidirectional-notification-handlers)))


;; ----- Server -----


(def mcp-server-methods-session-less
  "Map of session-less MCP method-name to its implementation function."
  {;; common
   sd/method-ping       im/ping
   ;; server-specific
   sd/method-initialize im/initialize})


(def mcp-server-methods-with-session
  "Map of post-initialization MCP method-name (needing a session) to its
   implementation function."
  (-> {;; requests to server
       sd/method-completion-complete       im/completion-complete
       sd/method-prompts-list              im/prompts-list
       sd/method-prompts-get               im/prompts-get
       sd/method-resources-list            im/resources-list
       sd/method-resources-read            im/resources-read
       sd/method-resources-subscribe       im/resources-subscribe
       sd/method-resources-unsubscribe     im/resources-unsubscribe
       sd/method-tools-list                im/tools-list
       sd/method-logging-setLevel          im/logging-setLevel
       sd/method-resources-templates-list  im/resources-templates-list
       sd/method-tools-call                im/tools-call}
      (merge server-received-notification-handlers)
      ;; wrap the method impls with session check/propagation
      (update-vals (fn [handler]
                     (->> "Session is missing"
                          (jr/jsonrpc-failure sd/error-code-invalid-request)
                          (rt/wrap-session-required handler))))))


(def mcp-server-methods
  "Map of server MCP method-name to its implementation function."
  (-> mcp-server-methods-session-less
      (merge mcp-server-methods-with-session)))


;; ----- Client -----


(def mcp-client-methods
  "Map of Client MCP method-name to its implementation function."
  (-> {;; requests to clients
       sd/method-roots-list im/roots-list
       sd/method-sampling-createMessage im/sampling-createMessage
       sd/method-elicitation-create im/elicitation-create}
      (merge client-received-notification-handlers)))


;; ----- JSON-RPC message handling -----


(defn make-dispatching-jsonrpc-request-handler
  "Given a map `{request-method-name method-handler-fn}` create a
   JSON-RPC request handler fn `(fn [jsonrpc-request])->jsonrpc-response`
   that dispatches control to the correct handler fn based on the
   request method name."
  [request-method-handler-map]
  (fn jsonrpc-request-handler [jsonrpc-request]
    (let [emt "JSON-RPC request method '%s' is either invalid or unimplemented"
          id (:id jsonrpc-request)
          method (:method jsonrpc-request)]
      (rs/log-incoming-jsonrpc-request jsonrpc-request)
      (-> (if-let [f (get request-method-handler-map method)]
            (uab/may-await [retval (f jsonrpc-request)]
              (merge {:jsonrpc sd/jsonrpc-version
                      :id id}
                     retval))
            {:jsonrpc sd/jsonrpc-version
             :id id
             :error {:code sd/error-code-internal-error
                     :message (format emt method)
                     :data {}}})
          (u/dotee (fn [sora-jr-response]  ; SORA: Sync-OR-Async
                     (uab/may-await [jr-response sora-jr-response]
                       (rs/log-outgoing-jsonrpc-response
                        jsonrpc-request jr-response))))))))


(defn make-dispatching-jsonrpc-notification-handler
  "Given a map `{notification-method-name method-handler-fn}` create a
   JSON-RPC notification handler fn `(fn [jsonrpc-notification])->nil`
   that dispatches control to the correct handler fn based on the
   notification method name."
  [notification-method-handler-map default-notification-handler]
  (fn jsonrpc-notification-handler [jsonrpc-notification]
    (let [method (:method jsonrpc-notification)]
      (rs/log-incoming-jsonrpc-notification jsonrpc-notification)
      (let [f (get notification-method-handler-map method
                   default-notification-handler)]
        (uab/may-await [_ (f jsonrpc-notification)]
          nil)))))


(defn make-jsonrpc-message-handler
  "Given JSON-RPC request/notification/response handler fns, create a
   handler fn `(fn [jsonrpc-message])->jsonrpc-return-value` that can
   accept and handle any JSON-RPC message."
  ([request-handler
    notification-handler
    response-handler]
   (fn jsonrpc-message-handler [jsonrpc-message]
     (if (jr/jsonrpc-request-or-notification? jsonrpc-message)
       (if (contains? jsonrpc-message :id)
         (request-handler jsonrpc-message)
         (uab/may-await [_ (notification-handler jsonrpc-message)]
           nil))
       (if (jr/jsonrpc-response? jsonrpc-message)
         (uab/may-await [_ (response-handler jsonrpc-message)]
           nil)
         (->> "JSON-RPC message to be a request, notification or response"
              (u/expected! (rt/dissoc-runtime jsonrpc-message)))))))
  ([request-handler
    notification-handler]
   (->> (fn default-jsonrpc-response-handler [jsonrpc-response]
          (rs/log-incoming-jsonrpc-response jsonrpc-response))
        (make-jsonrpc-message-handler request-handler
                                      notification-handler))))
