;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.era26-entity-gen
  "Schema entity generator functions - exclusive bits (delta) for
   MCP 2026-07-28 spec."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.schema.era26-schema-defs :as e26sd]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]))


;; --- capabilities ---


(defn ^{:see e26sd/ClientCapabilities} make-client-capabilities
  [& {:keys [experimental ; map
             roots        ; map - DEPRECATED
             sampling     ; map - DEPRECATED
             elicitation  ; map
             extensions   ; map
             ]}]
  (-> {}
      (u/assoc-some
       :experimental experimental
       :roots roots
       :sampling sampling
       :elicitation elicitation
       :extensions extensions)))


(defn ^{:see e26sd/ServerCapabilities} make-server-capabilities
  [& {:keys [experimental ; map
             logging      ; map - DEPRECATED
             completions  ; map
             prompts      ; map
             prompts-list-changed?   ; boolean
             resources    ; map
             resources-subscribe?    ; boolean
             resources-list-changed? ; boolean
             tools        ; map
             tools-list-changed?     ; boolean
             extensions   ; map
             ]}]
  (-> {}
      (u/assoc-some
       :experimental experimental
       :logging      logging
       :completions  completions
       :prompts (-> (or prompts (when (some? prompts-list-changed?) {}))
                    (u/assoc-some :listChanged prompts-list-changed?))
       :resources (-> (or resources
                          (when (or (some? resources-subscribe?)
                                    (some? resources-list-changed?))
                            {}))
                      (u/assoc-some
                       :subscribe resources-subscribe?
                       :listChanged resources-list-changed?))
       :tools (-> (or tools (when (some? tools-list-changed?) {}))
                  (u/assoc-some :listChanged tools-list-changed?))
       :extensions extensions)))


(defn ^{:se e26sd/RequestMetaObject} make-request-meta-object
  [{:keys [;; -- required --
           ^{:see [e26sd/ClientCapabilities
                   make-client-capabilities]}
           client-capabilities
           ;; -- optional --
           ^{:see e26sd/protocol-versions-supported}
           protocol-version
           ^{:see [sd/Implementation]}
           client-info
           ^{:see [sd/LoggingLevel]}
           log-level
           ^{:see [sd/ProgressToken]}
           progress-token]
    :or {protocol-version e26sd/protocol-version-max}
    :as kwargs}]
  (u/expected-enum! protocol-version
                    e26sd/protocol-versions-supported-set)
  (u/expected-keys! kwargs [:client-capabilities])
  (-> {e26sd/key-protocol-version protocol-version
       e26sd/key-client-capabilities client-capabilities}
      (u/assoc-some
       :progressToken progress-token
       :io.modelcontextprotocol/clientInfo client-info
       :io.modelcontextprotocol/logLevel log-level)))


(defn ^{:see [e26sd/RequestParams]} make-request-params
  [{:keys [;; -- required --
           ^{:see [e26sd/RequestMetaObject
                   make-request-meta-object]} request-meta-object]
    :as kwargs}]
  (u/expected-keys! kwargs [:request-meta-object])
  {:_meta request-meta-object})


(defn ^{:see e26sd/NotificationMetaObject} make-notification-meta-object
  [& {:keys [subscription-id]}]
  (-> {}
      (u/assoc-some
       :io.modelcontextprotocol/subscriptionId subscription-id)))


(defn ^{:see [e26sd/NotificationParams]} make-notification-params
  [{:keys [;; -- optional --
           ^{:see [e26sd/NotificationMetaObject
                   make-notification-meta-object]}
           notification-meta-object]
    :as kwargs}]
  {:_meta (or notification-meta-object
              (make-notification-meta-object kwargs))})


(defn ^{:see e26sd/ResultMetaObject} make-result-meta-object
  [& {:keys [;; -- optional --
             ^{:see [sd/Implementation
                     eg/make-implementation]}
             server-info]}]
  (-> {}
      (u/assoc-some
       e26sd/key-server-info server-info)))


(defn ^{:see e26sd/Result} make-result
  [{:keys [;; -- required --
           ^{:see [e26sd/ResultType]}
           result-type  ; string
           ;; -- optional --
           ^{:see [e26sd/ResultMetaObject
                   make-result-meta-object]}
           result-meta-object]
    :as kwargs}]
  (u/expected-keys! kwargs [:result-type])
  (-> {:resultType result-type}
      (u/assoc-some
       :_meta result-meta-object)))


(defn ^{:see e26sd/JSONRPCRequest} make-jsonrpc-request
  [{:keys [;; -- required --
           method
           request-meta-object
           ;; -- optional --
           ^{:see [sd/RequestId]}
           id
           params]
    :or {id (eg/make-id)
         params {}}
    :as kwargs}]
  (u/expected-keys!  kwargs [:method
                             :request-meta-object])
  {:jsonrpc sd/jsonrpc-version
   :id id
   :method method
   :params (-> params
               (assoc :_meta request-meta-object))})


