;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.mcp-method-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.capability-support :as cs]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.impl.impl-method :as im]
   [plumcp.core.schema.schema-defs :as sd])
  #?(:clj (:import
           [clojure.lang ExceptionInfo])))


(def runtime-empty {})


(def runtime-client-caps
  (let [roots-cap (-> (eg/make-root "dir://a")
                      cap/make-roots-capability-item
                      vector
                      cap/make-fixed-roots-capability)
        sampling-cap (-> (fn [{messages :messages
                               max-tokens :maxTokens
                               :as kwargs}]
                           {:messages messages
                            :max-tokens max-tokens})
                         cap/make-sampling-capability)
        elicitation-cap (-> (fn [{message :message
                                  requested-schema :requestedSchema
                                  :as kwargs}]
                              {:message message
                               :requested-schema requested-schema})
                            cap/make-elicitation-capability)
        client-caps (-> cap/default-client-capabilities
                        (cap/update-roots-capability roots-cap)
                        (cap/update-sampling-capability sampling-cap)
                        (cap/update-elicitation-capability elicitation-cap))
        context (-> {}
                    (rt/?client-capabilities client-caps))]
    (rt/get-runtime context)))


(def missing-ref-item (cs/make-completions-reference-item
                       (eg/make-prompt-reference "missing-prompt-ref")
                       (fn [{:keys [ref argument]}]
                         [:prompt argument])))


(def prompt-ref-item (cs/make-completions-reference-item
                      (eg/make-prompt-reference "test-prompt-ref")
                      (fn [{:keys [ref argument]}]
                        [:prompt argument])))


(def resource-ref-item (cs/make-completions-reference-item
                        (eg/make-resource-template-reference "res://test")
                        (fn [{:keys [ref argument]}]
                          [:resource argument])))


(def runtime-server-caps
  "Runtime with server capabilities"
  (let [completions-cap (cap/make-completions-capability [prompt-ref-item]
                                                         [resource-ref-item])
        prompts-cap (-> (eg/make-prompt "prompt1")
                        (cap/make-prompts-capability-item (fn [kwargs]
                                                            {:out :prompt1}))
                        vector
                        cap/make-fixed-prompts-capability)
        resource-cap-item (-> (eg/make-resource "test://resource1" "resource1")
                              (cap/make-resources-capability-resource-item
                               (fn [{uri :uri
                                     :as kwargs}]
                                 {:out :resource1
                                  :uri uri})))
        template-cap-item (-> (eg/make-resource-template "test://res/{id}" "template1")
                              (cap/make-resources-capability-resource-template-item
                               (fn [{uri :uri
                                     {id :id} :params
                                     :as kwargs}]
                                 {:out :template1
                                  :uri uri
                                  :id id})))
        resources-cap (cap/make-fixed-resources-capability [resource-cap-item]
                                                           [template-cap-item])
        tools-cap (-> (eg/make-tool "tool1"
                                    (-> {"a" {:type "number" :description "first number"}
                                         "b" {:type "number" :description "second number"}}
                                        (eg/make-tool-input-output-schema ["a" "b"])))
                      (cap/make-tools-capability-item (fn [{:keys [^long a ^long b]}]
                                                        {:out (+ a b)}))
                      vector
                      cap/make-fixed-tools-capability)
        server-caps (-> cap/default-server-capabilities
                        (cap/update-completions-capability completions-cap)
                        (cap/update-prompts-capability prompts-cap)
                        (cap/update-resources-capability resources-cap)
                        (cap/update-tools-capability tools-cap))
        context (-> {}
                    (rt/?server-capabilities server-caps))]
    (rt/get-runtime context)))


(def runtime-server-session
  (let [context {}
        server-session (rs/set-server-session context :test-session-id
                                              (fn [context message]
                                                #_(u/eprintln "->Client:"
                                                              message)))
        context (-> context
                    (rt/upsert-runtime runtime-server-caps)
                    (rt/?session server-session))]
    (rs/set-initialized-timestamp context)
    (rt/get-runtime context)))


;; --- Server tests ---


