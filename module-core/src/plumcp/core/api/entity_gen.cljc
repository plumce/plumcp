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


(defn add-id
  [jsonrpc-map]
  (-> jsonrpc-map
      (u/assoc-missing :id (u/uuid-v4))))


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
  [jsonrpc method-name & opts]
  (-> (make-notification method-name opts)
      (assoc :jsonrpc jsonrpc)))


(defn ^{:see sd/RequestId} make-request-id
  [request-id]
  request-id)


(defn ^{:see sd/Result} make-result
  [& {:keys [_meta]}]
  (-> {}
      (u/assoc-some :_meta _meta)))


(defn ^{:see sd/EmptyResult} make-empty-result
  []
  (make-result {}))


;; ----- JSON-RPC types -----


(defn ^{:see sd/JSONRPCRequest} make-jsonrpc-request
  [method-name jsonrpc request-id & opts]
  (-> (make-request method-name opts)
      (assoc :jsonrpc jsonrpc
             :id request-id)))


(defn ^{:see sd/JSONRPCResponse} make-jsonrpc-response
  [jsonrpc request-id result]
  {:jsonrpc jsonrpc
   :id request-id
   :result result})


(defn ^{:see sd/JSONRPCError} make-jsonrpc-error
  [jsonrpc request-id error-code error-message & {:keys [error-data]}]
  {:jsonrpc jsonrpc
   :id request-id
   :error (-> {:code error-code
               :message error-message}
              (u/assoc-some :data error-data))})


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


(defn ^{:see sd/BaseMetadata} make-base-metadata
  [name & {:keys [title]}]
  (-> {:name name}
      (u/assoc-some :title title)))


(defn ^{:see [sd/Implementation
              sd/BaseMetadata]} make-implementation
  [mcp-impl-name mcp-impl-version & {:keys [title]}]
  (-> {:name mcp-impl-name
       :version mcp-impl-version}
      (u/assoc-some :title title)))


(defn ^{:see sd/InitializeRequest} make-initialize-request
  [protocol-version
   client-capabilities-declaration
   client-implementation
   & {:keys [_meta] :as opts}]
  (-> (make-request sd/method-initialize
                    opts)
      (update :params
              merge {:protocolVersion protocol-version
                     :capabilities client-capabilities-declaration
                     :clientInfo client-implementation})))


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
   server-implementation
   & {:keys [instructions]}]
  (-> {:protocolVersion protocol-version
       :capabilities server-capabilities
       :serverInfo server-implementation}
      (u/assoc-some :instructions instructions)))