(defn ^{:see e26sd/JSONRPCNotification} make-jsonrpc-notification
  [{:keys [;; -- required --
           method
           ;; -- optional --
           notification-meta-object
           params]
    :or {notification-meta-object {}
         params {}}
    :as kwargs}]
  (u/expected-keys! kwargs [:method])
  {:jsonrpc sd/jsonrpc-version
   :method method
   :params (-> params
               (update :_meta
                       merge notification-meta-object))})


(defn ^{:see e26sd/JSONRPCResultResponse} make-jsonrpc-result-response
  [{:keys [^{:see [sd/RequestId]}
           request-id
           ^{:see [e26sd/Result]}
           result]
    :as kwargs}]
  (u/expected-keys! kwargs [:request-id
                            :result])
  {:jsonrpc sd/jsonrpc-version
   :id request-id
   :result result})



(defn ^{:see e26sd/InputRequiredResult} make-input-required-result
  [{:keys [;; -- required --
           ^{:see [e26sd/ResultMetaObject
                   make-result-meta-object]} result-meta-object
           ;; -- optional --
           ^{:see [e26sd/InputRequests
                   e26sd/InputRequest]}
           input-requests  ; map of server-generated ID to InputRequest
           request-state   ; String (encoded representation)
           result-type]
    :or {result-type e26sd/result-type-input-required}
    :as kwargs}]
  (u/expected-keys! kwargs [:result-meta-object])
  (-> {:_meta result-meta-object
       :resultType result-type}
      (u/assoc-some
       :inputRequests input-requests
       :requestState request-state)))


(defn ^{:see e26sd/InputResponseRequestParams}
  make-input-response-request-params
  [{:keys [;; -- required --
           ^{:see [e26sd/RequestMetaObject
                   make-request-meta-object]} request-meta-object
           ;; -- optional --
           ^{:see e26sd/InputResponses} input-responses
           ^String request-state]
    :as kwargs}]
  (u/expected-keys! kwargs [:request-meta-object])
  (-> {:_meta request-meta-object}
      (u/assoc-some
       :inputResponses input-responses
       :requestState request-state)))


(defn ^{:see [e26sd/CacheableResult]} make-cacheable-result
  [& {:keys [ttl-ms
             cache-scope]
      :or {ttl-ms 3600000
           cache-scope "public"}
      :as kwargs}]
  (-> {:ttlMs ttl-ms
       :cacheScope cache-scope}
      (merge (make-result (merge {:result-type e26sd/result-type-complete}
                                 ;; seed it with result-type 'complete'
                                 ;; (cacheable result must be complete)
                                 kwargs)))))


