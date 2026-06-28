;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.entity-gen
  "Schema entity generator functions. You may want to call functions in
   `entity-support` namespace for direct use."
  (:require
   [plumcp.core.util :as u]
   [plumcp.core.schema.schema-defs :as sd]))


(defn as-jsonrpc
  [jsonrpc-map]
  (-> jsonrpc-map
      (u/assoc-missing :jsonrpc sd/jsonrpc-version)))


(defn make-id
  []
  (u/uuid-v4))


(defn add-id
  [jsonrpc-map]
  (-> jsonrpc-map
      (u/assoc-missing :id (make-id))))


;; --- structures ---


(defn ^{:see sd/ProgressToken} make-progress-token
  [x]
  x)


(defn ^{:see sd/Request} make-request
  [method-name & {:keys [_meta]
                  :or {_meta {:progressToken (u/uuid-v4)}}}]
  (-> {:method method-name
       :params (-> {}
                   (u/assoc-some :_meta _meta))}
      as-jsonrpc
      add-id))


(defn ^{:see sd/Notification} make-notification
  [method-name & {:keys [_meta]}]
  (-> {:method method-name
       :params (-> {}
                   (u/assoc-some :_meta _meta))}
      as-jsonrpc))


(defn ^{:see sd/InitializedNotification} make-initialized-notification
  [& {:keys [_meta]
      :as opts}]
  (make-notification sd/method-notifications-initialized opts))


(defn ^{:see sd/JSONRPCNotification} make-jsonrpc-notification
  [method-name & opts]
  (-> (make-notification method-name opts)
      (assoc :jsonrpc sd/jsonrpc-version)))


(defn ^{:see sd/RequestId} make-request-id
  [request-id]
  (or request-id (make-id)))


(defn ^{:see sd/Result} make-result
  [& {:keys [_meta]}]
  (-> {}
      (u/assoc-some :_meta _meta)))


(defn ^{:see sd/EmptyResult} make-empty-result
  []
  (make-result {}))


;; ----- JSON-RPC types -----


(defn ^{:see sd/JSONRPCRequest} make-jsonrpc-request
  [method-name request-id & opts]
  (-> (make-request method-name opts)
      (assoc :jsonrpc sd/jsonrpc-version
             :id request-id)))


(defn ^{:see sd/JSONRPCResultResponse} make-jsonrpc-result-response
  [request-id result]
  {:jsonrpc sd/jsonrpc-version
   :id request-id
   :result result})


(defn ^{:see [sd/MCPError]} make-error
  [error-code error-message & {:keys [error-data]}]
  (-> {:code error-code
       :message error-message}
      (u/assoc-some :data error-data)))


(defn ^{:see sd/JSONRPCErrorResponse} make-jsonrpc-error-response
  [request-id error-code error-message & {:keys [error-data
                                                 jsonrpc]
                                          :or {jsonrpc sd/jsonrpc-version}
                                          :as opts}]
  {:jsonrpc jsonrpc
   :id request-id
   :error (make-error error-code error-message
                      opts)})


(defn ^{:see sd/JSONRPCMessage} make-jsonrpc-message
  [any-jsonrpc-request-or-notification-response]
  any-jsonrpc-request-or-notification-response)


;; ----- end of JSON-RPC stuff -----


(defn ^{:see sd/CancelledNotification} make-cancellation-notification
  [request-id & {:keys [reason _meta] :as opts}]
  (-> (make-notification sd/method-notifications-cancelled
                         opts)
      (update :params #(-> %
                           (merge {:requestId request-id})
                           (u/assoc-some :reason reason)))))


(defn ^{:see sd/ClientCapabilities} make-client-capabilities
  [& {:keys [experimental ; map
             roots        ; map
             roots-list-changed? ; boolean
             sampling     ; map
             elicitation  ; map
             ]}]
  (-> {}
      (u/assoc-some
       :experimental experimental
       :roots (-> (or roots (and (some? roots-list-changed?) {}))
                  (u/assoc-some :listChanged roots-list-changed?))
       :sampling sampling
       :elicitation elicitation)))


(defn ^{:see [sd/Icon]} make-icon
  [src-url & {:keys [mime-type ; the MIME type for the icon
                     sizes  ; vector of icon sizes, where each size in
                            ; WxH format, e.g. "32x32" or "any" for SVG
                     theme  ; "light" or "dark"
                     ]}]
  (-> {:src src-url}
      (u/assoc-some :mimeType mime-type
                    :sizes (when sizes
                             (u/as-vec sizes))
                    :theme theme)))


