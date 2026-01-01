(ns plumcp.core.test.entity-gen-test
  "Entity generation tests"
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as mc]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.schema.schema-defs :as sd]))


(def test-capability-declaration
  ;; FIXME: Deduce value from capabilities (see below)
  #_(-> cap/default-server-capabilities
        cap/get-server-capability-declaration)
  {})


(deftest test-request
  (testing "Generic request"
    (is (mc/validate sd/Request
                     (eg/make-request "foo"))
        "Must conform to schema")
    (is (:id (eg/make-request "foo"))
        "Request should have ID"))
  ;;
  (testing "Initialize request"
    (let [impl (eg/make-implementation "PluMCP"
                                       "0.1.1"
                                       {:title "PluMCP 0.1.1"})]
      (is (mc/validate sd/Implementation impl)
          "Should be valid implementation")
      (is (mc/validate sd/InitializeRequest
                       (eg/make-initialize-request sd/protocol-version-max
                                                   test-capability-declaration
                                                   impl))
          "Should be a valid InitializeRequest")))
  ;;
  (testing "ListPromptsRequest"
    (let [target (eg/make-list-prompts-request)]
      (is (mc/validate sd/ListPromptsRequest target)
          "Should be a ListPromptsRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "GetPromptRequest"
    (let [target (eg/make-get-prompt-request "prompt-name")]
      (is (mc/validate sd/GetPromptRequest target)
          "Should be a GetPromptRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "ListResouresRequest"
    (let [target (eg/make-list-resources-request)]
      (is (mc/validate sd/ListResourcesRequest target)
          "Should be a ListResourcesRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "ReadResoureRequest"
    (let [target (eg/make-read-resource-request "test://resource")]
      (is (mc/validate sd/ReadResourceRequest target)
          "Should be a ReadResourceRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "ListToolsRequest"
    (let [target (eg/make-list-tools-request)]
      (is (mc/validate sd/ListToolsRequest target)
          "Should be a ListToolsRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "ListResourceTemplatesRequest"
    (let [target (eg/make-list-resource-templates-request)]
      (is (mc/validate sd/ListResourceTemplatesRequest target)
          "Should be a ListResourceTemplatesRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "CallToolRequest"
    (let [target (eg/make-call-tool-request "some-tool" {})]
      (is (mc/validate sd/CallToolRequest target)
          "Should be a CallToolRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "CreateMessageRequest"
    (let [target (eg/make-create-message-request [] 100)]
      (is (mc/validate sd/CreateMessageRequest target)
          "Should be a CreateMessageRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "CompleteRequest"
    (let [target (eg/make-complete-request (eg/make-prompt-reference "some-name")
                                           "some-name"
                                           "some-value")]
      (is (mc/validate sd/CompleteRequest target)
          "Should be a CompleteRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "ListRootsRequest"
    (let [target (eg/make-list-roots-request)]
      (is (mc/validate sd/ListRootsRequest target)
          "Should be a ListRootsRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "ElicitRequest"
    (let [target (eg/make-elicit-request "elicit-message" {})]
      (is (mc/validate sd/ElicitRequest target)
          "Should be a ElicitRequest")
      (is (mc/validate sd/PaginatedRequest target)
          "Should be a valid PaginatedRequest")))
  ;;
  (testing "Ping request"
    (is (mc/validate sd/PingRequest
                     (eg/make-ping-request))
        "Should be a valid Ping request"))
  ;;
  (testing "SubscribeRequest"
    (is (mc/validate sd/SubscribeRequest
                     (eg/make-subscribe-request "test://uri"))
        "Should be a valid SubscribeRequest"))
  ;;
  (testing "UnsubscribeRequest"
    (is (mc/validate sd/UnsubscribeRequest
                     (eg/make-unsubscribe-request "test://uri"))
        "Should be a valid UnsubscribeRequest"))
  ;;
  (testing "SetLevelRequest"
    (is (mc/validate sd/SetLevelRequest
                     (eg/make-set-level-request sd/log-level-6-info))
        "Should be a valid SetLevelRequest"))
  ;;
  )


(deftest test-result
  (testing "Generic result"
    (is (mc/validate sd/Result
                     (eg/make-result "foo"))
        "Must conform to schema"))
  ;;
  (testing "Initialize request"
    (let [impl (eg/make-implementation "ZooMCP"
                                       "0.1.1"
                                       {:title "ZooMCP 0.1.1"})]
      (is (mc/validate sd/Implementation impl)
          "Should be valid implementation")
      (is (mc/validate sd/InitializeResult
                       (eg/make-initialize-result sd/protocol-version-max
                                                  test-capability-declaration
                                                  impl))
          "Should be a valid InitializeResult")))
  ;;
  (testing "ListPromptsResult"
    (let [target (eg/make-list-prompts-result [])]
      (is (mc/validate sd/ListPromptsResult target)
          "Should be a ListPromptsResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "GetPromptResult"
    (let [target (eg/make-get-prompt-result [])]
      (is (mc/validate sd/GetPromptResult target)
          "Should be a GetPromptResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "ListResouresResult"
    (let [target (eg/make-list-resources-result [])]
      (is (mc/validate sd/ListResourcesResult target)
          "Should be a ListResourcesResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "ReadResoureResult"
    (let [target (eg/make-read-resource-result [])]
      (is (mc/validate sd/ReadResourceResult target)
          "Should be a ReadResourceResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "ListToolsResult"
    (let [target (eg/make-list-tools-result [])]
      (is (mc/validate sd/ListToolsResult target)
          "Should be a ListToolsResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "ListResourceTemplatesResult"
    (let [target (eg/make-list-resource-templates-result [])]
      (is (mc/validate sd/ListResourceTemplatesResult target)
          "Should be a ListResourceTemplatesResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "CallToolResult"
    (let [target (eg/make-call-tool-result [])]
      (is (mc/validate sd/CallToolResult target)
          "Should be a CallToolResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "CreateMessageResult"
    (let [target (eg/make-create-message-result "some-model" sd/role-user
                                                (eg/make-text-content "some-text"))]
      (is (mc/validate sd/CreateMessageResult target)
          "Should be a CreateMessageResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "CompleteResult"
    (let [target (eg/make-complete-result [])]
      (is (mc/validate sd/CompleteResult target)
          "Should be a CompleteResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "ListRootsResult"
    (let [target (eg/make-list-roots-result [])]
      (is (mc/validate sd/ListRootsResult target)
          "Should be a ListRootsResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  (testing "ElicitResult"
    (let [target (eg/make-elicit-result sd/elicit-action-accept)]
      (is (mc/validate sd/ElicitResult target)
          "Should be a ElicitResult")
      (is (mc/validate sd/PaginatedResult target)
          "Should be a valid PaginatedResult")))
  ;;
  )


(deftest test-notification
  (testing "Generic notification"
    (is (mc/validate sd/Notification
                     (eg/make-notification "foo"))
        "Must conform to schema"))
  ;;
  (testing "CancelledNotification"
    (is (mc/validate sd/CancelledNotification
                     (eg/make-cancellation-notification "id-1"))
        "Must conform to schema"))
  ;;
  (testing "InitializedNotification"
    (is (mc/validate sd/InitializedNotification
                     (eg/make-initialized-notification))
        "Should be InitializedNotification"))
  ;;
  (testing "ResourceListChangedNotification"
    (is (mc/validate sd/ResourceListChangedNotification
                     (eg/make-resource-list-changed-notification))
        "Should be ResourceListChangedNotification"))
  ;;
  (testing "ResourceUpdatedNotification"
    (is (mc/validate sd/ResourceUpdatedNotification
                     (eg/make-resource-updated-notification "test://uri"))
        "Should be ResourceUpdatedNotification"))
  ;;
  (testing "LoggingMessageNotification"
    (is (mc/validate sd/LoggingMessageNotification
                     (eg/make-logging-message-notification sd/log-level-6-info
                                                           "log message"))
        "Should be LoggingMessageNotification"))
  ;;
  (testing "PromptListChangedNotification"
    (is (mc/validate sd/PromptListChangedNotification
                     (eg/make-prompt-list-changed-notification))
        "Should be PromptListChangedNotification"))
  ;;
  (testing "ProgressNotification"
    (is (mc/validate sd/ProgressNotification
                     (eg/make-progress-notification "token-1" 20))
        "Should be ProgressNotification"))
  ;;
  (testing "RootsListChangedNotification"
    (is (mc/validate sd/RootsListChangedNotification
                     (eg/make-roots-list-changed-notification))
        "Should be RootsListChangedNotification"))
  ;;
  (testing "ToolListChangedNotification"
    (is (mc/validate sd/ToolListChangedNotification
                     (eg/make-tool-list-changed-notification))
        "Should be ToolListChangedNotification"))
  ;;
  )