(deftest logging-test
  (testing "logging"
    (is (thrown-with-msg?
         ExceptionInfo #"Expected container-map to have path.+"
         (-> (rt/upsert-runtime {} runtime-empty)
             (rs/log-0-emergency "test-log"))))
    (is (= nil
           (-> (rt/upsert-runtime {} runtime-server-session)
               (rs/log-0-emergency "test-log")))))
  (testing "setLevel (logging)"
    (is (= {:jsonrpc "2.0"
            :error {:code sd/error-code-invalid-request
                    :message "Initialization not done yet"
                    :data {}}}
           (-> (eg/make-logging-level "debug")
               eg/make-set-level-request
               (rt/upsert-runtime runtime-empty)
               im/logging-setLevel)))
    (is (= {:jsonrpc "2.0"
            :error {:code -32600
                    :message "Initialization not done yet"
                    :data {}}}
           (-> (eg/make-logging-level "debug")
               eg/make-set-level-request
               (rt/upsert-runtime runtime-server-caps)
               im/logging-setLevel)))
    (is (= {:result {}}
           (-> (eg/make-logging-level "debug")
               eg/make-set-level-request
               (rt/upsert-runtime runtime-server-session)
               im/logging-setLevel)))))


(deftest completions-test
  (testing sd/method-completion-complete
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'completions' not supported"
                                    :data {}}}
           (-> (eg/make-complete-request prompt-ref-item :foo :bar)
               (rt/upsert-runtime runtime-empty)
               im/completion-complete)) "no-caps, session-less")
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-invalid-params
                                    :message "Invalid or unsupported ref/prompt name"
                                    :data {:valid ["test-prompt-ref"]}}}
           (-> (eg/make-complete-request missing-ref-item :foo :bar)
               (rt/upsert-runtime runtime-server-session)
               im/completion-complete)) "with-session, missing ref item")
    (is (= {:result {:values [:prompt {:name :foo, :value :bar}], :total 2}}
           (-> (eg/make-complete-request prompt-ref-item :foo :bar)
               (rt/upsert-runtime runtime-server-session)
               im/completion-complete)) "no-caps, with-session")
    (is (= {:result {:values [:resource {:name :baz, :value :qux}], :total 2}}
           (-> (eg/make-complete-request resource-ref-item :baz :qux)
               (rt/upsert-runtime runtime-server-session)
               im/completion-complete)) "no-caps, with-session")))


(deftest prompts-test
  (testing sd/method-prompts-list
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'prompts' not supported"
                                    :data {}}}
           (-> (eg/make-list-prompts-request)
               (rt/upsert-runtime runtime-empty)
               im/prompts-list)) "no-caps, session-less")
    (is (= {:result {:prompts [{:name "prompt1"}]}}
           (-> (eg/make-list-prompts-request)
               (rt/upsert-runtime runtime-server-session)
               im/prompts-list)) "with-session"))
  (testing sd/method-prompts-get
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'prompts' not supported"
                                    :data {}}}
           (-> (eg/make-get-prompt-request "prompt1")
               (rt/upsert-runtime runtime-empty)
               im/prompts-get)) "no-caps, session-less")
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-invalid-params
                                    :message "Requested prompt-name does not exist"
                                    :data {:prompt-name "prompt2" :prompt-args nil}}}
           (-> (eg/make-get-prompt-request "prompt2")
               (rt/upsert-runtime runtime-server-session)
               im/prompts-get)) "no-caps, session-less")
    (is (= {:result {:out :prompt1}}
           (-> (eg/make-get-prompt-request "prompt1")
               (rt/upsert-runtime runtime-server-session)
               im/prompts-get
               (update :result rt/dissoc-runtime))) "with-session")))


