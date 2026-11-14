;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.era26-entity-gen-test
  "Era26 Entity generation tests"
  (:require
   [clojure.test :refer [deftest is testing]]
   [malli.core :as mc]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.era26-entity-gen :as e26eg]
   [plumcp.core.dev.schema-malli :as sm]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.schema.era26-schema-defs :as e26sd]
   [plumcp.core.schema.schema-defs :as sd]))


(defn malli-validate
  [schema data]
  (or (mc/validate schema data)
      (do
        (sm/bling-explain schema data)
        nil)))


(def test-capability-declaration
  (-> ic/default-server-capabilities
      (ic/get-server-capability-declaration {:mcp-era e26sd/mcp-era26})))


(deftest test-request-meta-object
  (testing "minimal"
    (let [client-capabilities (e26eg/make-client-capabilities)
          request-meta-object (e26eg/make-request-meta-object
                               {:client-capabilities client-capabilities})]
      (is (malli-validate e26sd/RequestMetaObject
                          request-meta-object)))))


(deftest test-input-required-result
  (testing "minimal"
    (let [result-meta-object (e26eg/make-result-meta-object)]
      (is (mc/validate e26sd/InputRequiredResult
                       (e26eg/make-input-required-result
                        {:result-meta-object result-meta-object})))))
  (testing "maximal"
    (let [server-info (eg/make-implementation "test" "0.1")
          result-meta-object (e26eg/make-result-meta-object
                              {:server-info server-info})
          sampling-messages [(eg/make-sampling-message
                              sd/role-assistant
                              (eg/make-text-content "aa"))
                             (eg/make-sampling-message
                              sd/role-user
                              (eg/make-text-content "bb"))]
          input-requests {"aaa" (eg/make-create-message-request
                                 sampling-messages
                                 100)
                          "bbb" (eg/make-elicit-url-request "message"
                                                            "elicit-id"
                                                            "elicit://test")}]
      (is (mc/validate e26sd/InputRequiredResult
                       (e26eg/make-input-required-result
                        {:result-meta-object result-meta-object
                         :input-requests input-requests
                         :request-state "state1"
                         :result-type e26sd/result-type-input-required}))))))


(deftest test-make-input-response-request-params
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (testing "minimal"
      (is (malli-validate e26sd/InputResponseRequestParams
                          (e26eg/make-input-response-request-params
                           {:request-meta-object request-meta-object}))))
    (testing "maximal"
      (let [input-responses {"aa" (eg/make-create-message-result
                                   "model1"
                                   sd/role-user
                                   (eg/make-text-content "aa"))}]
        (is (malli-validate e26sd/InputResponseRequestParams
                            (e26eg/make-input-response-request-params
                             {:request-meta-object request-meta-object
                              :input-responses input-responses
                              :request-state "some-state"})))))))


(deftest test-make-cacheable-result
  (testing "minimal"
    (is (malli-validate e26sd/CacheableResult
                        (e26eg/make-cacheable-result))))
  (testing "maximal"
    (is (malli-validate e26sd/CacheableResult
                        (e26eg/make-cacheable-result {:ttl-ms 300
                                                      :cache-scope "public"})))))


(deftest test-make-discover-request
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (is (malli-validate e26sd/DiscoverRequest
                        (e26eg/make-discover-request
                         {:request-meta-object request-meta-object})))))


(deftest test-make-discover-result
  (let [server-capabilities (e26eg/make-server-capabilities)]
    (testing "minimal"
      (is (malli-validate e26sd/DiscoverResult
                          (e26eg/make-discover-result
                           {:server-capabilities server-capabilities}))))
    (testing "maximal"
      (is (malli-validate e26sd/DiscoverResult
                          (e26eg/make-discover-result
                           {:server-capabilities server-capabilities
                            :supported-versions ["just one"]
                            :instructions "Test instructions"}))))))


(deftest test-make-list-resources-result
  (is (malli-validate e26sd/ListResourcesResult
                      (e26eg/make-list-resources-result
                       {:resources []}))))


(deftest test-make-list-resources-templates-result
  (is (malli-validate e26sd/ListResourceTemplatesResult
                      (e26eg/make-list-resource-templates-result
                       {:resource-templates []}))))


(deftest test-make-read-resource-request
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (is (malli-validate e26sd/ReadResourceRequest
                        (e26eg/make-read-resource-request
                         {:uri "test://uri"
                          :request-meta-object request-meta-object})))))


(deftest test-make-read-resource-result-and-response
  (let [contents (eg/make-text-resource-contents
                  "test://uri" "test-text")]
    (testing "just result"
      (is (malli-validate e26sd/ReadResourceResult
                          (e26eg/make-read-resource-result
                           {:contents contents}))))
    (testing "full response"
      (is (malli-validate e26sd/ReadResourceResultResponse
                          (e26eg/make-read-resource-result-response
                           {:id "aa"
                            :contents contents}))))))


(deftest test-make-resource-list-changed-notification
  (testing "minimal"
    (is (malli-validate e26sd/ResourceListChangedNotification
                        (e26eg/make-resource-list-changed-notification))))
  (testing "maximal"
    (is (malli-validate e26sd/ResourceListChangedNotification
                        (e26eg/make-resource-list-changed-notification
                         {:notification-meta-object (e26eg/make-notification-meta-object)
                          :params {}})))))


(deftest test-make-subscription-filter
  (testing "minimal"
    (is (malli-validate e26sd/SubscriptionFilter
                        (e26eg/make-subscription-filter))))
  (testing "maximal"
    (is (malli-validate e26sd/SubscriptionFilter
                        (e26eg/make-subscription-filter
                         {:tools-list-changed? true
                          :prompts-list-changed? true
                          :resources-list-changed? true
                          :resource-subscriptions ["res1"]})))))