(defn ^{:see sd/BaseMetadata} make-base-metadata
  [name & {:keys [title]}]
  (-> {:name name}
      (u/assoc-some :title title)))


(defn ^{:see [sd/TaskMetadata]} make-task-metadata
  [{:keys [ttl]}]
  (-> {}
      (u/assoc-some :ttl ttl)))


(defn ^{:see [sd/RelatedTaskMetadata]} make-related-task-metadata
  [task-id]
  {:taskId task-id})


(defn ^{:see [sd/Implementation
              sd/BaseMetadata
              make-icon]} make-implementation
  [mcp-impl-name mcp-impl-version & {:keys [title
                                            icons]}]
  (-> {:name mcp-impl-name
       :version mcp-impl-version}
      (u/assoc-some :title title
                    :icons (when icons
                             (u/as-vec icons)))))


(defn ^{:see sd/InitializeRequest} make-initialize-request
  [protocol-version
   client-capabilities-declaration
   client-info
   & {:keys [_meta] :as opts}]
  (-> (make-request sd/method-initialize
                    opts)
      (update :params
              merge {:protocolVersion protocol-version
                     :capabilities client-capabilities-declaration
                     :clientInfo client-info})))


(defn ^{:see sd/ServerCapabilities} make-server-capabilities
  [& {:keys [experimental ; map
             logging      ; map
             completions  ; map
             prompts      ; map
             prompts-list-changed?   ; boolean
             resources    ; map
             resources-subscribe?    ; boolean
             resources-list-changed? ; boolean
             tools        ; map
             tools-list-changed?     ; boolean
             ]}]
  (-> {}
      (u/assoc-some
       :experimental experimental
       :logging      logging
       :completions  completions
       :prompts (-> (or prompts (and (some? prompts-list-changed?) {}))
                    (u/assoc-some :listChanged prompts-list-changed?))
       :resources (-> (or resources
                          (and (or (some? resources-subscribe?)
                                   (some? resources-list-changed?))
                               {}))
                      (u/assoc-some
                       :subscribe resources-subscribe?
                       :listChanged resources-list-changed?))
       :tools (-> (or tools (and (some? tools-list-changed?) {}))
                  (u/assoc-some :listChanged tools-list-changed?)))))


(defn ^{:see sd/InitializeResult} make-initialize-result
  [protocol-version
   server-capabilities
   server-info
   & {:keys [instructions]}]
  (-> {:protocolVersion protocol-version
       :capabilities server-capabilities
       :serverInfo server-info}
      (u/assoc-some :instructions instructions)))


(defn ^{:see sd/PingRequest} make-ping-request
  [& {:keys [request-id]
      :or {request-id (make-id)}
      :as opts}]
  (make-jsonrpc-request sd/method-ping request-id opts))


(defn ^{:see sd/ProgressNotification} make-progress-notification
  [progress-token progress & {:keys [total
                                     message
                                     _meta]
                              :as opts}]
  (-> (make-notification sd/method-notifications-progress opts)
      (update :params (fn [params]
                        (-> params
                            (assoc :progressToken progress-token
                                   :progress progress)
                            (u/assoc-some :total total
                                          :message message))))))


(defn ^{:see sd/Cursor} make-cursor [s] s)


(defn ^{:see sd/PaginatedRequest} make-paginated-request
  [method-name & {:keys [cursor
                         _meta
                         request-id]
                  :or {request-id (make-id)}
                  :as opts}]
  (-> (make-jsonrpc-request method-name request-id opts)
      (u/assoc-some :params (-> (and (some some? [cursor _meta]) {})
                                (u/assoc-some :cursor cursor
                                              :_meta _meta)))))


(defn ^{:see sd/PaginatedResult} make-paginated-result
  [& {:keys [next-cursor
             _meta]
      :as opts}]
  (-> (make-result opts)
      (u/assoc-some :nextCursor next-cursor
                    :_meta _meta)))


(defn ^{:see sd/ListResourcesRequest} make-list-resources-request
  [& {:keys [cursor
             _meta]
      :as opts}]
  (make-paginated-request sd/method-resources-list opts))


