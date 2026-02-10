;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.impl.impl-method
  "Implementation of the client and server MCP methods."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


;; --- Utilities ---


(defn copy-deps
  [args jsonrpc-request]
  (-> args
      (rt/copy-runtime jsonrpc-request)
      (rt/?request-id (:id jsonrpc-request))
      (rt/?request-params-meta (jr/request-params-meta
                                jsonrpc-request))))


(defn make-result
  ([result f]
   (uab/may-await [result result]
     (or (u/only-when jr/jsonrpc-response? result)
         (f result))))
  ([result]
   (make-result result #(do {:result %}))))


(defn with-capability
  [context capability-name capability f]
  (if (some? capability)
    ;; until initialized, only logging and ping is allowed
    (if (or (contains? #{"roots" "sampling" "elicitation"}
                       capability-name)  ; client capability?
            (and (= "logging" capability-name)
                 (rt/has-session? context))  ; before initialized notification
            (and (contains? #{"completions" "prompts" "resources" "tools"}
                            capability-name) ; after initialized notification
                 (rs/get-initialized-timestamp context)))
      (try
        (f capability)
        (catch #?(:cljs js/Error
                  :clj Exception) ex
          (rs/log-mcpcall-failure context ex)
          (if-some [[message data] (u/ex-info-parts ex)]
            (do
              (rs/log-3-error context {:message message
                                       :data data})
              (jr/jsonrpc-failure sd/error-code-internal-error
                                  message
                                  data))
            (do
              (rs/log-3-error context (str ex))
              (jr/jsonrpc-failure sd/error-code-internal-error
                                  (str ex))))))
      (if (rt/has-session? context)
        (jr/jsonrpc-failure sd/error-code-invalid-request
                            "Initialization notification not received yet")
        (jr/jsonrpc-failure sd/error-code-invalid-request
                            "Initialization not done yet")))
    (jr/jsonrpc-failure sd/error-code-method-not-found
                        (format "Capability '%s' not supported"
                                capability-name))))


;; --- Client capabilities ---


(defn with-roots-capability [request f]
  (let [roots-capability (-> (rt/?client-capabilities request)
                             (cap/get-capability-roots))]
    (with-capability request "roots" roots-capability f)))


(defn with-sampling-capability [request f]
  (let [sampling-capability (-> (rt/?client-capabilities request)
                                (cap/get-capability-sampling))]
    (with-capability request "sampling" sampling-capability f)))


(defn with-elicitation-capability [request f]
  (let [elicitation-capability (-> (rt/?client-capabilities request)
                                   (cap/get-capability-elicitation))]
    (with-capability request "elicitation" elicitation-capability f)))


;; --- Server capabilities ---


(defn with-logging-capability [request f]
  (let [logging-capability (-> (rt/?server-capabilities request)
                               (cap/get-capability-logging))]
    (with-capability request "logging" logging-capability f)))


(defn with-completions-capability [request f]
  (let [completions-capability (-> (rt/?server-capabilities request)
                                   (cap/get-capability-completions))]
    (with-capability request "completions" completions-capability f)))


(defn with-prompts-capability [request f]
  (let [prompts-capability (-> (rt/?server-capabilities request)
                               (cap/get-capability-prompts))]
    (with-capability request "prompts" prompts-capability f)))


(defn with-resources-capability [request f]
  (let [resources-capability (-> (rt/?server-capabilities request)
                                 (cap/get-capability-resources))]
    (with-capability request "resources" resources-capability f)))


(defn with-tools-capability [request f]
  (let [tools-capability (-> (rt/?server-capabilities request)
                             (cap/get-capability-tools))]
    (with-capability request "tools" tools-capability f)))


;; --- Handshake ---


(defn handshake-error [client-protocol-version]
  (jr/jsonrpc-failure
   sd/error-code-invalid-params
   "Unsupported protocol version"
   {:supported [sd/protocol-versions-supported]
    :requested client-protocol-version}))


(defn ^{:see [sd/InitializeResult
              eg/make-initialize-result]} get-initialize-result
  [jsonrpc-request supported-protocol-version]
  (let [protocol-version supported-protocol-version
        server-capabilities (-> (rt/?server-capabilities jsonrpc-request)
                                (cap/get-server-capability-declaration))
        server-info (rt/?server-info jsonrpc-request)
        server-instructions (rt/?server-instructions jsonrpc-request)]
    (->> (-> {}
             (u/assoc-some :instructions server-instructions))
         (eg/make-initialize-result protocol-version
                                    server-capabilities
                                    server-info))))


(defn find-supported-protocol-version
  "Given peer's highest protocol version, return the highest supported
   MCP protocol version. Return `nil` when there's no match."
  [peer-protocol-version]
  (some #(when (= % peer-protocol-version)
           %)
        sd/protocol-versions-supported))


(defn ^{:see [sd/InitializeRequest
              eg/make-initialize-request]} initialize [{params :params
                                                        :as jsonrpc-request}]
  (let [{client-protocol-version :protocolVersion
         client-capabilities :capabilities
         client-info :clientInfo} params
        ;; server-capabilities @mcp-state/db
        ;; server-info {}
        ;; server-instructions ""
        ]
    (if-some [protocol-version (-> client-protocol-version
                                   (find-supported-protocol-version))]
      (-> jsonrpc-request
          (get-initialize-result protocol-version)
          jr/jsonrpc-success)
      (handshake-error client-protocol-version))))


;; --- Connectivity ---


(defn ^{:see [sd/PingRequest
              eg/make-ping-request]} ping [_jsonrpc-request]
  {:result {}})


;; --- Client capabilities ---


(defn ^{:see [sd/ListRootsRequest
              sd/ListRootsResult
              eg/make-list-roots-request
              eg/make-list-roots-result]} roots-list [jsonrpc-request]
  (with-roots-capability
    jsonrpc-request
    (fn [roots-capability]
      {:result {:roots (p/obtain-list roots-capability
                                      sd/method-roots-list)}})))


(defn ^{:see [sd/CreateMessageRequest
              sd/CreateMessageResult
              eg/make-create-message-request
              eg/make-create-message-result
              p/IMcpSampling]} sampling-createMessage
  [{params :params
    :as jsonrpc-request}]
  (with-sampling-capability
    jsonrpc-request
    (fn [sampling-capability]
      (as-> params $
        (copy-deps $ jsonrpc-request)
        (p/get-sampling-response sampling-capability $)
        (make-result $)))))


(defn ^{:see [sd/ElicitRequest
              sd/ElicitResult
              eg/make-elicit-request
              eg/make-elicit-result
              p/IMcpElicitation]} elicitation-create
  [{params :params
    :as jsonrpc-request}]
  (with-elicitation-capability
    jsonrpc-request
    (fn [elicitation-capability]
      (as-> params $
        (copy-deps $ jsonrpc-request)
        (p/get-elicitation-response elicitation-capability $)
        (make-result $)))))


;; --- Server capabilities ---


(defn ^{:see [sd/CompleteRequest
              sd/CompleteResult
              eg/make-complete-request
              eg/make-complete-result]} completion-complete
  [{params :params
    :as jsonrpc-request}]
  (with-completions-capability
    jsonrpc-request
    (fn [completions-capability]
      (let [{params-ref :ref
             params-argument :argument} params
            coll (p/completion-complete completions-capability
                                        params-ref params-argument)]
        (->> (fn [coll]
               (let [values (->> coll (take 100) vec)
                     other  (->> coll (drop 100) seq)]
                 {:result (u/assoc-some {:values values}
                                        :total   (when (nil? other) (count values))
                                        :hasMore (when (some? other) true))}))
             (make-result coll))))))


(defn ^{:see [sd/ListPromptsRequest
              sd/ListPromptsResult
              eg/make-list-prompts-request
              eg/make-list-prompts-result]} prompts-list
  [jsonrpc-request]
  (with-prompts-capability
    jsonrpc-request
    (fn [prompts-capability]
      {:result {:prompts (p/obtain-list prompts-capability
                                        sd/method-prompts-list)}})))


(defn ^{:see [sd/GetPromptRequest
              sd/GetPromptResult
              sd/PromptMessage
              eg/make-get-prompt-request
              eg/make-get-prompt-result
              eg/make-prompt-message]} prompts-get [{params :params
                                                     :as jsonrpc-request}]
  (with-prompts-capability
    jsonrpc-request
    (fn [prompts-capability]
      (let [{prompt-name :name
             prompt-args :arguments} params]
        (if-let [{:keys [handler]} (p/find-handler prompts-capability
                                                   prompt-name)]
          (-> prompt-args
              (copy-deps jsonrpc-request)
              handler
              make-result)
          (do
            (rs/log-3-error jsonrpc-request
                            {:message "Requested prompt-name does not exist"
                             :prompt-name prompt-name
                             :prompt-args prompt-args})
            (jr/jsonrpc-failure sd/error-code-invalid-params
                                "Requested prompt-name does not exist"
                                {:prompt-name prompt-name
                                 :prompt-args prompt-args})))))))


(defn ^{:see [sd/ListResourcesRequest
              sd/ListResourcesResult
              eg/make-list-resources-request
              eg/make-list-resources-result]} resources-list
  [jsonrpc-request]
  (with-resources-capability
    jsonrpc-request
    (fn [resources-capability]
      {:result {:resources (p/obtain-list resources-capability
                                          sd/method-resources-list)}})))


(defn ^{:see [sd/ReadResourceRequest
              sd/ReadResourceResult
              eg/make-read-resource-request
              eg/make-read-resource-result]} resources-read
  [{params :params
    :as jsonrpc-request}]
  (with-resources-capability
    jsonrpc-request
    (fn [resources-capability]
      (let [uri (:uri params)]
        (if-let [{:keys [handler params]} (p/find-handler resources-capability
                                                          uri)]
          (-> {:uri uri
               :params params}
              (copy-deps jsonrpc-request)
              handler
              make-result)
          (do
            (rs/log-3-error jsonrpc-request
                            {:message "Requested invalid resource URI"
                             :uri uri})
            (jr/jsonrpc-failure sd/error-code-invalid-params
                                "Requested invalid resource URI"
                                {:uri uri})))))))


(defn ^{:see [sd/SubscribeRequest
              eg/make-subscribe-request]} resources-subscribe
  [{params :params
    :as jsonrpc-request}]
  (let [uri (:uri params)]
    (rs/add-subscription jsonrpc-request uri)
    {:result {}}))


(defn ^{:see [sd/UnsubscribeRequest
              eg/make-unsubscribe-request]} resources-unsubscribe
  [{params :params
    :as jsonrpc-request}]
  (let [uri (:uri params)]
    (rs/remove-subscription jsonrpc-request uri)
    {:result {}}))


(defn ^{:see [sd/ListResourceTemplatesRequest
              sd/ListResourceTemplatesResult]} resources-templates-list
  [jsonrpc-request]
  (with-resources-capability
    jsonrpc-request
    (fn [resources-capability]
      {:result {:resourceTemplates
                (p/obtain-list resources-capability
                               sd/method-resources-templates-list)}})))


(defn ^{:see [sd/ListToolsRequest
              sd/ListToolsResult
              eg/make-list-tools-request
              eg/make-list-tools-result]} tools-list [jsonrpc-request]
  (with-tools-capability
    jsonrpc-request
    (fn [tools-capability]
      {:result {:tools (p/obtain-list tools-capability
                                      sd/method-tools-list)}})))


(defn ^{:see [sd/CallToolRequest
              sd/CallToolResult
              eg/make-call-tool-request
              eg/make-call-tool-result]} tools-call
  [{{tool-name :name
     tool-args :arguments
     :as params} :params
    :as jsonrpc-request}]
  (with-tools-capability
    jsonrpc-request
    (fn [tools-capability]
      (if-let [{:keys [handler]} (p/find-handler tools-capability tool-name)]
        (-> tool-args
            (copy-deps jsonrpc-request)
            handler
            make-result)
        (jr/jsonrpc-failure sd/error-code-invalid-params
                            (str "Unrecognized tool: " tool-name)
                            params)))))


(defn ^{:see [sd/SetLevelRequest
              sd/LoggingMessageNotification
              eg/make-set-level-request
              eg/make-logging-message-notification]} logging-setLevel
  [{params :params
    :as jsonrpc-request}]
  (with-logging-capability
    jsonrpc-request
    (fn [logging-capability]
      (let [level (:level params)]
        (rs/set-log-level jsonrpc-request level)
        {:result {}}))))


;; --- Notifications ---


(defn call-notification-listener
  [context method-name params]
  (when-let [listener (rs/get-notification-listener context method-name)]
    (listener params)))


(defn ^{:see [sd/InitializedNotification
              eg/make-initialized-notification]} notifications-initialized
  [{params :params
    :as jsonrpc-notification}]
  (rs/set-initialized-timestamp jsonrpc-notification)
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-initialized params)
  {:result {}})