(deftest test-make-subscriptions-listen-request-params
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (testing "request params"
      (is (malli-validate e26sd/SubscriptionsListenRequestParams
                          (e26eg/make-subscriptions-listen-request-params
                           {:request-meta-object request-meta-object}))))
    (testing "the request"
      (is (malli-validate e26sd/SubscriptionsListenRequest
                          (e26eg/make-subscriptions-listen-request
                           {:request-meta-object request-meta-object}))))))


(deftest test-make-subscriptions-listen-result
  (testing "result meta-object"
    (is (malli-validate e26sd/SubscriptionsListenResultMetaObject
                        (e26eg/make-subscriptions-listen-result-meta-object
                         {:subscription-id "aa"}))))
  (testing "the result itself"
    (is (malli-validate e26sd/SubscriptionsListenResult
                        (e26eg/make-subscriptions-listen-result
                         {:subscription-id "aa"})))))


(deftest test-make-subscriptions-acknowledged-notification
  (testing "notification params"
    (is (malli-validate e26sd/SubscriptionsAcknowledgedNotificationParams
                        (e26eg/make-subscriptions-acknowledged-notification-params))))
  (testing "the notification"
    (is (malli-validate e26sd/SubscriptionsAcknowledgedNotification
                        (e26eg/make-subscriptions-acknowledged-notification)))))


(deftest test-make-resource-updated-notification
  (testing "notification params"
    (is (malli-validate e26sd/ResourceUpdatedNotificationParams
                        (e26eg/make-resource-updated-notification-params
                         {:uri "test://uri"}))))
  (testing "the notification"
    (is (malli-validate e26sd/ResourceUpdatedNotification
                        (e26eg/make-resource-updated-notification
                         {:uri "test://uri"})))))


(deftest test-make-list-prompts-result
  (testing "just result"
    (is (malli-validate e26sd/ListPromptsResult
                        (e26eg/make-list-prompts-result
                         {:prompts [(eg/make-prompt "prompt1")]}))))
  (testing "full response"
    (is (malli-validate e26sd/ListPromptsResultResponse
                        (e26eg/make-list-prompts-result-response
                         {:id "aa"
                          :prompts [(eg/make-prompt "prompt1")]})))))


(deftest test-make-get-prompt-request
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (testing "request params"
      (is (malli-validate e26sd/GetPromptRequestParams
                          (e26eg/make-get-prompt-request-params
                           {:name "prompt1"
                            :request-meta-object request-meta-object}))))
    (testing "full request"
      (is (malli-validate e26sd/GetPromptRequest
                          (e26eg/make-get-prompt-request
                           {:name "prompt1"
                            :request-meta-object request-meta-object}))))))


(deftest test-make-get-prompt-result-response
  (let [prompt-messages [(eg/make-prompt-message sd/role-user
                                                 (eg/make-text-content "test"))]]
    (testing "result only"
      (is (malli-validate e26sd/GetPromptResult
                          (e26eg/make-get-prompt-result
                           {:messages prompt-messages}))))
    (testing "full response"
      (is (malli-validate e26sd/GetPromptResultResponse
                          (e26eg/make-get-prompt-result-response
                           {:messages prompt-messages
                            :id "one"}))))))


(deftest test-make-list-tools-result
  (let [tool-1 (eg/make-tool "foo"
                             (eg/make-tool-input-output-schema
                              {} []))]
    (testing "result only"
      (is (malli-validate e26sd/ListToolsResult
                          (e26eg/make-list-tools-result {:tools [tool-1]}))))
    (testing "full response"
      (is (malli-validate e26sd/ListToolsResultResponse
                          (e26eg/make-list-tools-result-response
                           {:tools [tool-1]
                            :id "aa"}))))))


(deftest test-make-call-tool-request-params
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (testing "request params"
      (is (malli-validate e26sd/CallToolRequestParams
                          (e26eg/make-call-tool-request-params
                           {:name "foo"
                            :request-meta-object request-meta-object}))))
    (testing "full request"
      (is (malli-validate e26sd/CallToolRequest
                          (e26eg/make-call-tool-request
                           {:name "foo"
                            :request-meta-object request-meta-object}))))))


(deftest test-make-call-tool-result-response
  (testing "only result"
    (is (malli-validate e26sd/CallToolResult
                        (e26eg/make-call-tool-result
                         {:content [(eg/make-text-content "test")]}))))
  (testing "full response"
    (is (malli-validate e26sd/CallToolResultResponse
                        (e26eg/make-call-tool-result-response
                         {:content [(eg/make-text-content "test")]
                          :id "aa"})))))


(deftest test-make-tool-list-changed-notification
  (is (malli-validate e26sd/ToolListChangedNotification
                      (e26eg/make-tool-list-changed-notification))))


(deftest test-make-complete-result-response
  (testing "only result"
    (is (malli-validate e26sd/CompleteResult
                        (e26eg/make-complete-result
                         {:values ["foo" "bar"]}))))
  (testing "full response"
    (is (malli-validate e26sd/CompleteResultResponse
                        (e26eg/make-complete-result-response
                         {:values ["foo" "bar"]
                          :id "aa"})))))


(deftest test-make-elicit-request
  (let [client-capabilities (e26eg/make-client-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:client-capabilities client-capabilities})]
    (testing "request params"
      (is (malli-validate e26sd/ElicitRequestURLParams
                          (e26eg/make-elicit-request-url-params
                           {:message "test"
                            :url "test://url"}))))
    (testing "full request"
      (is (malli-validate e26sd/ElicitRequest
                          (e26eg/make-elicit-request
                           {:message "test"
                            :url "test://url"
                            :request-meta-object request-meta-object}))))))
