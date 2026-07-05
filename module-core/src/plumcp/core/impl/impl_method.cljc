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
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.impl-capability :as ic]
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


(defn ^{:see [sd/TaskAugmentedRequestParams]} task-augmented-request?
  "Return true if given JSON-RPC request is a task-augmented request,
   false otherwise."
  [jsonrpc-request]
  (-> jsonrpc-request
      (get-in [:params :task])
      map?))


(defn wrap-task-augmented
  "Given (fn f [& args]) that produces a JSON-RPC result or response,
   wrap it such that it detects and intercepts task-augmented request and
   responds appropriately."
  [f jsonrpc-request specific-tasks-capability]
  (if-let [task-metadata (get-in jsonrpc-request [:params :task])]
    ;; task augmented, so process like one
    (let [tasks-capability (-> (rs/my-capabilities jsonrpc-request)
                               (ic/get-capability-tasks))]
      (if (-> (p/get-capability-declaration tasks-capability)
              (get-in (vec specific-tasks-capability)))
        ;; specific tasks capability exists
        (fn [& args]
          (let [common-session (rs/my-session jsonrpc-request)
                task-id (u/uuid-v7)
                new-task (->> (assoc task-metadata :task-id task-id)
                              (es/make-working-task))]
            ;; register task
            (p/add-task common-session new-task)
            ;; run task-worker
            (u/background
              (let [[result ex] (u/catch! (apply f args))]
                (->> (fn [task]
                       (if ex
                         ;; set task status to failed
                         (->> (jr/jsonrpc-failure sd/error-code-internal-error
                                                  (ex-message ex)
                                                  (ex-data ex))
                              (es/update-task-status-to-failed task))
                         ;; set task status to completed
                         (->> result
                              (es/update-task-status-to-completed task))))
                     (p/update-task common-session task-id))))
            ;; return task
            (es/clean-task new-task)))
        ;; specific tasks capability absent
        (fn [& _]
          (jr/jsonrpc-failure sd/error-code-method-not-found
                              (format "Tasks capability '%s' not supported"
                                      specific-tasks-capability)))))
    ;; ordinary request without task augmentation, so wrap not required
    f))


(defn with-capability
  [context capability-name capability f]
  (if (some? capability)
    ;; until initialized, only logging and ping is allowed
    (if (or (contains? #{"roots" "sampling" "elicitation"}
                       capability-name)  ; client capability?
            (= "tasks" capability-name)
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
            (jr/jsonrpc-failure sd/error-code-internal-error
                                message
                                data)
            (jr/jsonrpc-failure sd/error-code-internal-error
                                (str ex)))))
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
                             (ic/get-capability-roots))]
    (with-capability request "roots" roots-capability f)))


(defn with-sampling-capability [request f]
  (let [sampling-capability (-> (rt/?client-capabilities request)
                                (ic/get-capability-sampling))]
    (with-capability request "sampling" sampling-capability f)))


(defn with-elicitation-capability [request f]
  (let [elicitation-capability (-> (rt/?client-capabilities request)
                                   (ic/get-capability-elicitation))]
    (with-capability request "elicitation" elicitation-capability f)))


;; --- Server capabilities ---


(defn with-logging-capability [request f]
  (let [logging-capability (-> (rt/?server-capabilities request)
                               (ic/get-capability-logging))]
    (with-capability request "logging" logging-capability f)))


(defn with-completions-capability [request f]
  (let [completions-capability (-> (rt/?server-capabilities request)
                                   (ic/get-capability-completions))]
    (with-capability request "completions" completions-capability f)))


(defn with-prompts-capability [request f]
  (let [prompts-capability (-> (rt/?server-capabilities request)
                               (ic/get-capability-prompts))]
    (with-capability request "prompts" prompts-capability f)))


(defn with-resources-capability [request f]
  (let [resources-capability (-> (rt/?server-capabilities request)
                                 (ic/get-capability-resources))]
    (with-capability request "resources" resources-capability f)))


(defn with-tools-capability [request f]
  (let [tools-capability (-> (rt/?server-capabilities request)
                             (ic/get-capability-tools))]
    (with-capability request "tools" tools-capability f)))


;; --- Client/Server capabilities ---


