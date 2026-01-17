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
   [plumcp.core.main.client :as client]
   [plumcp.core.main.server :as server]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]
   [plumcp.core.deps.runtime :as rt])
  #?(:clj (:import
           [clojure.lang ExceptionInfo])))


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
  (-> {:client-transport (make-zero-transport server/server-options)}
      (merge client/client-options)
      mc/make-client
      (doto mc/initialize-and-notify!)))


(deftest list-server-primitives-test
  (let [client-context (make-initialized-context)]
    (testing sd/method-tools-list
      (->> (fn [tools-list]
             (is (vector? tools-list) "tools is a vector")
             (let [ev-tools (->> ev/tools
                                 (mapv #(dissoc % :handler))
                                 (sort-by :name))
                   tl-tools (->> tools-list
                                 (sort-by :name))]
               (is (= tl-tools ev-tools)
                   "retrieved/existing tools should match")))
           (mc/list-tools client-context)))
    (testing sd/method-prompts-list
      (->> (fn [prompts-list]
             (is (vector? prompts-list))
             (let [ev-prompts (->> ev/prompts
                                   (mapv #(dissoc % :handler))
                                   (sort-by :name))
                   pl-prompts (->> prompts-list
                                   (sort-by :name))]
               (is (= pl-prompts ev-prompts)
                   "retrieved/existing prompts should match"))
             (u/dprint "Prompts-list result" prompts-list))
           (mc/list-prompts client-context)))
    (testing sd/method-resources-list
      (->> (fn [resources-list]
             (is (vector? resources-list))
             (let [ev-resources (->> ev/resources
                                     (mapv #(dissoc % :handler))
                                     (sort-by :name))
                   rl-resources (->> resources-list
                                     (sort-by :name))]
               (is (= rl-resources ev-resources)
                   "retrieved/existing resources should match")))
           (mc/list-resources client-context)))
    (testing sd/method-resources-templates-list
      (->> (fn [resource-templates-list]
             (is (vector? resource-templates-list))
             (let [ev-rts (->> ev/resource-templates
                               (mapv #(dissoc % :handler))
                               (sort-by :name))
                   rl-rts (->> resource-templates-list
                               (sort-by :name))]
               (is (= rl-rts ev-rts)
                   "retrieved/existing resource-templates should match")))
           (mc/list-resource-templates client-context)))
    (mc/disconnect! client-context)))


(deftest call-tools-test
  (let [client-context (make-initialized-context)]
    (testing "echo"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text"
                              :text "Hi there"}]))
             (is (not error?)))
           (mc/call-tool client-context "echo"
                         {:message "Hi there"})))
    (testing "add"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text"
                              :text "The sum of 10 and 20 is 30"}]))
             (is (not error?)))
           (mc/call-tool client-context "add"
                         {:a 10 :b 20})))
    (testing "longRunningOperation"
      (tu/until-done [done! 10]
        (->> (fn [{content :content error?  :isError}] ; call-tool-result
               (is (= content [{:type "text"
                                :text "Long running operation completed. Duration: 4 seconds, Steps: 4."}]))
               (is (not error?))
               (done!))
             (mc/call-tool client-context "longRunningOperation"
                           {:duration 4 :steps 4}))))
    (testing "printEnv"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= (get-in content [0 :type]) "text"))
             (is (-> (get-in content [0 :text])
                     u/json-parse-str
                     (get "PATH")
                     string?))
             (is (not error?)))
           (mc/call-tool client-context "printEnv"
                         {})))
    (testing "sampleLLM"
      (tu/until-done [done! 10]
        (->> (fn [{content :content error?  :isError}] ; call-tool-result
               (is (= content [{:type "text"
                                :text "LLM sampling result: Canned sampling text"}]))
               (is (not error?))
               (done!))
             (mc/call-tool client-context "sampleLLM"
                           {:prompt "Make some hay" :maxTokens 100}))))
    (testing "getTinyImage"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text" :text "This is a tiny image"}
                             {:type "image" :data ev/mcp-tiny-image
                              :mimeType "image/png"}
                             {:type "text" :text "The image above is the MCP tiny image."}]))
             (is (not error?)))
           (mc/call-tool client-context "getTinyImage"
                         {})))
    (testing "annotatedMessage"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text" :text "Error: Operation failed"
                              :annotations {:audience ["user" "assistant"]
                                            :priority 1.0}}]))
             (is (not error?)))
           (mc/call-tool client-context "annotatedMessage"
                         {:message-type "error"
                          :include-image false}))
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text" :text "Operation completed successfully"
                              :annotations {:audience ["user"]
                                            :priority 0.7}}]))
             (is (not error?)))
           (mc/call-tool client-context "annotatedMessage"
                         {:message-type "success"
                          :include-image false})))
    (testing "getResourceReference"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text"
                              :text "Returning resource reference for Resource 1"}
                             {:type "resource"
                              :resource {:uri "test://static/resource/1"
                                         :text "Resource 1: This is a plaintext resource"
                                         :mimeType "text/plain", :name "Resource 1"}}
                             {:type "text"
                              :text "You can access this resource using the URI: test://static/resource/1"}])
                 "odd numbered resource ID")
             (is (not error?)))
           (mc/call-tool client-context "getResourceReference"
                         {:resource-id 1}))
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content [{:type "text"
                              :text "Returning resource reference for Resource 2"}
                             {:type "resource"
                              :resource {:uri "test://static/resource/2"
                                         :blob "UmVzb3VyY2UgMjogVGhpcyBpcyBhIGJhc2U2NCBibG9i"
                                         :mimeType "application/octet-stream", :name "Resource 2"}}
                             {:type "text"
                              :text "You can access this resource using the URI: test://static/resource/2"}])
                 "even numbered resource ID")
             (is (not error?)))
           (mc/call-tool client-context "getResourceReference"
                         {:resource-id 2}))
      (is (thrown-with-msg?
           ExceptionInfo #"\{:resource-id \[\"should be at most 100\"\]\}\n"
           (->> (fn [{content :content error?  :isError}] ; call-tool-result
                  (is (= content [])
                      "out of range resource ID")
                  (is error?))
                (mc/call-tool client-context "getResourceReference"
                              {:resource-id 102})))))
    (testing "startElicitation"
      (tu/until-done [done! 10]
        (reset! elicit-action-atom sd/elicit-action-accept)
        (->> (fn [{content :content error?  :isError}] ; call-tool-result
               (is (= content [{:type "text", :text "✅ User provided their favorite things!"}
                               {:type "text",
                                :text
                                "Their favorites are:\n- Color: blue\n- Number: 42\n- Pets: dog"}
                               {:type "text",
                                :text
                                "\nRaw result: {:action \"accept\", :content {:color \"blue\", :number 42, :pets \"dog\"}}\n"}])
                   "elicitation accepted")
               (is (not error?))
               (done!))
             (mc/call-tool client-context "startElicitation"
                           {})))
      (tu/until-done [done! 10]
        (reset! elicit-action-atom sd/elicit-action-cancel)
        (->> (fn [{content :content error?  :isError}] ; call-tool-result
               (is (= content
                      [{:type "text"
                        :text "⚠️ User cancelled the elicitation dialog."}
                       {:type "text"
                        :text "\nRaw result: {:action \"cancel\"}\n"}])
                   "elicitation cancelled")
               (is (not error?))
               (done!))
             (mc/call-tool client-context "startElicitation"
                           {})))
      (tu/until-done [done! 10]
        (reset! elicit-action-atom sd/elicit-action-decline)
        (->> (fn [{content :content error?  :isError}] ; call-tool-result
               (is (= content
                      [{:type "text",
                        :text "❌ User declined to provide their favorite things."}
                       {:type "text"
                        :text "\nRaw result: {:action \"decline\"}\n"}])
                   "elicitation declined")
               (is (not error?))
               (done!))
             (mc/call-tool client-context "startElicitation"
                           {}))))
    (testing "getResourceLinks"
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content
                    [{:type "text",
                      :text "Here are 3 resource links to resources available in this server (see full output in tool response if your client does not support resource_link yet):"}
                     {:uri "test://static/resource/1", :name "Resource 1", :type "resource_link"}
                     {:uri "test://static/resource/2", :name "Resource 2", :type "resource_link"}
                     {:uri "test://static/resource/3", :name "Resource 3", :type "resource_link"}])
                 "default param value")
             (is (not error?)))
           (mc/call-tool client-context "getResourceLinks"
                         {}))
      (->> (fn [{content :content error?  :isError}] ; call-tool-result
             (is (= content
                    [{:type "text",
                      :text "Here are 2 resource links to resources available in this server (see full output in tool response if your client does not support resource_link yet):"}
                     {:uri "test://static/resource/1", :name "Resource 1", :type "resource_link"}
                     {:uri "test://static/resource/2", :name "Resource 2", :type "resource_link"}])
                 "specified param value")
             (is (not error?)))
           (mc/call-tool client-context "getResourceLinks"
                         {:count 2}))
      (is (thrown-with-msg?
           ExceptionInfo #"\{:count \[\"should be at least 1\"\]\}\n"
           (->> (fn [{content :content error?  :isError}] ; call-tool-result
                  (is (= content [])
                      "validated (erroneous) param value")
                  (is (not error?)))
                (mc/call-tool client-context "getResourceLinks"
                              {:count 0})))))))