(deftest resources-test
  (testing sd/method-resources-list
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'resources' not supported"
                                    :data {}}}
           (-> (eg/make-list-resources-request)
               (rt/upsert-runtime runtime-empty)
               im/resources-list)) "no-caps, session-less")
    (is (= {:result {:resources [{:uri "test://resource1" :name "resource1"}]}}
           (-> (eg/make-list-resources-request)
               (rt/upsert-runtime runtime-server-session)
               im/resources-list)) "with-session"))
  (testing sd/method-resources-templates-list
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'resources' not supported"
                                    :data {}}}
           (-> (eg/make-list-resource-templates-request)
               (rt/upsert-runtime runtime-empty)
               im/resources-templates-list)) "no-caps, session-less")
    (is (= {:result {:resourceTemplates [{:uriTemplate "test://res/{id}" :name "template1"}]}}
           (-> (eg/make-list-resource-templates-request)
               (rt/upsert-runtime runtime-server-session)
               im/resources-templates-list)) "with-session"))
  (testing sd/method-resources-read
    (testing "no capability"
      (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                      :message "Capability 'resources' not supported"
                                      :data {}}}
             (-> (eg/make-read-resource-request "test://resource1")
                 (rt/upsert-runtime runtime-empty)
                 im/resources-read)) "no-caps, session-less, resource")
      (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                      :message "Capability 'resources' not supported"
                                      :data {}}}
             (-> (eg/make-read-resource-request "test://res/100")
                 (rt/upsert-runtime runtime-empty)
                 im/resources-read)) "no-caps, session-less, resource-template"))
    (testing "bad argument"
      (is (= {:jsonrpc "2.0", :error {:code sd/error-code-invalid-params
                                      :message "Requested invalid resource URI"
                                      :data {:uri "test://resource2"}}}
             (-> (eg/make-read-resource-request "test://resource2")
                 (rt/upsert-runtime runtime-server-session)
                 im/resources-read)) "no-caps, session-less, resource")
      (is (= {:jsonrpc "2.0", :error {:code sd/error-code-invalid-params
                                      :message "Requested invalid resource URI"
                                      :data {:uri "test://bad/100"}}}
             (-> (eg/make-read-resource-request "test://bad/100")
                 (rt/upsert-runtime runtime-server-session)
                 im/resources-read)) "no-caps, session-less, resource-template"))
    (testing "genuine arguments"
      (is (= {:result {:out :resource1
                       :uri "test://resource1"}}
             (-> (eg/make-read-resource-request "test://resource1")
                 (rt/upsert-runtime runtime-server-session)
                 im/resources-read
                 (update :result rt/dissoc-runtime))) "with-session. resource")
      (is (= {:result {:out :template1
                       :uri "test://res/100"
                       :id "100"}}
             (-> (eg/make-read-resource-request "test://res/100")
                 (rt/upsert-runtime runtime-server-session)
                 im/resources-read
                 (update :result rt/dissoc-runtime))) "with-session, template"))))


(deftest tools-test
  (testing sd/method-tools-list
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'tools' not supported"
                                    :data {}}}
           (-> (eg/make-list-tools-request)
               (rt/upsert-runtime runtime-empty)
               im/tools-list)) "no-caps, session-less")
    (is (= {:result {:tools [{:name "tool1"
                              :inputSchema {:type "object",
                                            :properties {"a" {:type "number", :description "first number"},
                                                         "b" {:type "number", :description "second number"}},
                                            :required ["a" "b"]}}]}}
           (-> (eg/make-list-tools-request)
               (rt/upsert-runtime runtime-server-session)
               im/tools-list)) "with-session"))
  (testing sd/method-tools-call
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-method-not-found
                                    :message "Capability 'tools' not supported"
                                    :data {}}}
           (-> (eg/make-call-tool-request "tool1" {:a 10 :b 20})
               (rt/upsert-runtime runtime-empty)
               im/tools-call)) "no-caps, session-less")
    (is (= {:jsonrpc "2.0", :error {:code sd/error-code-invalid-params
                                    :message "Unrecognized tool: tool2"
                                    :data {:name "tool2"
                                           :arguments {:a 10, :b 20}}}}
           (-> (eg/make-call-tool-request "tool2" {:a 10 :b 20})
               (rt/upsert-runtime runtime-server-session)
               im/tools-call
               (update-in [:error :data] dissoc :_meta))) "with-session")
    (is (= {:result {:out 30}}
           (-> (eg/make-call-tool-request "tool1" {:a 10 :b 20})
               (rt/upsert-runtime runtime-server-session)
               im/tools-call
               (update :result rt/dissoc-runtime))) "with-session")))


;; --- Client tests ---


(deftest roots-test
  (testing sd/method-roots-list
    (is (= {:jsonrpc "2.0"
            :error {:code -32601
                    :message "Capability 'roots' not supported"
                    :data {}}}
           (-> (eg/make-list-roots-request)
               (rt/upsert-runtime runtime-empty)
               im/roots-list)))
    (is (= {:result {:roots [{:uri "dir://a"}]}}
           (-> (eg/make-list-roots-request)
               (rt/upsert-runtime runtime-client-caps)
               im/roots-list)))))


