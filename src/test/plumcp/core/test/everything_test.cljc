;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.everything-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.apps.everything :as ev]
   [plumcp.core.client.zero-client-transport :as zct]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.main.client :as client]
   [plumcp.core.main.server :as server]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(defn ^{:mcp-type :sampling} sampling-handler
  [{:as jsonrpc-request-params}]
  (u/eprintln "[sampling-handler] Received" (-> jsonrpc-request-params
                                                rt/dissoc-runtime
                                                pr-str))
  (let [result (->> (eg/make-text-content "Canned sampling text")
                    (eg/make-create-message-result "test-model"
                                                   sd/role-user))]
    (u/eprintln "[sampling-handler] Replying" (-> result
                                                  pr-str))
    result))


(def elicit-action-atom (atom nil))


(defn ^{:mcp-type :elicitation} elicitation-handler
  [{:as jsonrpc-request-params}]
  (u/eprintln "[elicitation-handler] Received" (-> jsonrpc-request-params
                                                   rt/dissoc-runtime
                                                   pr-str))
  (let [action (deref elicit-action-atom)
        result (eg/make-elicit-result action
                                      (when (= "accept" action)
                                        {:content {:color "blue"
                                                   :number 42
                                                   :pets "dog"}}))]
    (u/eprintln "[elicitation-handler] Replying" (-> result
                                                     pr-str))
    result))


(defn make-zero-transport
  [{:keys [runtime
           jsonrpc-handler]
    :as server-options}]
  (-> zs/make-zero-handler
      (partial runtime jsonrpc-handler)
      (zct/make-zero-client-transport)))


(defn make-initialized-context []
  (let [client (-> {:client-transport (-> server/server-options
                                          make-zero-transport)}
                   (merge client/client-options)
                   mc/make-client)]
    ;; (doto client cs/async-initialize-and-notify!)
    (uab/let-await [_ (mc/initialize-and-notify! client)]
      client)))


(deftest list-server-primitives-test
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing sd/method-tools-list
         (uab/let-await [tools-list (mc/list-tools client)]
           (is (vector? tools-list) "tools is a vector")
           (let [ev-tools (->> ev/tools
                               (mapv #(dissoc % :handler))
                               (sort-by :name))
                 tl-tools (->> tools-list
                               (sort-by :name))]
             (is (= tl-tools ev-tools)
                 "retrieved/existing tools should match"))))
       (testing sd/method-prompts-list
         (uab/let-await [prompts-list (mc/list-prompts client)]
           (is (vector? prompts-list))
           (let [ev-prompts (->> ev/prompts
                                 (mapv #(dissoc % :handler))
                                 (sort-by :name))
                 pl-prompts (->> prompts-list
                                 (sort-by :name))]
             (is (= pl-prompts ev-prompts)
                 "retrieved/existing prompts should match"))))
       (testing sd/method-resources-list
         (uab/let-await [resources-list (mc/list-resources client)]
           (is (vector? resources-list))
           (let [ev-resources (->> ev/resources
                                   (mapv #(dissoc % :handler))
                                   (sort-by :name))
                 rl-resources (->> resources-list
                                   (sort-by :name))]
             (is (= rl-resources ev-resources)
                 "retrieved/existing resources should match"))))
       (testing sd/method-resources-templates-list
         (uab/let-await [resource-templates-list (mc/list-resource-templates
                                                  client)]
           (is (vector? resource-templates-list))
           (let [ev-rts (->> ev/resource-templates
                             (mapv #(dissoc % :handler))
                             (sort-by :name))
                 rl-rts (->> resource-templates-list
                             (sort-by :name))]
             (is (= rl-rts ev-rts)
                 "retrieved/existing resource-templates should match"))))
       ;; --- All done, disconnect now ---
       (mc/disconnect! client)
       (done!)))))