(deftest read-server-resources-test
  (let [client-context (make-initialized-context)]
    (testing "resource-2"
      (tu/until-done [done! 10]
        (->> (fn [{:keys [contents]}]
               (is (= contents
                      [{:uri "test://static/resource/2"
                        :blob "UmVzb3VyY2UgMjogVGhpcyBpcyBhIGJhc2U2NCBibG9i"
                        :mimeType "application/octet-stream"
                        :name "Resource 2"}]))
               (done!))
             (mc/read-resource client-context
                               "test://static/resource/1"))))
    (testing "resource-100"
      (is (thrown-with-msg?
           ExceptionInfo #"Unknown resource: test://static/resource/100"
           (->> (fn [_] (u/throw! "Unreachable code"))
                (mc/read-resource client-context
                                  "test://static/resource/100")))))))


(deftest get-prompts-test
  (let [client-context (make-initialized-context)]
    (testing "simple-prompt"
      (->> (fn [{:keys [messages description]}]
             (is (= messages
                    [{:role "user"
                      :content {:type "text"
                                :text "This is a simple prompt without arguments."}}]))
             (is (nil? description)))
           (mc/get-prompt client-context "simple_prompt"
                          {})))
    (testing "complex-prompts"
      (->> (fn [{:keys [messages description]}]
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
             (is (nil? description)))
           (mc/get-prompt client-context "complex_prompts"
                          {:temperature "100"
                           :style "modern"}))
      (->> (fn [{:keys [messages description]}]
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
             (is (nil? description)))
           (mc/get-prompt client-context "complex_prompts"
                          {:temperature "100"})))
    (testing "resource-prompts"
      (->> (fn [{:keys [messages description]}]
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
             (is (nil? description)))
           (mc/get-prompt client-context "resource_prompt"
                          {:resourceId "50"}))
      (is (thrown-with-msg?
           ExceptionInfo #"JSON-RPC Request validation error"
           (->> (fn [_] (u/throw! "Unreachable code"))
                (mc/get-prompt client-context "resource_prompt"
                               {:resourceId 50})))
          "invalid kwarg type (must be string)")
      (is (thrown-with-msg?
           ExceptionInfo #"Invalid resourceId: 200. Must be a number between 1 and 100."
           (->> (fn [_] (u/throw! "Unreachable code"))
                (mc/get-prompt client-context "resource_prompt"
                               {:resourceId "200"})))
          "kwarg out of range (must be 1-100)"))))


(deftest ping-test
  (let [client-context (make-initialized-context)]
    (testing "ping"
      (tu/until-done [done! 10]
        (->> (fn [result]
               (is (= result {}) "handler is called")
               (done!))
             (mc/ping client-context))))))


;; TODO: add other remaining tests