(defn ^{:see [sd/CancelledNotification
              eg/make-cancellation-notification]} notifications-cancelled
  [{params :params
    :as jsonrpc-notification}]
  (let [request-id (:requestId params)
        reason (:reason params)]
    (when (rt/has-session? jsonrpc-notification)  ; this is true on server
      (rs/request-cancellation jsonrpc-notification request-id)
      (rs/log-7-debug jsonrpc-notification
                      (-> {:message "Cancel requested for task"
                           :request-id request-id}
                          (u/assoc-some :reason reason))))
    (call-notification-listener jsonrpc-notification
                                sd/method-notifications-cancelled params))
  {:result {}})


(defn ^{:see [sd/ProgressNotification
              eg/make-progress-notification]} notifications-progress
  [{progress :params
    :as jsonrpc-notification}]
  (when (rt/has-session? jsonrpc-notification)  ; this is true on server
    (rs/update-peer-progress jsonrpc-notification
                             (:progressToken progress) progress))
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-progress progress)
  {:result {}})


(defn ^{:see [sd/LoggingMessageNotification
              eg/make-logging-message-notification]}
  notifications-message
  [{params :params
    :as jsonrpc-notification}]
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-message params)
  {:result {}})


(defn ^{:see [sd/ResourceListChangedNotification
              eg/make-resource-list-changed-notification]}
  notifications-resources-list_changed
  [{params :params
    :as jsonrpc-notification}]
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-resources-list_changed
                              params)
  {:result {}})


(defn ^{:see [sd/ResourceUpdatedNotification
              eg/make-resource-updated-notification]}
  notifications-resources-updated
  [{params :params
    :as jsonrpc-notification}]
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-resources-updated
                              params)
  {:result {}})


(defn ^{:see [sd/PromptListChangedNotification
              eg/make-prompt-list-changed-notification]}
  notifications-prompts-list_changed
  [{params :params
    :as jsonrpc-notification}]
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-prompts-list_changed
                              params)
  {:result {}})


(defn ^{:see [sd/ToolListChangedNotification
              eg/make-tool-list-changed-notification]}
  notifications-tools-list_changed
  [{params :params
    :as jsonrpc-notification}]
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-tools-list_changed
                              params)
  {:result {}})


(defn ^{:see [sd/RootsListChangedNotification
              eg/make-roots-list-changed-notification]}
  notifications-roots-list_changed
  [{params :params
    :as jsonrpc-notification}]
  (call-notification-listener jsonrpc-notification
                              sd/method-notifications-roots-list_changed
                              params)
  {:result {}})