(deftest test-call-tool-echo
  (tu/async-test [done!]
    (uab/let-await
      [client (make-initialized-context)
       {content :content
        error? :isError} (mc/call-tool client "echo"
                                       {:message "Hi there"})]
      (is (= content [{:type "text"
                       :text "Hi there"}]))
      (is (not error?))
      (mc/disconnect! client)
      (done!))))


(deftest test-call-tool-add
  (tu/async-test [done!]
    (uab/let-await
      [client (make-initialized-context)
       {content :content
        error? :isError} (mc/call-tool client "add"
                                       {:a 10 :b 20})]
      (is (= content [{:type "text"
                       :text "The sum of 10 and 20 is 30"}]))
      (is (not error?))
      (mc/disconnect! client)
      (done!))))


(deftest test-call-tool-longRunningOperation
  (tu/async-test [done!]
    (uab/let-await
      [client (make-initialized-context)
       {content :content
        error? :isError} (mc/call-tool client
                                       "longRunningOperation"
                                       {:duration 4 :steps 4})]
      (is (= content
             [{:type "text"
               :text "Long running operation completed. Duration: 4 seconds, Steps: 4."}]))
      (is (not error?))
      (mc/disconnect! client)
      (done!))))


(deftest test-call-tool-printEnv
  (tu/async-test [done!]
    (uab/let-await
      [client (make-initialized-context)
       {content :content
        error? :isError} (mc/call-tool client "printEnv" {})]
      (is (= (get-in content [0 :type]) "text"))
      (is (-> (get-in content [0 :text])
              u/json-parse-str
              (get "PATH")
              string?))
      (is (not error?))
      (mc/disconnect! client)
      (done!))))


(deftest test-call-tool-sampleLLM
  (tu/async-test [done!]
    (uab/let-await
      [client (make-initialized-context)
       {content :content
        error? :isError} (mc/call-tool client "sampleLLM"
                                       {:prompt "Make some hay"
                                        :maxTokens 100})]
      (is (= content [{:type "text"
                       :text "LLM sampling result: Canned sampling text"}]))
      (is (not error?))
      (mc/disconnect! client)
      (done!))))


(deftest test-call-tool-getTinyImage
  (tu/async-test [done!]
    (uab/let-await
      [client (make-initialized-context)
       {content :content
        error? :isError} (mc/call-tool client "getTinyImage"
                                       {})]
      (is (= content
             [{:type "text" :text "This is a tiny image"}
              {:type "image" :data ev/mcp-tiny-image
               :mimeType "image/png"}
              {:type "text"
               :text "The image above is the MCP tiny image."}]))
      (is (not error?))
      (mc/disconnect! client)
      (done!))))