(defn ^{:see sd/Role} make-role [x]
  (-> x
      (u/expected-enum! #{sd/role-assistant sd/role-user})))


(defn ^{:see [sd/Annotations
              u/now-iso8601-utc]} make-annotations
  [& {:keys [audience-roles
             priority
             last-modified]}]
  (-> {}
      (u/assoc-some :audience audience-roles
                    :priority priority
                    :lastModified last-modified)))


(defn ^{:see [sd/Resource
              sd/BaseMetadata
              make-icon]} make-resource
  [resource-uri resource-name & {:keys [description
                                        icons
                                        title
                                        mime-type
                                        annotations
                                        size
                                        _meta]}]
  (-> {:uri resource-uri
       :name resource-name}
      (u/assoc-some :description description
                    :icons (when icons
                             (u/as-vec icons))
                    :title title
                    :mimeType mime-type
                    :annotations annotations
                    :size size
                    :_meta _meta)))


(defn ^{:see sd/ListResourcesResult} make-list-resources-result
  [resources-coll & {:keys [next-cursor
                            _meta]
                     :as opts}]
  (-> (make-result opts)
      (assoc :resources (vec resources-coll))
      (u/assoc-some :nextCursor next-cursor
                    :_meta _meta)))


(defn ^{:see sd/ListResourceTemplatesRequest} make-list-resource-templates-request
  [& {:keys [cursor
             _meta]
      :as opts}]
  (-> (make-request sd/method-resources-templates-list
                    opts)
      (update :params
              u/assoc-some :cursor cursor)))


(defn ^{:see [sd/ResourceTemplate
              sd/BaseMetadata
              make-icon]} make-resource-template
  [uri-template name & {:keys [description
                               icons
                               title
                               mime-type
                               annotations
                               _meta]}]
  (-> {:uriTemplate uri-template
       :name name}
      (u/assoc-some :description description
                    :icons (when icons
                             (u/as-vec icons))
                    :title title
                    :mimeType mime-type
                    :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/ListResourceTemplatesResult} make-list-resource-templates-result
  [resource-templates-coll & {:keys [next-cursor
                                     _meta]
                              :as opts}]
  (-> (make-result opts)
      (assoc :resourceTemplates (vec resource-templates-coll))
      (u/assoc-some :nextCursor next-cursor)))


(defn ^{:see sd/ReadResourceRequest} make-read-resource-request
  [resource-uri & {:keys [_meta
                          request-id]
                   :or {request-id (make-id)}
                   :as opts}]
  (-> (make-jsonrpc-request sd/method-resources-read request-id opts)
      (update :params
              assoc :uri resource-uri)))


(defn ^{:see sd/ResourceContents} make-resource-contents
  [uri & {:keys [mime-type
                 _meta]}]
  (-> {:uri uri}
      (u/assoc-some :mimeType mime-type
                    :_meta _meta)))


(defn ^{:see sd/TextResourceContents} make-text-resource-contents
  [uri text & {:keys [mime-type]}]
  (-> {:uri uri
       :text (str text)}
      (u/assoc-some :mimeType mime-type)))


(defn ^{:see sd/BlobResourceContents} make-blob-resource-contents
  [uri blob & {:keys [mime-type]}]
  (-> {:uri uri
       :blob (u/as-base64-str blob "blob")}
      (u/assoc-some :mimeType mime-type)))


(defn ^{:see sd/ReadResourceResult} make-read-resource-result
  [contents-coll & {:keys [_meta] :as opts}]
  (-> (make-result opts)
      (assoc :contents (vec contents-coll))))


(defn ^{:see sd/ResourceListChangedNotification} make-resource-list-changed-notification
  [& {:keys [_meta] :as opts}]
  (make-notification sd/method-notifications-resources-list_changed
                     opts))


(defn ^{:see sd/SubscribeRequest} make-subscribe-request
  [uri & {:keys [_meta request-id]
          :or {request-id (make-id)}
          :as opts}]
  (-> (make-jsonrpc-request sd/method-resources-subscribe request-id
                            opts)
      (update :params
              assoc :uri uri)))


(defn ^{:see sd/UnsubscribeRequest} make-unsubscribe-request
  [uri & {:keys [_meta request-id]
          :or {request-id (make-id)}
          :as opts}]
  (-> (make-jsonrpc-request sd/method-resources-unsubscribe request-id
                            opts)
      (update :params
              assoc :uri uri)))


(defn ^{:see sd/ResourceUpdatedNotification} make-resource-updated-notification
  [uri & {:keys [_meta] :as opts}]
  (-> (make-notification sd/method-notifications-resources-updated
                         opts)
      (update :params
              assoc :uri uri)))


;; --- Prompts ---


(defn ^{:see [sd/PromptArgument
              sd/BaseMetadata]} make-prompt-argument
  [arg-name & {:keys [description title required?]}]
  (-> {:name arg-name}
      (u/assoc-some :description description
                    :title title
                    :required required?)))


(defn ^{:see [sd/Prompt
              sd/BaseMetadata
              make-icon]} make-prompt
  [prompt-name & {:keys [description icons title args _meta]}]
  (-> {:name prompt-name}
      (u/assoc-some :description description
                    :icons (when icons
                             (u/as-vec icons))
                    :title title
                    :arguments args
                    :_meta _meta)))


(defn ^{:see sd/ListPromptsRequest} make-list-prompts-request
  [& {:keys [cursor _meta] :as opts}]
  (-> (make-request sd/method-prompts-list
                    opts)
      (update :params
              u/assoc-some :cursor cursor)))


(defn ^{:see sd/ListPromptsResult} make-list-prompts-result
  [prompts-coll & {:keys [next-cursor _meta] :as opts}]
  (-> (make-result opts)
      (assoc :prompts prompts-coll)
      (u/assoc-some :nextCursor next-cursor)))


(defn ^{:see sd/GetPromptRequest} make-get-prompt-request
  [prompt-or-template-name & {:keys [args _meta request-id]
                              :or {request-id (make-id)}
                              :as opts}]
  (-> (make-jsonrpc-request sd/method-prompts-get request-id
                            opts)
      (update :params #(-> %
                           (assoc :name prompt-or-template-name)
                           (u/assoc-some :arguments args)))))


;; --- content ---


(defn ^{:see sd/TextContent} make-text-content
  [text & {:keys [^{:see [make-annotations]} annotations
                  _meta]}]
  (-> {:type "text"
       :text (str text)}
      (u/assoc-some :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/ImageContent} make-image-content
  [image mime-type & {:keys [^{:see [make-annotations]} annotations
                             _meta]}]
  (-> {:type "image"
       :data (u/as-base64-str image "image")
       :mimeType mime-type}
      (u/assoc-some :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/AudioContent} make-audio-content
  [audio mime-type & {:keys [^{:see [make-annotations]} annotations
                             _meta]}]
  (-> {:type "audio"
       :data (u/as-base64-str audio "audio")
       :mimeType mime-type}
      (u/assoc-some :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/EmbeddedResource} make-embedded-resource
  [^{:see [make-text-resource-contents
           make-blob-resource-contents]} resource-contents
   & {:keys [annotations _meta]}]
  (-> {:type "resource"
       :resource resource-contents}
      (u/assoc-some :annotations annotations
                    :_meta _meta)))


(defn ^{:see [sd/ResourceLink
              sd/Resource
              make-resource]} make-resource-link
  [resource-uri resource-name & opts]
  (-> (make-resource resource-uri resource-name opts)
      (assoc :type "resource_link")))


(defn ^{:see [sd/PromptMessage
              sd/Role
              sd/ContentBlock]} make-prompt-message
  [^{:see [sd/role-user
           sd/role-assistant]} role-string
   ^{:see [make-text-content
           make-image-content
           make-audio-content
           make-resource-link
           make-embedded-resource]} content-block]
  {:role role-string
   :content content-block})


(defn ^{:see sd/GetPromptResult} make-get-prompt-result
  [prompt-messages & {:keys [description _meta]}]
  (-> {:messages (vec prompt-messages)}
      (u/assoc-some :description description
                    :_meta _meta)))


(defn ^{:see sd/PromptListChangedNotification} make-prompt-list-changed-notification
  [& {:keys [_meta] :as opt}]
  (make-notification sd/method-notifications-prompts-list_changed
                     opt))


;; --- Tools ---


(defn ^{:see sd/ToolAnnotations} make-tool-annotations
  [& {:keys [title
             read-only-hint?
             destructive-hint?
             idempotent-hint?
             open-world-hint?]}]
  (-> {}
      (u/assoc-some :title title
                    :readOnlyHint read-only-hint?
                    :destructiveHint destructive-hint?
                    :idempotentHint idempotent-hint?
                    :openWorldHint open-world-hint?)))


(defn make-tool-input-output-schema
  "Make input/output-schema for a tool. Arguments explained below:

   properties-map  - map of property-name to {:type .. :description ..}
   required-names  - vector of required property names

   Input/Output properties:
   | Attribute    | Description                                        |
   |--------------|----------------------------------------------------|
   | :type        | JSON type: \"string\", \"number\", \"boolean\"...  |
   | :description | Attribute description, value range etc.            |
   | :default     | (Optional) Default value if no value is supplied   |
   | :minimum     | (Optional) Minimum value for the numeric attribute |
   | :maximum     | (Optional) Maximum value for the numeric attribute |"
  [properties-map required-names]
  {:type "object"
   :properties properties-map
   :required required-names})


(defn validate-tool-name
  "Validate tool name as per 2025-Nov-25 spec:
   https://modelcontextprotocol.io/specification/2025-11-25/server/tools#tool-names
   returning tool-name on success, else throw exception."
  [tool-name]
  (let [chars-msg (str "uppercase and lowercase ASCII letters (A-Z, a-z), "
                       "digits (0-9), underscore (_), hyphen (-), and dot (.)")]
    (-> tool-name
        (u/expected! string? "tool-name to be a string")
        (u/expected! #(<= 1 (count %) 128)
                     "tool-name to between 1 and 128 characters in length")
        (u/expected! #(re-matches #"[A-Za-z0-9_\-.]+" %)
                     (str "tool-name to have " chars-msg)))))


(defn make-tool-execution
  [& {:keys [task-support]
      :or {task-support sd/task-support-forbidden}}]
  (-> {}
      (u/assoc-some :taskSupport task-support)))


(defn ^{:see [sd/Tool
              sd/BaseMetadata
              make-icon]} make-tool
  [tool-name
   ^{:see make-tool-input-output-schema} input-schema
   & {:keys [description
             icons
             title
             ^{:see make-tool-execution} execution
             ^{:see make-tool-input-output-schema} output-schema
             annotations
             _meta]}]
  (-> {:name (validate-tool-name tool-name)
       :inputSchema input-schema}
      (u/assoc-some :description description
                    :icons (when icons
                             (u/as-vec icons))
                    :title title
                    :execution (when execution
                                 (make-tool-execution execution))
                    :outputSchema output-schema
                    :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/ListToolsRequest} make-list-tools-request
  [& {:keys [cursor
             _meta]
      :as opt}]
  (-> (make-request sd/method-tools-list opt)
      (update :params
              u/assoc-some :cursor cursor)))


(defn ^{:see sd/ListToolsResult} make-list-tools-result
  [^{:see make-tool} tools-coll & {:keys [next-cursor
                                          _meta]
                                   :as opt}]
  (-> (make-result opt)
      (assoc :tools (vec tools-coll))
      (u/assoc-some :nextCursor next-cursor)))


(defn ^{:see [sd/CallToolResult
              sd/Result]} make-call-tool-result
  [^{:see [sd/ContentBlock
           make-text-content
           make-image-content
           make-audio-content
           make-resource-link
           make-embedded-resource]} content-block-coll
   & {:keys [error?
             _meta]
      :or {error? false}  ; always be explicit about the error flag
      :as opt}]
  (-> (make-result opt)
      (assoc :content (vec content-block-coll)
             :isError error?)))


(defn ^{:see sd/CallToolRequest} make-call-tool-request
  [tool-name tool-argmap
   & {:keys [_meta
             request-id
             ^{:see [make-task-metadata
                     sd/TaskAugmentedRequestParams]} task]
      :or {request-id (make-id)}
      :as opt}]
  (-> (make-jsonrpc-request sd/method-tools-call request-id
                            opt)
      (update :params
              merge {:name tool-name
                     :arguments tool-argmap})
      (update :params
              u/assoc-some :task task)))


(defn ^{:see sd/ToolListChangedNotification} make-tool-list-changed-notification
  [& {:keys [_meta] :as opt}]
  (make-notification sd/method-notifications-tools-list_changed
                     opt))


;; --- Logging ---


(defn ^{:see sd/LoggingLevel} make-logging-level
  [level-string]
  (-> level-string
      (u/expected-enum! sd/log-level-indices)))


(defn ^{:see sd/SetLevelRequest} make-set-level-request
  [^{:see [make-logging-level]} level-string
   & {:keys [_meta request-id]
      :or {request-id (make-id)}
      :as opt}]
  (-> (make-jsonrpc-request sd/method-logging-setLevel request-id
                            opt)
      (update :params
              assoc :level level-string)))


(defn ^{:see sd/LoggingMessageNotification} make-logging-message-notification
  [log-level log-msg-or-data & {:keys [logger _meta] :as opts}]
  (-> (make-notification sd/method-notifications-message
                         opts)
      (update :params
              merge (-> {:level log-level
                         :data log-msg-or-data}
                        (u/assoc-some :logger logger)))))


;; ----- Sampling -----


(defn ^{:see sd/SamplingMessage} make-sampling-message
  [^{:see [make-role]} role
   ^{:see [make-text-content
           make-audio-content
           make-image-content]} content]
  {:role role
   :content content})


(defn ^{:see sd/ModelHint} make-model-hint
  [& {:keys [model-name]}]
  (-> {}
      (u/assoc-some :name model-name)))


(defn ^{:see sd/ModelPreferences} make-model-preferences
  [& {:keys [model-hints  ; vector of model hint maps
             cost-priority
             speed-priority
             intelligence-priority]}]
  (-> {}
      (u/assoc-some :hints model-hints
                    :costPriority cost-priority
                    :speedPriority speed-priority
                    :intelligencePriority intelligence-priority)))


(defn ^{:see [sd/ToolChoice]} make-tool-choice
  ([mode] {:mode mode})
  ([] {:mode sd/tool-choice-auto}))


(defn ^{:see sd/CreateMessageRequest} make-create-message-request
  "Make sampling create-message request from given arguments."
  [sampling-message-coll max-token-count
   & {:keys [model-preferences
             system-prompt
             include-context
             temperature
             stop-sequences
             metadata
             ^{:see [make-task-metadata
                     sd/TaskAugmentedRequestParams]} task
             ^{:see [sd/Tool]} tools
             ^{:see [sd/ToolChoice]} tool-choice
             _meta]
      :as opts}]
  (-> (make-request sd/method-sampling-createMessage
                    opts)
      (update :params #(-> %
                           (merge {:messages (vec sampling-message-coll)
                                   :maxTokens max-token-count})
                           (u/assoc-some :modelPreferences model-preferences
                                         :systemPrompt system-prompt
                                         :includeContext include-context
                                         :temperature temperature
                                         :stopSequences stop-sequences
                                         :metadata metadata
                                         :task task
                                         :tools tools
                                         :toolChoice tool-choice)))))


(defn ^{:see sd/CreateMessageResult} make-create-message-result
  [model-name role content & {:keys [stop-reason
                                     _meta]
                              :as opts}]
  (-> (make-result opts)
      (merge {:model model-name
              :role role
              :content content})
      (u/assoc-some :stopReason stop-reason)))


;; ----- Autocomplete -----


(defn ^{:see [sd/PromptReference
              sd/BaseMetadata]} make-prompt-reference
  [prompt-or-template-name & {:keys [title]}]
  (-> {:type "ref/prompt"
       :name prompt-or-template-name}
      (u/assoc-some :title title)))


(defn ^{:see sd/ResourceTemplateReference} make-resource-template-reference
  [uri-or-template]
  {:type "ref/resource"
   :uri uri-or-template})


(defn ^{:see sd/CompleteRequest} make-complete-request
  [^{:see [make-prompt-reference
           make-resource-template-reference]} prompt-or-resource-template-ref
   arg-name arg-value & {:keys [context _meta request-id]
                         :or {request-id (make-id)}
                         :as opts}]
  (-> (make-jsonrpc-request sd/method-completion-complete request-id
                            opts)
      (update :params #(-> %
                           (merge {:ref prompt-or-resource-template-ref
                                   :argument {:name arg-name
                                              :value arg-value}})
                           (u/assoc-some :context context)))))


(defn ^{:see sd/CompleteResult} make-complete-result
  [values & {:keys [total-count
                    has-more?
                    _meta]
             :as opts}]
  (let [returned-values (vec (take 100 values))
        leftover-values (seq (drop 100 values))]
    (-> (make-result opts)
        (merge {:completion (-> {:values returned-values}
                                (u/assoc-some
                                 :total (or total-count
                                            (if leftover-values
                                              (when (counted? leftover-values)
                                                (+ 100 (count leftover-values)))
                                              (count returned-values)))
                                 :hasMore (if (some? has-more?)
                                            has-more?
                                            (when leftover-values
                                              true))))}))))


;; ----- Roots -----


(defn ^{:see sd/ListRootsRequest} make-list-roots-request
  [& {:keys [_meta request-id]
      :or {request-id (make-id)}
      :as opts}]
  (make-jsonrpc-request sd/method-roots-list request-id
                        opts))


(defn ^{:see sd/Root} make-root
  [uri & {:keys [name _meta]}]
  (-> {:uri uri}
      (u/assoc-some :name name
                    :_meta _meta)))


(defn ^{:see sd/ListRootsResult} make-list-roots-result
  [roots-coll & {:keys [_meta] :as opts}]
  (-> (make-result opts)
      (merge {:roots (vec roots-coll)})))


(defn ^{:see [sd/RootsListChangedNotification
              sd/Notification]} make-roots-list-changed-notification
  [& {:keys [_meta] :as opts}]
  (make-notification sd/method-notifications-roots-list_changed
                     opts))


;; ----- Elicitations -----


(defn ^{:see [sd/StringSchema]} make-string-schema
  [& {:keys [title
             description
             min-length
             max-length
             format
             default]}]
  (when (some? format)
    (u/expected-enum! format sd/string-schema-format-set))
  (-> {:type "string"}
      (u/assoc-some :title title
                    :description description
                    :minLength min-length
                    :maxLength max-length
                    :format (sd/string-schema-format-set format)
                    :default default)))


(defn ^{:see [sd/NumberSchema]} make-number-schema
  [& {:keys [type
             title
             description
             minimum
             maximum
             default]}]
  (when (some? type)
    (u/expected-enum! type #{"number" "integer"}))
  (-> {:type (or type "number")}
      (u/assoc-some :title title
                    :description description
                    :minimum minimum
                    :maximum maximum
                    :default default)))


(defn ^{:see [sd/BooleanSchema]} make-boolean-schema
  [& {:keys [title
             description
             default]}]
  (-> {:type "boolean"}
      (u/assoc-some :title title
                    :description description
                    :default default)))


(defn ^{:see [sd/UntitledSingleSelectEnumSchema]}
  make-untitled-single-select-enum-schema
  [enum-vals & {:keys [title
                       description
                       default]}]
  (-> {:type "string"}
      (u/assoc-some :title title
                    :description description
                    :enum (vec enum-vals)
                    :default default)))


(declare make-titled-single-select-enum-schema)
(declare make-titled-multi-select-enum-schema)


(defn ^{:see [make-titled-single-select-enum-schema
              make-titled-multi-select-enum-schema]} make-enum-val-option
  "To be used with the following:
   - `make-titled-single-select-enum-schema`
   - `make-titled-multi-select-enum-schema`"
  [const title]
  {:const const
   :title title})


(defn ^{:see [sd/TitledSingleSelectEnumSchema
              make-enum-val-option]}
  make-titled-single-select-enum-schema
  [^{:see [make-enum-val-option]} enum-options
   & {:keys [title
             description
             default]}]
  (-> {:type "string"
       :oneOf (vec enum-options)}
      (u/assoc-some :title title
                    :description description
                    :default default)))


(defn ^{:see [sd/UntitledMultiSelectEnumSchema]}
  make-untitled-multi-select-enum-schema
  [item-vals & {:keys [title
                       description
                       min-items
                       max-items
                       default-items]}]
  (-> {:type "array"
       :items {:type "string"
               :enum (vec item-vals)}}
      (u/assoc-some :title title
                    :description description
                    :minItems min-items
                    :maxItems max-items
                    :default (vec default-items))))


(defn ^{:see [sd/TitledMultiSelectEnumSchema
              make-enum-val-option]}
  make-titled-multi-select-enum-schema
  [item-options & {:keys [title
                          description
                          min-items
                          max-items
                          default-items]}]
  (-> {:type "array"
       :items {:anyOf (vec item-options)}}
      (u/assoc-some :title title
                    :description description
                    :minItems min-items
                    :maxItems max-items
                    :default default-items)))


(defn ^{:see [sd/LegacyTitledEnumSchema]} make-enum-schema
  {:deprecated {:in "0.3.0"
                :use-instead make-titled-single-select-enum-schema
                :print-warning :always}}
  [enum-vals-coll & {:keys [title
                            description
                            enum-names]}]
  (-> {:type "string"
       :enum (vec enum-vals-coll)}
      (u/assoc-some :title title
                    :description description
                    :enumNames enum-names)))


(defn ^{:see [sd/ElicitRequestFormParams
              sd/ElicitRequestParams
              sd/ElicitRequest]} make-elicit-form-request
  [message
   schema-properties  ; a map
   & {:keys [_meta
             request-id
             schema-required
             ^{:see [make-task-metadata
                     sd/TaskAugmentedRequestParams]} task]
      :or {request-id (make-id)}
      :as opts}]
  (let [sr (when schema-required
             (vec schema-required))]
    (-> (make-jsonrpc-request sd/method-elicitation-create request-id
                              opts)
        (update :params
                merge {:mode "form"
                       :message message
                       :requestedSchema (-> {:type "object"
                                             :properties schema-properties}
                                            (u/assoc-some :required sr))})
        (update :params
                u/assoc-some :task task))))


(defn ^{:see [sd/ElicitRequestURLParams
              sd/ElicitRequestParams
              sd/ElicitRequest]} make-elicit-url-request
  [message elicitation-id url
   & {:keys [_meta
             request-id
             ^{:see [make-task-metadata
                     sd/TaskAugmentedRequestParams]} task]
      :or {request-id (make-id)}
      :as opts}]
  (-> (make-jsonrpc-request sd/method-elicitation-create request-id
                            opts)
      (update :params
              merge {:mode "url"
                     :message message
                     :elicitationId elicitation-id
                     :url url})
      (update :params
              u/assoc-some :task task)))


(defn ^{:see [sd/ElicitRequest
              sd/Request]} make-elicit-request
  {:deprecated {:in "0.3.0"
                :use-instead make-elicit-form-request
                :print-warning :always}}
  [^String message
   schema-properties  ; a map
   & {:keys [_meta request-id schema-required]
      :or {request-id (make-id)}
      :as opts}]
  (let [sr (when schema-required
             (vec schema-required))]
    (-> (make-jsonrpc-request sd/method-elicitation-create request-id
                              opts)
        (update :params
                merge {:message message
                       :requestedSchema (-> {:type "object"
                                             :properties schema-properties}
                                            (u/assoc-some :required sr))}))))


(defn ^{:see [sd/ElicitResult
              sd/Result]} make-elicit-result
  [^{:see [sd/elicit-action-accept
           sd/elicit-action-cancel
           sd/elicit-action-decline]} action
   & {:keys [content _meta] :as opts}]
  (-> (make-result opts)
      (merge {:action action})
      (u/assoc-some :content content)))


(defn ^{:see [sd/ElicitationCompleteNotification]}
  make-elicitation-complete-notification
  [elicitation-id & {:keys [_meta] :as opts}]
  (-> (make-notification sd/method-notifications-elicitation-complete
                         opts)
      (update :params merge {:elicitationId elicitation-id})))


;; ----- Tasks -----


(defn ^{:see [sd/Task]} make-task
  [task-id ^{:see [sd/TaskStatus]} status
   & {:keys [status-message
             created-at
             last-updated-at
             ttl        ; unspecified is nil, which is a valid value
             poll-interval]}]
  (let [now-iso8601 (u/now-iso8601-utc)]
    (-> {:taskId task-id
         :status status
         :createdAt (or created-at now-iso8601)
         :lastUpdatedAt (or last-updated-at now-iso8601)
         :ttl ttl}
        (u/assoc-some :statusMessage status-message
                      :pollInterval poll-interval))))


(defn ^{:see [sd/CreateTaskResult]} make-create-task-result
  [^{:see [sd/Task
           make-task]} task
   & {:keys [_meta
             ^{:see [sd/meta-model-immediate-response-key]} model-immediate-response]
      :as opts}]
  (-> (make-result opts)
      (merge {:task task})
      (u/assoc-some-in [:_meta sd/meta-model-immediate-response-key]
                       model-immediate-response)))


(defn ^{:see [sd/ListTasksRequest]} make-list-tasks-request
  [& {:keys [cursor]
      :as opts}]
  (-> (make-request sd/method-tasks-list
                    opts)
      (update :params u/assoc-some :cursor cursor)))


(defn ^{:see [sd/ListTasksResult]} make-list-tasks-result
  [tasks & {:as opts}]
  (-> (make-paginated-result opts)
      (merge {:tasks tasks})))


(defn ^{:see [sd/CancelTaskRequest]} make-cancel-task-request
  [task-id & {:keys [request-id]
              :or {request-id (make-id)}
              :as opts}]
  (-> (make-jsonrpc-request sd/method-tasks-cancel request-id opts)
      (assoc-in [:params :taskId] task-id)))


(defn ^{:see [sd/CancelTaskResult]} make-cancel-task-result
  [task & {:as opts}]
  (-> (make-result opts)
      (merge task)))


(defn ^{:see [sd/GetTaskRequest]} make-get-task-request
  [task-id & {:keys [request-id]
              :or {request-id (make-id)}
              :as opts}]
  (-> (make-jsonrpc-request sd/method-tasks-get request-id opts)
      (assoc-in [:params :taskId] task-id)))


(defn ^{:see [sd/GetTaskResult]} make-get-task-result
  [task & {:as opts}]
  (-> (make-result opts)
      (merge task)))


(defn ^{:see [sd/GetTaskPayloadRequest]} make-get-task-payload-request
  [task-id & {:keys [request-id]
              :or {request-id (make-id)}
              :as opts}]
  (-> (make-jsonrpc-request sd/method-tasks-result request-id opts)
      (assoc-in [:params :taskId] task-id)))


(defn ^{:see [sd/GetTaskPayloadResult]} make-get-task-payload-result
  [result ^{:see [make-related-task-metadata]} related-task-metadata]
  (-> result
      (assoc-in [:_meta sd/meta-related-task-key]
                related-task-metadata)))


(defn ^{:see [sd/TaskStatusNotification]} make-task-status-notification
  [task & {:as opts}]
  (-> (make-jsonrpc-notification sd/method-notifications-tasks-status opts)
      (assoc :params task)))