(defn ^{:see sd/PingRequest} make-ping-request
  [& {:keys [_meta]
      :as opts}]
  (make-request sd/method-ping opts))


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
                         _meta]}]
  (-> (make-request method-name)
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


(defn ^{:see sd/Role} make-role [x] x)


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
              sd/BaseMetadata]} make-resource
  [resource-uri resource-name & {:keys [description
                                        title
                                        mime-type
                                        annotations
                                        size
                                        _meta]}]
  (-> {:uri resource-uri
       :name resource-name}
      (u/assoc-some :description description
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
              sd/BaseMetadata]} make-resource-template
  [uri-template name & {:keys [description
                               title
                               mime-type
                               annotations
                               _meta]}]
  (-> {:uriTemplate uri-template
       :name name}
      (u/assoc-some :description description
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
  [resource-uri & {:keys [_meta] :as opts}]
  (-> (make-request sd/method-resources-read
                    opts)
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
  [uri & {:keys [_meta] :as opts}]
  (-> (make-request sd/method-resources-subscribe
                    opts)
      (update :params
              assoc :uri uri)))


(defn ^{:see sd/UnsubscribeRequest} make-unsubscribe-request
  [uri & {:keys [_meta] :as opts}]
  (-> (make-request sd/method-resources-unsubscribe
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
              sd/BaseMetadata]} make-prompt
  [prompt-name & {:keys [description title args _meta]}]
  (-> {:name prompt-name}
      (u/assoc-some :description description
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
  [prompt-or-template-name & {:keys [args _meta] :as opts}]
  (-> (make-request sd/method-prompts-get
                    opts)
      (update :params #(-> %
                           (assoc :name prompt-or-template-name)
                           (u/assoc-some :arguments args)))))


;; --- content ---


(defn ^{:see sd/TextContent} make-text-content
  [text & {:keys [annotations _meta]}]
  (-> {:type "text"
       :text (str text)}
      (u/assoc-some :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/ImageContent} make-image-content
  [image mime-type & {:keys [annotations _meta]}]
  (-> {:type "image"
       :data (u/as-base64-str image "image")
       :mimeType mime-type}
      (u/assoc-some :annotations annotations
                    :_meta _meta)))


(defn ^{:see sd/AudioContent} make-audio-content
  [audio mime-type & {:keys [annotations _meta]}]
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


(defn ^{:see [sd/Tool
              sd/BaseMetadata]} make-tool
  [tool-name
   ^{:see make-tool-input-output-schema} input-schema
   & {:keys [description
             title
             ^{:see make-tool-input-output-schema} output-schema
             annotations
             _meta]}]
  (-> {:name tool-name
       :inputSchema input-schema}
      (u/assoc-some :description description
                    :title title
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
      :as opt}]
  (-> (make-result opt)
      (assoc :content (vec content-block-coll))
      (u/assoc-some :isError error?)))


(defn ^{:see sd/CallToolRequest} make-call-tool-request
  [tool-name tool-argmap & {:keys [_meta] :as opt}]
  (-> (make-request sd/method-tools-call
                    opt)
      (update :params
              merge {:name tool-name
                     :arguments tool-argmap})))


(defn ^{:see sd/ToolListChangedNotification} make-tool-list-changed-notification
  [& {:keys [_meta] :as opt}]
  (make-notification sd/method-notifications-tools-list_changed
                     opt))


;; --- Logging ---


(defn ^{:see sd/LoggingLevel} make-logging-level
  [level-string]
  level-string)


(defn ^{:see sd/SetLevelRequest} make-set-level-request
  [level-string & {:keys [_meta] :as opt}]
  (-> (make-request sd/method-logging-setLevel
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
  [role content]
  {:role role
   :content content})


(defn ^{:see sd/ModelHint} make-model-hint
  [& {:keys [model-name]}]
  (-> {}
      (u/assoc-some :name model-name)))


(defn ^{:see sd/ModelPreferences} make-model-preferences
  [& {:keys [model-hints
             cost-priority
             speed-priority
             intelligence-priority]}]
  (-> {}
      (u/assoc-some :hints model-hints
                    :costPriority cost-priority
                    :speedPriority speed-priority
                    :intelligencePriority intelligence-priority)))


(defn ^{:see sd/CreateMessageRequest} make-create-message-request
  [sampling-message-coll max-token-count & {:keys [model-preferences
                                                   system-prompt
                                                   include-context
                                                   temperature
                                                   stop-sequences
                                                   metadata
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
                                         :metadata metadata)))))


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
   arg-name arg-value & {:keys [context
                                _meta]
                         :as opts}]
  (-> (make-request sd/method-completion-complete
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
  [& {:keys [_meta] :as opts}]
  (make-request sd/method-roots-list
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
             format]}]
  (when (some? format)
    (u/expected-enum! format #{"email" "uri" "date" "date-time"}))
  (-> {:type "string"}
      (u/assoc-some :title title
                    :description description
                    :minLength min-length
                    :maxLength max-length
                    :format (#{"email" "uri" "date" "date-time"}
                             format))))


(defn make-number-schema
  [& {:keys [type
             title
             description
             minimum
             maximum]}]
  (when (some? type)
    (u/expected-enum! type #{"number" "integer"}))
  (-> {:type (or type "number")}
      (u/assoc-some :title title
                    :description description
                    :minimum minimum
                    :maximum maximum)))


(defn make-boolean-schema
  [& {:keys [title
             description
             default]}]
  (-> {:type "boolean"}
      (u/assoc-some :title title
                    :description description
                    :default default)))


(defn make-enum-schema
  [enum-vals-coll & {:keys [title
                            description
                            enum-names]}]
  (-> {:type "string"
       :enum (vec enum-vals-coll)}
      (u/assoc-some :title title
                    :description description
                    :enumNames enum-names)))


(defn ^{:see [sd/ElicitRequest
              sd/Request]} make-elicit-request
  [^String message
   schema-properties  ; a map
   & {:keys [schema-required
             _meta]
      :as opts}]
  (let [sr (when schema-required
             (vec schema-required))]
    (-> (make-request sd/method-elicitation-create
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