(defn ^{:see e26sd/DiscoverRequest} make-discover-request
  [{:as ^{:see [make-request-params
                make-jsonrpc-request]} kwargs}]
  (-> {:method e26sd/method-server-discover
       :params (make-request-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-request)))


(defn ^{:see [e26sd/DiscoverResult
              e26sd/CacheableResult]} make-discover-result
  [{:keys [;; -- required --
           ^{:see e26sd/ServerCapabilities} server-capabilities
           ;; -- optional --
           ^{:see e26sd/protocol-versions-supported} supported-versions
           instructions]
    :or {supported-versions e26sd/protocol-versions-supported}
    :as kwargs}]
  (u/expected-keys! kwargs [:server-capabilities])
  (-> {;; -- DiscoverResult attrs --
       :supportedVersions supported-versions
       :capabilities server-capabilities}
      (merge (make-cacheable-result kwargs))
      (u/assoc-some
       :instructions instructions)))


(defn ^{:see [e26sd/ListResourcesResult]} make-list-resources-result
  [{:keys [;; -- required --
           ^{:see [sd/Resource]}
           resources  ; vector of resources
           ]
    :as kwargs}]
  (u/expected-keys! kwargs [:resources])
  (-> {:resources resources}
      (merge (eg/make-paginated-result kwargs))
      (merge (make-cacheable-result kwargs))))


(defn ^{:see [e26sd/ListResourceTemplatesResult
              eg/make-paginated-result
              make-cacheable-result]}
  make-list-resource-templates-result
  [{:keys [;; -- required --
           ^{:see [sd/ResourceTemplate]}
           resource-templates  ; vector of resource templates
           ]
    :as kwargs}]
  (u/expected-keys! kwargs [:resource-templates])
  (-> {:resourceTemplates resource-templates}
      (merge (eg/make-paginated-result kwargs))
      (merge (make-cacheable-result kwargs))))


(defn ^{:see [e26sd/ResourceRequestParams]} make-resource-request-params
  [{:keys [;; -- required --
           uri]
    :as kwargs}]
  (u/expected-keys! kwargs [:uri])
  (-> {:uri uri}
      (merge (make-request-params kwargs))))


(defn ^{:see [e26sd/ReadResourceRequestParams
              make-resource-request-params
              make-input-response-request-params]}
  make-read-resource-request-params
  [{:as kwargs}]
  (-> {}
      (merge (make-resource-request-params kwargs))
      (merge (make-input-response-request-params kwargs))))


(defn ^{:see [e26sd/ReadResourceRequest]} make-read-resource-request
  [{:as ^{:keys [make-read-resource-request-params
                 make-jsonrpc-request]} kwargs}]
  (-> {:method sd/method-resources-read
       :params (make-read-resource-request-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-request)))


(defn ^{:see [e26sd/ReadResourceResult]} make-read-resource-result
  [{:keys [;; -- required --
           ^{:see [sd/TextResourceContents
                   sd/BlobResourceContents]}
           contents]
    :as kwargs}]
  (u/expected-keys! kwargs [:contents])
  (-> {:contents contents}
      (merge (make-cacheable-result kwargs))))


(defn ^{:see [e26sd/ReadResourceResultResponse
              make-jsonrpc-result-response]}
  make-read-resource-result-response
  [{:as kwargs}]
  (-> kwargs
      (assoc :result (make-read-resource-result kwargs))
      (make-jsonrpc-result-response)))


(defn ^{:see [e26sd/ResourceListChangedNotification]}
  make-resource-list-changed-notification
  [& {:as kwargs}]
  (-> {:method sd/method-notifications-resources-list_changed}
      (merge kwargs)
      (make-jsonrpc-notification)))


(defn ^{:see [e26sd/SubscriptionFilter]} make-subscription-filter
  [& {:keys [tools-list-changed?
             prompts-list-changed?
             resources-list-changed?
             resource-subscriptions]
      :as _kwargs}]
  (-> {}
      (u/assoc-some
       :toolsListChanged tools-list-changed?
       :promptsListChanged prompts-list-changed?
       :resourcesListChanged resources-list-changed?
       :resourceSubscriptions resource-subscriptions)))


(defn ^{:see [e26sd/SubscriptionsListenRequestParams]}
  make-subscriptions-listen-request-params
  [^{:see [make-subscription-filter]}
   {:as kwargs}]
  (-> {:notifications (make-subscription-filter kwargs)}
      (merge (make-request-params kwargs))))


(defn ^{:see [e26sd/SubscriptionsListenRequest]}
  make-subscriptions-listen-request
  [^{:see [make-subscriptions-listen-request-params]}
   {:as kwargs}]
  (-> {:method e26sd/method-subscriptions-listen
       :params (make-subscriptions-listen-request-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-request)))


(defn ^{:see [e26sd/SubscriptionsListenResultMetaObject]}
  make-subscriptions-listen-result-meta-object
  [^{:see [make-result-meta-object]}
   {:keys [subscription-id]
    :as kwargs}]
  (u/expected-keys! kwargs [:subscription-id])
  (-> {e26sd/key-subscription-id subscription-id}
      (merge (make-result-meta-object kwargs))))


(defn ^{:see [e26sd/SubscriptionsListenResult]}
  make-subscriptions-listen-result
  [^{:see [make-subscriptions-listen-result-meta-object
           make-result]}
   {:as kwargs}]
  (-> {:result-meta-object (make-subscriptions-listen-result-meta-object kwargs)
       :result-type e26sd/result-type-complete}
      (merge kwargs)
      (make-result)))


(defn ^{:see [e26sd/SubscriptionsAcknowledgedNotificationParams]}
  make-subscriptions-acknowledged-notification-params
  [& ^{:see [make-subscription-filter
             make-notification-params]}
   {:as kwargs}]
  (-> {:notifications (make-subscription-filter kwargs)}
      (merge (-> {:notification-meta-object (make-notification-meta-object
                                             kwargs)}
                 make-notification-params))))


(defn ^{:see [e26sd/SubscriptionsAcknowledgedNotification]}
  make-subscriptions-acknowledged-notification
  [& ^{:see [make-subscriptions-acknowledged-notification-params
             make-jsonrpc-notification]}
   {:as kwargs}]
  (-> {:method e26sd/method-notifications-subscriptions-acknowledged
       :params (make-subscriptions-acknowledged-notification-params
                kwargs)}
      (merge kwargs)
      (make-jsonrpc-notification)))


(defn ^{:see [e26sd/ResourceUpdatedNotificationParams]}
  make-resource-updated-notification-params
  [{:keys [uri]
    :as kwargs}]
  (u/expected-keys! kwargs [:uri])
  (-> {:uri uri}
      (merge (make-notification-params kwargs))))


(defn ^{:see e26sd/ResourceUpdatedNotification}
  make-resource-updated-notification
  [^{:see [make-resource-updated-notification-params]}
   {:as kwargs}]
  (-> {:method sd/method-notifications-resources-updated
       :params (make-resource-updated-notification-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-notification)))


(defn ^{:see [e26sd/ListPromptsResult]} make-list-prompts-result
  [{:keys [;; -- required --
           ^{:see sd/Prompt}
           prompts]
    :as kwargs}]
  (u/expected-keys! kwargs [:prompts])
  (-> {:prompts prompts}
      (merge (make-cacheable-result kwargs))))


(defn ^{:see [e26sd/ListPromptsResultResponse
              make-jsonrpc-result-response]}
  make-list-prompts-result-response
  [{:as kwargs}]
  (-> kwargs
      (assoc :result (make-list-prompts-result kwargs))
      (make-jsonrpc-result-response)))


(defn ^{:see [e26sd/GetPromptRequestParams]}
  make-get-prompt-request-params
  [{:keys [;; -- required --
           name
           ;; -- optional --
           arguments  ; map
           ]
    :as kwargs}]
  (u/expected-keys! kwargs [:name])
  (-> {:name name}
      (u/assoc-some
       :arguments arguments)
      (merge (make-input-response-request-params kwargs))))


(defn ^{:see [e26sd/GetPromptRequest]} make-get-prompt-request
  [^{:see [make-get-prompt-request-params]}
   {:as kwargs}]
  (-> {:method sd/method-prompts-get
       :params (make-get-prompt-request-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-request)))


(defn ^{:see [e26sd/GetPromptResultResponse
              make-jsonrpc-result-response]}
  make-get-prompt-result-response
  [{:as kwargs}]
  (u/expected-keys! kwargs [:prompt-messages])
  (-> kwargs
      (assoc :result (eg/make-get-prompt-result (:prompt-messages kwargs)
                                                kwargs))
      (make-jsonrpc-result-response)))


(defn ^{:see [e26sd/ListToolsResult]} make-list-tools-result
  [{:keys [;; -- required --
           ^{:see sd/Tool}
           tools]
    :as kwargs}]
  (u/expected-keys! kwargs [:tools])
  (-> {:tools tools}
      (merge (make-cacheable-result kwargs))))


(defn ^{:see [e26sd/ListToolsResultResponse
              make-jsonrpc-result-response]}
  make-list-tools-result-response
  [{:as kwargs}]
  (-> kwargs
      (assoc :result (make-list-tools-result kwargs))
      (make-jsonrpc-result-response)))


(defn ^{:see [e26sd/CallToolRequestParams]}
  make-call-tool-request-params
  [{:keys [;; -- required --
           name
           ;; -- optional --
           arguments]
    :as kwargs}]
  (u/expected-keys! kwargs [:name])
  (-> {:name name}
      (u/assoc-some
       :arguments arguments)
      (merge (make-input-response-request-params kwargs))))


(defn ^{:see [e26sd/CallToolRequest]} make-call-tool-request
  [{:as kwargs}]
  (-> {:method sd/method-tools-call
       :params (make-call-tool-request-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-request)))


(defn ^{:see [e26sd/CallToolResultResponse
              make-jsonrpc-result-response]}
  make-call-tool-result-response
  [{:keys [contents]
    :as kwargs}]
  (u/expected-keys! kwargs [:contents])
  (-> kwargs
      (assoc :result (eg/make-call-tool-result contents kwargs))
      (make-jsonrpc-result-response)))


(defn ^{:see e26sd/ToolListChangedNotification}
  make-tool-list-changed-notification
  [& {:as kwargs}]
  (-> {:method sd/method-notifications-tools-list_changed
       :params (make-notification-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-notification)))


(defn ^{:see [e26sd/CompleteResultResponse
              make-jsonrpc-result-response]}
  make-complete-result-response
  [{:keys [values]
    :as kwargs}]
  (u/expected-keys! kwargs [:values])
  (-> kwargs
      (assoc :result (eg/make-complete-result values kwargs))
      (make-jsonrpc-result-response)))


(defn ^{:see [e26sd/ElicitRequestURLParams]}
  make-elicit-request-url-params
  [{:keys [message
           url]
    :as kwargs}]
  (u/expected-keys! kwargs [:message
                            :url])
  (-> {:mode "url"
       :message message
       :url url}))


(defn ^{:see [e26sd/ElicitRequest]} make-elicit-request
  [{:as kwargs}]
  (-> {:method sd/method-elicitation-create
       :params (make-elicit-request-url-params kwargs)}
      (merge kwargs)
      (make-jsonrpc-request)))