(deftest test-call-tool-annotatedMessage
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "annotatedMessage - error"
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "annotatedMessage"
                                       {:message-type "error"
                                        :include-image false})]
           (is (= content
                  [{:type "text" :text "Error: Operation failed"
                    :annotations {:audience ["user" "assistant"]
                                  :priority 1.0}}]))
           (is (not error?))))
       (testing "annotatedMessage - success"
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "annotatedMessage"
                                       {:message-type "success"
                                        :include-image false})]
           (is (= content
                  [{:type "text" :text "Operation completed successfully"
                    :annotations {:audience ["user"]
                                  :priority 0.7}}]))
           (is (not error?))))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest test-call-tool-getResourceReference
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "getResourceReference - odd numbered resource ID"
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "getResourceReference"
                                       {:resource-id 1})]
           (is (= content
                  [{:type "text"
                    :text "Returning resource reference for Resource 1"}
                   {:type "resource"
                    :resource {:uri "test://static/resource/1"
                               :text "Resource 1: This is a plaintext resource"
                               :mimeType "text/plain", :name "Resource 1"}}
                   {:type "text"
                    :text "You can access this resource using the URI: test://static/resource/1"}])
               "odd numbered resource ID")
           (is (not error?))))
       (testing "getResourceReference - even numbered resource ID"
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "getResourceReference"
                                       {:resource-id 2})]
           (is (= content
                  [{:type "text"
                    :text "Returning resource reference for Resource 2"}
                   {:type "resource"
                    :resource {:uri "test://static/resource/2"
                               :blob "UmVzb3VyY2UgMjogVGhpcyBpcyBhIGJhc2U2NCBibG9i"
                               :mimeType "application/octet-stream", :name "Resource 2"}}
                   {:type "text"
                    :text "You can access this resource using the URI: test://static/resource/2"}])
               "even numbered resource ID")
           (is (not error?))))
       (testing "getResourceReference - out of range resource ID"
         (uab/let-await [[_ error] (mc/call-tool client "getResourceReference"
                                                 {:resource-id 102}
                                                 {:on-error vector})]
           (is (= error
                  {:code -32602
                   :message "{:resource-id [\"should be at most 100\"]}\n"
                   :data {}})
               "out of range resource ID")))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest test-call-tool-startElicitation
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "startElicitation - accepted"
         (reset! elicit-action-atom sd/elicit-action-accept)
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "startElicitation"
                                       {})]
           (is (= content
                  [{:type "text", :text "✅ User provided their favorite things!"}
                   {:type "text",
                    :text
                    "Their favorites are:\n- Color: blue\n- Number: 42\n- Pets: dog"}
                   {:type "text",
                    :text
                    "\nRaw result: {:action \"accept\", :content {:color \"blue\", :number 42, :pets \"dog\"}}\n"}])
               "elicitation accepted")
           (is (not error?))))
       (testing "startElicitation - cancelled"
         (reset! elicit-action-atom sd/elicit-action-cancel)
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "startElicitation"
                                       {})]
           (is (= content
                  [{:type "text"
                    :text "⚠️ User cancelled the elicitation dialog."}
                   {:type "text"
                    :text "\nRaw result: {:action \"cancel\"}\n"}])
               "elicitation cancelled")
           (is (not error?))))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest call-tools-test-getResourceLinks
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "getResourceLinks - default param value"
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "getResourceLinks"
                                       {})]
           (is (= content
                  [{:type "text",
                    :text "Here are 3 resource links to resources available in this server (see full output in tool response if your client does not support resource_link yet):"}
                   {:uri "test://static/resource/1", :name "Resource 1", :type "resource_link"}
                   {:uri "test://static/resource/2", :name "Resource 2", :type "resource_link"}
                   {:uri "test://static/resource/3", :name "Resource 3", :type "resource_link"}])
               "default param value")
           (is (not error?))))
       (testing "getResourceLinks - specified param value"
         (uab/let-await [{content :content error? :isError}
                         (mc/call-tool client "getResourceLinks"
                                       {:count 2})]
           (is (= content
                  [{:type "text",
                    :text "Here are 2 resource links to resources available in this server (see full output in tool response if your client does not support resource_link yet):"}
                   {:uri "test://static/resource/1", :name "Resource 1", :type "resource_link"}
                   {:uri "test://static/resource/2", :name "Resource 2", :type "resource_link"}])
               "specified param value")
           (is (not error?))))
       (testing "getResourceLinks - error"
         (uab/let-await [[_ error]
                         (mc/call-tool client "getResourceLinks"
                                       {:count 0}
                                       {:on-error vector})]
           (is (= error {:code -32602
                         :message "{:count [\"should be at least 1\"]}\n"
                         :data {}})
               "count should be atleast 1")))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest read-server-resources-test
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "resource-2"
         (uab/let-await [{:keys [contents]}
                         (mc/read-resource client
                                           "test://static/resource/1")]
           (is (= contents
                  [{:uri "test://static/resource/2"
                    :blob "UmVzb3VyY2UgMjogVGhpcyBpcyBhIGJhc2U2NCBibG9i"
                    :mimeType "application/octet-stream"
                    :name "Resource 2"}]))))
       (testing "resource-100"
         (uab/let-await [[_ error]
                         (mc/read-resource client
                                           "test://static/resource/100"
                                           {:on-error vector})]
           (is (= error
                  {:code -32602
                   :message "Unknown resource: test://static/resource/100"
                   :data {}})
               "Unknown resource: test://static/resource/100")))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest test-get-prompt-simple
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)
                    {:keys [messages
                            description]} (mc/get-prompt client "simple_prompt"
                                                         {})]
      (is (= messages
             [{:role "user"
               :content {:type "text"
                         :text "This is a simple prompt without arguments."}}]))
      (is (nil? description))
      (mc/disconnect! client)
      (done!))))