(defn with-tasks-capability [specific-tasks-capability request f]
  (if-let [tasks-capability (-> (rs/my-capabilities request)
                                (ic/get-capability-tasks))]
    (let [tasks-capability-declaration (-> tasks-capability
                                           p/get-capability-declaration)]
      (if (->> (vec specific-tasks-capability)
               (get-in tasks-capability-declaration))
        (with-capability request "tasks" tasks-capability f)
        (jr/jsonrpc-failure sd/error-code-method-not-found
                            (format "Tasks capability %s is not supported"
                                    (pr-str specific-tasks-capability)))))
    (jr/jsonrpc-failure sd/error-code-method-not-found
                        "Tasks capability is not supported")))


;; --- Handshake ---


(defn handshake-error [client-protocol-version]
  (jr/jsonrpc-failure
   sd/error-code-invalid-params
   "Unsupported protocol version"
   {:supported (vec sd/protocol-versions-supported)
    :requested client-protocol-version}))


(defn ^{:see [sd/InitializeResult
              eg/make-initialize-result]} get-initialize-result
  [jsonrpc-request supported-protocol-version]
  (let [protocol-version supported-protocol-version
        server-capabilities (-> (rt/?server-capabilities jsonrpc-request)
                                (ic/get-server-capability-declaration))
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
         client-info :clientInfo} params]
    (if-some [protocol-version (-> client-protocol-version
                                   (find-supported-protocol-version))]
      (do
        (rs/set-initiliazed-params jsonrpc-request params)
        (-> jsonrpc-request
            (get-initialize-result protocol-version)
            jr/jsonrpc-success))
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
      (let [handler (-> #(p/get-sampling-response sampling-capability %)
                        (wrap-task-augmented jsonrpc-request
                                             ^{:see [ic/default-client-tasks-capability]}
                                             [:requests :sampling :createMessage]))]
        (-> params
            (copy-deps jsonrpc-request)
            handler
            make-result)))))


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
      (let [handler (-> #(p/get-elicitation-response elicitation-capability %)
                        (wrap-task-augmented jsonrpc-request
                                             ^{:see [ic/default-client-tasks-capability]}
                                             [:requests :elicitation :create]))]
        (-> params
            (copy-deps jsonrpc-request)
            handler
            make-result)))))


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
          (jr/jsonrpc-failure sd/error-code-invalid-params
                              "Requested prompt-name does not exist"
                              {:prompt-name prompt-name
                               :prompt-args prompt-args}))))))


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
          (jr/jsonrpc-failure sd/error-code-invalid-params
                              "Requested invalid resource URI"
                              {:uri uri}))))))


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
        (let [handler (wrap-task-augmented handler jsonrpc-request
                                           ^{:see [ic/default-server-tasks-capability]}
                                           [:requests :tools :call])]
          (-> tool-args
              (copy-deps jsonrpc-request)
              handler
              make-result))
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


;; --- Client/Server capabilities ---


(defn ^{:see [sd/ListTasksRequest
              sd/ListTasksResult
              eg/make-list-tasks-request
              eg/make-list-tasks-result]} tasks-list
  [{{cursor :cursor} :params
    :as jsonrpc-request}]
  (with-tasks-capability
    [:list]
    jsonrpc-request
    (fn [tasks-capability]
      (if-let [tasks (->> (rs/my-session jsonrpc-request)
                          p/list-tasks
                          (mapv es/clean-task))]
        (-> (eg/make-list-tasks-result tasks)
            make-result)
        (jr/jsonrpc-failure sd/error-code-internal-error
                            "Unable to fetch tasks")))))


(defn ^{:see [sd/CancelTaskRequest
              sd/CancelTaskResult
              eg/make-cancel-task-request
              eg/make-cancel-task-result]} tasks-cancel
  [{{task-id :taskId} :params
    :as jsonrpc-request}]
  (with-tasks-capability
    [:cancel]
    jsonrpc-request
    (fn [tasks-capability]
      (let [common-session (rs/my-session jsonrpc-request)]
        (if-let [task (p/get-task common-session task-id)]
          (do
            (p/request-cancel-task common-session task-id)
            (-> (eg/make-cancel-task-result task)
                make-result))
          ;;
          (jr/jsonrpc-failure sd/error-code-invalid-params
                              "Unable to locate task"
                              {:task-id task-id}))))))