(deftest sampling-test
  (testing sd/method-sampling-createMessage
    (is (= {:jsonrpc "2.0"
            :error {:code -32601
                    :message "Capability 'sampling' not supported"
                    :data {}}}
           (-> (eg/make-sampling-message sd/role-user
                                         (eg/make-text-content "test"))
               vector
               (eg/make-create-message-request 100)
               (rt/upsert-runtime runtime-empty)
               im/sampling-createMessage)))
    (is (= {:result {:messages [{:role "user"
                                 :content {:type "text", :text "test"}}]
                     :max-tokens 100}}
           (-> (eg/make-sampling-message sd/role-user
                                         (eg/make-text-content "test"))
               vector
               (eg/make-create-message-request 100)
               (rt/upsert-runtime runtime-client-caps)
               im/sampling-createMessage)))))


(deftest elicitation-test
  (testing sd/method-elicitation-create
    (is (= {:jsonrpc "2.0"
            :error {:code -32601
                    :message "Capability 'elicitation' not supported"
                    :data {}}}
           (-> "elicitation-message"
               (eg/make-elicit-request {})
               (rt/upsert-runtime runtime-empty)
               im/elicitation-create)))
    (is (= {:result {:message "elicitation-message"
                     :requested-schema {:type "object", :properties {}}}}
           (-> "elicitation-message"
               (eg/make-elicit-request {})
               (rt/upsert-runtime runtime-client-caps)
               im/elicitation-create)))))


;; --- Other tests ---


(deftest ping-test
  (testing "ping"
    (is (= {:result {}}
           (-> (eg/make-ping-request)
               im/ping)) "session-less")
    (is (= {:result {}}
           (-> (eg/make-ping-request)
               (rt/upsert-runtime runtime-server-session)
               im/ping)) "with-session")))


(deftest notification-test
  (testing "server-only notification handlers"
    (is (= {:result {}}
           (-> sd/method-notifications-initialized
               eg/make-notification
               (rt/upsert-runtime runtime-server-session)
               im/notifications-initialized)))
    (is (= {:result {}}
           (-> sd/method-notifications-roots-list_changed
               eg/make-notification
               (rt/upsert-runtime runtime-client-caps)
               im/notifications-roots-list_changed))))
  (testing "client-only notification handlers"
    (is (= {:result {}}
           (-> sd/method-notifications-resources-list_changed
               eg/make-notification
               (rt/upsert-runtime runtime-server-session)
               im/notifications-resources-list_changed)))
    (is (= {:result {}}
           (-> sd/method-notifications-resources-updated
               eg/make-notification
               (rt/upsert-runtime runtime-server-session)
               im/notifications-resources-updated)))
    (is (= {:result {}}
           (-> sd/method-notifications-prompts-list_changed
               eg/make-notification
               (rt/upsert-runtime runtime-server-session)
               im/notifications-prompts-list_changed)))
    (is (= {:result {}}
           (-> sd/method-notifications-tools-list_changed
               eg/make-notification
               (rt/upsert-runtime runtime-server-session)
               im/notifications-tools-list_changed)))
    (is (= {:result {}}
           (-> sd/method-notifications-message
               eg/make-notification
               (rt/upsert-runtime runtime-server-session)
               im/notifications-message)) "log message"))
  (testing "client/server notification handlers"
    (testing sd/method-notifications-cancelled
      (is (= {:result {}}
             (-> sd/method-notifications-cancelled
                 eg/make-notification
                 (rt/upsert-runtime runtime-client-caps)
                 im/notifications-cancelled)) "client-received")
      (is (= {:result {}}
             (-> sd/method-notifications-cancelled
                 eg/make-notification
                 (rt/upsert-runtime runtime-server-session)
                 im/notifications-cancelled)) "server-received"))
    (testing sd/method-notifications-progress
      (is (= {:result {}}
             (-> sd/method-notifications-progress
                 eg/make-notification
                 (rt/upsert-runtime runtime-client-caps)
                 im/notifications-progress)) "client-received")
      (is (= {:result {}}
             (-> sd/method-notifications-progress
                 eg/make-notification
                 (rt/upsert-runtime runtime-server-session)
                 im/notifications-progress)) "server-received"))))