(deftest test-get-prompt-complex
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "success message"
         (uab/let-await [{:keys [messages description]}
                         (mc/get-prompt client "complex_prompts"
                                        {:temperature "100"
                                         :style "modern"})]
           (is (= messages
                  [{:role "user"
                    :content {:type "text"
                              :text "This is a complex prompt with arguments: temperature=100, style=modern"}}
                   {:role "assistant"
                    :content {:type "text"
                              :text "I understand. You've provided a complex prompt with temperature and style arguments. How would you like me to proceed?"}}
                   {:role "user"
                    :content {:type "image"
                              :data ev/mcp-tiny-image
                              :mimeType "image/png"}}])
               "success message")
           (is (nil? description))))
       (testing "success message without optional kwarg"
         (uab/let-await [{:keys [messages description]}
                         (mc/get-prompt client "complex_prompts"
                                        {:temperature "100"})]
           (is (= messages
                  [{:role "user"
                    :content {:type "text"
                              :text "This is a complex prompt with arguments: temperature=100, style=null"}}
                   {:role "assistant"
                    :content {:type "text"
                              :text "I understand. You've provided a complex prompt with temperature and style arguments. How would you like me to proceed?"}}
                   {:role "user"
                    :content {:type "image"
                              :data ev/mcp-tiny-image
                              :mimeType "image/png"}}])
               "success message without optional kwarg")
           (is (nil? description))))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest test-get-prompt-resource
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)]
      (tu/async-do
       (testing "success message"
         (uab/let-await [{:keys [messages description]}
                         (mc/get-prompt client "resource_prompt"
                                        {:resourceId "50"})]
           (is (= messages
                  [{:role "user"
                    :content {:type "text"
                              :text "This prompt includes Resource 50. Please analyze the following resource:"}}
                   {:role "user"
                    :content {:type "resource"
                              :resource {:uri "test://static/resource/50"
                                         :blob "UmVzb3VyY2UgNTA6IFRoaXMgaXMgYSBiYXNlNjQgYmxvYg=="
                                         :mimeType "application/octet-stream"
                                         :name "Resource 50"}}}])
               "success message")
           (is (nil? description))))
       (testing "invalid kwarg type (must be string)"
         (uab/let-await [[_ error]
                         (mc/get-prompt client "resource_prompt"
                                        {:resourceId 50}
                                        {:on-error vector})]
           (is (= error
                  {:code -32602
                   :message "JSON-RPC Request validation error"
                   :data {:params {:arguments {:resourceId ["should be a string"]}}}})
               "invalid kwarg type (must be string)")))
       (testing "kwarg out of range (must be 1-100)"
         (uab/let-await [[_ error]
                         (mc/get-prompt client "resource_prompt"
                                        {:resourceId "200"}
                                        {:on-error vector})]
           (is (= error
                  {:code -32602
                   :message "Invalid resourceId: 200. Must be a number between 1 and 100."
                   :data {}})
               "kwarg out of range (must be 1-100)")))
       ;; All done, disconnect now
       (mc/disconnect! client)
       (done!)))))


(deftest ping-test
  (tu/async-test [done!]
    (uab/let-await [client (make-initialized-context)
                    pong (mc/ping client)]
      (is (= pong {}) "handler is called")
      (mc/disconnect! client)
      (done!))))


;; TODO: add other remaining tests
