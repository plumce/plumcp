;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.entity-gen-test
  "Entity generation tests"
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as mc]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs byte-array)]])
  #?(:clj (:import
           [clojure.lang ExceptionInfo])))


(def test-capability-declaration
  (-> cap/default-server-capabilities
      cap/get-server-capability-declaration))


(deftest test-resource
  (testing "resource"
    (is (mc/validate sd/Resource
                     (eg/make-resource "test://foo" "foo-resource"))
        "Minimal resource")
    (is (mc/validate sd/Resource
                     (eg/make-resource "test://foo" "foo-resource"
                                       {:description "foo docs"
                                        :title "Foo resource"
                                        :mime-type "text/html"
                                        :annotations (eg/make-annotations)
                                        :size 100 #_bytes}))
        "Maximal resource")))


(deftest test-resource-template
  (testing "resource template"
    (is (mc/validate sd/ResourceTemplate
                     (eg/make-resource-template "test://emp/{id}" "employee"))
        "Minimal resource template")
    (is (mc/validate sd/ResourceTemplate
                     (eg/make-resource-template "test://emp/{id}/pic" "emp-pic"
                                                {:description "Pic on file"
                                                 :title "Employee pic"
                                                 :mime-type "image/png"
                                                 :annotations (eg/make-annotations)}))
        "Maximal resource template"))
  (testing "resource template reference"
    (is (mc/validate sd/ResourceTemplateReference
                     (eg/make-resource-template-reference "test://emp/{id}"))
        "Minimal resource template reference")))


(deftest test-prompt
  (testing "prompt argument"
    (is (mc/validate sd/PromptArgument
                     (eg/make-prompt-argument "foo"))
        "Minimal prompt argument")
    (is (mc/validate sd/PromptArgument
                     (eg/make-prompt-argument "foo" {:description "all about foo"
                                                     :title "Foo mania"
                                                     :required? true}))
        "Maximal prompt argument"))
  (testing "prompt"
    (is (mc/validate sd/Prompt
                     (eg/make-prompt "fooprompt"))
        "Minimal prompt")
    (is (mc/validate sd/Prompt
                     (eg/make-prompt "barprompt"
                                     {:description "Bar-bar"
                                      :title "Restro Bar"
                                      :args [(eg/make-prompt-argument "foo")]}))
        "Maximal prompt")))


(deftest test-content
  (testing "text content"
    (is (mc/validate sd/TextContent
                     (eg/make-text-content "foo"))
        "Minimal text content")
    (is (mc/validate sd/TextContent
                     (->> {:audience-roles [(eg/make-role sd/role-assistant)]
                           :priority 0.5
                           :last-modified "2025-12-01"}
                          eg/make-annotations
                          (array-map :annotations)
                          (eg/make-text-content "bar")))
        "Maximal text content"))
  (testing "image content"
    (is (mc/validate sd/ImageContent
                     (-> (byte-array [1 2 3 4])  ; pretend image
                         (u/bytes->base64-string)
                         (eg/make-image-content "image/png")))
        "Minimal image content")
    (is (mc/validate sd/ImageContent
                     (-> (byte-array [5 6 7 8])  ; pretend image
                         (u/bytes->base64-string)
                         (eg/make-image-content "image/png"
                                                {:annotations (eg/make-annotations)})))
        "Maximal image content"))
  (testing "audio content"
    (is (mc/validate sd/AudioContent
                     (-> (byte-array [1 2 3 4])  ; pretend audio
                         (u/bytes->base64-string)
                         (eg/make-audio-content "audio/ogg")))
        "Minimal audio content")
    (is (mc/validate sd/AudioContent
                     (-> (byte-array [5 6 7 8])  ; pretend audio
                         (u/bytes->base64-string)
                         (eg/make-audio-content "audio/vorbis"
                                                {:annotations (eg/make-annotations)})))
        "Maximal audio content"))
  (testing "embedded resource"
    (is (mc/validate sd/EmbeddedResource
                     (-> (eg/make-text-resource-contents "test://foo" "footext")
                         (eg/make-embedded-resource)))
        "Minimal embedded text resource")
    (is (mc/validate sd/EmbeddedResource
                     (-> (eg/make-text-resource-contents "test://foo" "footext"
                                                         {:mime-type "text/plain"})
                         (eg/make-embedded-resource {:annotations (eg/make-annotations)})))
        "Maximal embedded text resource")
    (is (mc/validate sd/EmbeddedResource
                     (-> (eg/make-blob-resource-contents "test://foo"
                                                         (-> (byte-array [1 2 3 4])
                                                             (u/bytes->base64-string)))
                         (eg/make-embedded-resource)))
        "Minimal embedded blob resource")
    (is (mc/validate sd/EmbeddedResource
                     (-> (eg/make-blob-resource-contents "test://foo"
                                                         (-> (byte-array [5 6 7 8])
                                                             (u/bytes->base64-string))
                                                         {:mime-type "text/plain"})
                         (eg/make-embedded-resource {:annotations (eg/make-annotations)})))
        "Maximal embedded blob resource"))
  (testing "resource link"
    (is (mc/validate sd/ResourceLink
                     (eg/make-resource-link "test://foo" "foo-resource"))
        "Minimal resource link")
    (is (mc/validate sd/ResourceLink
                     (eg/make-resource-link "test://foo" "foo-resource"
                                            {:description "foo docs"
                                             :title "all about foo"
                                             :mime-type "text/plain"
                                             :annotations (eg/make-annotations)
                                             :size 100 #_bytes}))
        "Maximal resource link")))


(deftest test-tool-definition
  (testing "tool annotations"
    (is (mc/validate sd/ToolAnnotations
                     (eg/make-tool-annotations))
        "Minimal tool annotation")
    (is (mc/validate sd/ToolAnnotations
                     (eg/make-tool-annotations {:title "Accounts summary"
                                                :read-only-hint? false
                                                :destructive-hint? true
                                                :idempotent-hint? false
                                                :open-world-hint? false}))
        "Maximal tool annotation"))
  (testing "tool"
    (is (mc/validate sd/Tool
                     (eg/make-tool "add"
                                   (eg/make-tool-input-output-schema
                                    {"a" {:type "number" :description "first number"}
                                     "b" {:type "number" :description "second number"}}
                                    ["a" "b"])))
        "Simple tool definition")))


(deftest test-logging-definition
  (testing "logging-level"
    (is (= "debug"
           (eg/make-logging-level "debug"))
        "Correct log level")
    (is (thrown-with-msg?
         ExceptionInfo #"Expected value to be either of*"
         (eg/make-logging-level "debugging"))
        "Incorrect log level")))


(deftest test-sampling-message
  (testing "make sampling message"
    (is (mc/validate sd/SamplingMessage
                     (eg/make-sampling-message sd/role-user
                                               (eg/make-text-content "foo")))
        "simple sampling message")
    (is (mc/validate sd/ModelHint
                     (eg/make-model-hint "mixtral")))
    (is (mc/validate sd/ModelPreferences
                     (eg/make-model-preferences {}))
        "Minimal model preferences")
    (is (mc/validate sd/ModelPreferences
                     (eg/make-model-preferences
                      {:model-hints [(eg/make-model-hint "mixtral")]
                       :cost-priority 0.5
                       :speed-priority 0.7
                       :intelligence-priority 0.6}))
        "Maximal model preferences")))


(deftest test-autocomplete
  (is (mc/validate sd/PromptReference
                   (eg/make-prompt-reference "foo"))
      "Minimal prompt reference")
  (is (mc/validate sd/PromptReference
                   (eg/make-prompt-reference "bar" {:title "Bar prompting"}))
      "Maximal prompt reference"))


(deftest test-root
  (is (mc/validate sd/Root
                   (eg/make-root "test://foo"))
      "Miminal root")
  (is (mc/validate sd/Root
                   (eg/make-root "test://bar" :name "Bar root"))))


(deftest test-elicitation
  (is (mc/validate sd/StringSchema
                   (eg/make-string-schema))
      "Minimal string-schema")
  (is (mc/validate sd/StringSchema
                   (eg/make-string-schema {:title "Some elicitaion"
                                           :description "Elicitation description"
                                           :min-length 10
                                           :max-length 20
                                           :format "email"}))))


;; ----- All requests -----


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


;; ----- All results -----


(deftest test-result
  (testing "Generic result"
    (is (mc/validate sd/Result
                     (eg/make-result "foo"))
        "Must conform to schema"))
  ;;
  (testing "Initialize request"
    (let [impl (eg/make-implementation "PluMCP"
                                       "0.1.1"
                                       {:title "PluMCP 0.1.1"})]
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


;; ----- All notifications -----


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