(defn ^{:see [sd/GetTaskRequest
              sd/GetTaskResult
              eg/make-get-task-request
              eg/make-get-task-result]} tasks-get
  [{{task-id :taskId} :params
    :as jsonrpc-request}]
  (with-tasks-capability
    []  ; not restricted by specific tasks capability
    jsonrpc-request
    (fn [tasks-capability]
      (let [common-session (rs/my-session jsonrpc-request)]
        (if-let [task (-> (p/get-task common-session task-id)
                          es/clean-task)]
          (-> (eg/make-get-task-result task)
              make-result)
          (jr/jsonrpc-failure sd/error-code-invalid-params
                              "Unable to locate task"
                              {:task-id task-id}))))))


(defn ^{:see [sd/GetTaskPayloadRequest
              sd/GetTaskPayloadResult
              eg/make-get-task-payload-request
              eg/make-get-task-payload-result]} tasks-result
  [{{task-id :taskId} :params
    :as jsonrpc-request}]
  (with-tasks-capability
    []  ; not restricted by specific tasks capability
    jsonrpc-request
    (fn [tasks-capability]
      (let [common-session (rs/my-session jsonrpc-request)]
        (if-let [task (p/get-task common-session task-id)]
          (->> (eg/make-related-task-metadata task-id)
               (eg/make-get-task-payload-result (es/get-task-result task))
               make-result)
          (jr/jsonrpc-failure sd/error-code-invalid-params
                              "Unable to locate task"
                              {:task-id task-id}))))))


;; --- Notifications ---


(defn call-notification-handler
  [context method-name]
  (when-let [handler (rs/get-notification-handler context method-name)]
    (handler context)))


(defn ^{:see [sd/InitializedNotification
              eg/make-initialized-notification]} notifications-initialized
  [{:as jsonrpc-notification}]
  (rs/set-initialized-timestamp jsonrpc-notification)
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-initialized)
  {:result {}})


(defn ^{:see [sd/CancelledNotification
              eg/make-cancellation-notification]} notifications-cancelled
  [{params :params
    :as jsonrpc-notification}]
  (let [request-id (:requestId params)]
    (when (rt/has-session? jsonrpc-notification)  ; this is true on server
      (rs/request-cancellation jsonrpc-notification request-id))
    (call-notification-handler jsonrpc-notification
                               sd/method-notifications-cancelled))
  {:result {}})


(defn ^{:see [sd/ProgressNotification
              eg/make-progress-notification]} notifications-progress
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-progress)
  {:result {}})


(defn ^{:see [sd/LoggingMessageNotification
              eg/make-logging-message-notification]}
  notifications-message
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-message)
  {:result {}})


(defn ^{:see [sd/ResourceListChangedNotification
              eg/make-resource-list-changed-notification]}
  notifications-resources-list_changed
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-resources-list_changed)
  {:result {}})


(defn ^{:see [sd/ResourceUpdatedNotification
              eg/make-resource-updated-notification]}
  notifications-resources-updated
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-resources-updated)
  {:result {}})


(defn ^{:see [sd/PromptListChangedNotification
              eg/make-prompt-list-changed-notification]}
  notifications-prompts-list_changed
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-prompts-list_changed)
  {:result {}})


(defn ^{:see [sd/ToolListChangedNotification
              eg/make-tool-list-changed-notification]}
  notifications-tools-list_changed
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-tools-list_changed)
  {:result {}})


(defn ^{:see [sd/RootsListChangedNotification
              eg/make-roots-list-changed-notification]}
  notifications-roots-list_changed
  [{:as jsonrpc-notification}]
  (rs/fetch-roots jsonrpc-notification)
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-roots-list_changed)
  {:result {}})


(defn notifications-tasks-status
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-tasks-status)
  {:result {}})


(defn ^{:see [sd/ElicitationCompleteNotification
              eg/make-elicitation-complete-notification]}
  notifications-elicitation-complete
  [{:as jsonrpc-notification}]
  (call-notification-handler jsonrpc-notification
                             sd/method-notifications-elicitation-complete)
  {:result {}})
