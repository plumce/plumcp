;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.capability-test
  "Client and Server capability tests"
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocols :as p]
   [plumcp.core.schema.schema-defs :as sd]))


;; --- Client capability ---


(def root-one (-> "file:///home/user/projects/myproject"
                  (eg/make-root {:name "My Project"})
                  cap/make-roots-capability-item))


(deftest roots-capability-test
  (testing "empty/no roots"
    (let [roots-cap (cap/make-fixed-roots-capability [])]
      (is (= {:listChanged true}
             (p/get-capability-declaration roots-cap)))
      (is (= []
             (p/obtain-list roots-cap sd/method-roots-list)))
      (is (= nil
             (p/find-handler roots-cap {})))))
  (testing "1 root"
    (let [roots-cap (-> [root-one]
                        (cap/make-fixed-roots-capability))]
      (is (= {:listChanged true}
             (p/get-capability-declaration roots-cap)))
      (is (= [root-one]
             (p/obtain-list roots-cap sd/method-roots-list)))
      (is (= nil
             (p/find-handler roots-cap {}))))))


(deftest sampling-capability-test
  (let [sampling-cap (cap/make-sampling-capability identity)]
    (is (= {}
           (p/get-capability-declaration sampling-cap)))
    (is (= :foo
           (p/get-sampling-response sampling-cap :foo)))))


(deftest elicitation-capability-test
  (let [elicitation-cap (cap/make-elicitation-capability identity)]
    (is (= {}
           (p/get-capability-declaration elicitation-cap)))
    (is (= :foo
           (p/get-elicitation-response elicitation-cap :foo)))))


(deftest client-capabilities-test
  (testing "default capabilities"
    (let [default-caps cap/default-client-capabilities]
      (is (= {}
             (cap/get-client-capability-declaration default-caps)))))
  (testing "all capabilities"
    (let [all-caps {:roots (-> [root-one]
                               (cap/make-fixed-roots-capability))
                    :sampling (cap/make-sampling-capability identity)
                    :elicitation (cap/make-elicitation-capability identity)}]
      (is (= {:roots {:listChanged true}
              :sampling {}
              :elicitation {}}
             (cap/get-client-capability-declaration all-caps))))))


;; --- Server capability ---


(deftest logging-capability-test
  (is (= {}
         (p/get-capability-declaration cap/logging-capability))))


(deftest prompts-capability-test
  (testing "empty/no prompts"
    (let [prompts-cap (cap/make-fixed-prompts-capability [])]
      (is (= {:listChanged true}
             (p/get-capability-declaration prompts-cap)))
      (is (= []
             (p/obtain-list prompts-cap sd/method-prompts-list)))
      (is (nil? (p/find-handler prompts-cap "absent-prompt")))))
  (testing "one prompt"
    (let [prompt1-name "prompt-1"
          prompt1-handler identity
          prompt1 (-> (eg/make-prompt prompt1-name)
                      (cap/make-prompts-capability-item prompt1-handler))
          prompts-cap (cap/make-fixed-prompts-capability [prompt1])]
      (is (= {:listChanged true}
             (p/get-capability-declaration prompts-cap)))
      (is (= [(dissoc prompt1 :handler)]
             (p/obtain-list prompts-cap sd/method-prompts-list)))
      (let [handler-map (p/find-handler prompts-cap prompt1-name)
            handler (:handler handler-map)]
        (is (map? handler-map) "lookup by prompt1-name returns a handler map")
        (is (fn? handler) "prompt1 handler is a function")
        (is (= 10 (handler 10)) "prompt1 handler executes as expected"))))
  (testing "two prompts"
    (let [prompt1-name "prompt-1"
          prompt1-handler (fn [^long n] (+ 1 n))
          prompt1 (-> (eg/make-prompt prompt1-name)
                      (cap/make-prompts-capability-item prompt1-handler))
          prompt2-name "prompt-2"
          prompt2-handler (fn [^long n] (+ 2 n))
          prompt2 (-> (eg/make-prompt prompt2-name)
                      (cap/make-prompts-capability-item prompt2-handler))
          prompts-cap (cap/make-fixed-prompts-capability [prompt1
                                                          prompt2])
          handler-map (p/find-handler prompts-cap prompt2-name)
          handler (:handler handler-map)]
      (is (fn? handler) "prompt2 handler is a function")
      (is (= 12 (handler 10)) "prompt2 handler is called"))))


(deftest resources-capability-test
  (testing "empty/no resources"
    (let [resources-cap (cap/make-fixed-resources-capability [] [])]
      (is (= {:listChanged true
              :subscribe true}
             (p/get-capability-declaration resources-cap)))
      (is (= []
             (p/obtain-list resources-cap sd/method-resources-list)))
      (is (nil? (p/find-handler resources-cap "absent-resource")))))
  (testing "one resource, one resource template"
    (let [res1-name "res1"
          res1-url "resource://res1"
          res1-handler (fn [{:keys [uri]}] (str uri "::resource-1"))
          res1 (-> (eg/make-resource res1-url res1-name)
                   (cap/make-resources-capability-resource-item res1-handler))
          tem1-name "tem1"
          tem1-url-tem "resource://restem/{id}"
          tem1-url-one "resource://restem/100"
          tem1-handler (fn [{:keys [uri params]}] (str uri
                                                       "::templated-resource-"
                                                       (:id params)))
          tem1 (-> (eg/make-resource-template tem1-url-tem tem1-name)
                   (cap/make-resources-capability-resource-template-item tem1-handler))
          the-cap (cap/make-fixed-resources-capability [res1] [tem1])
          ;; resource
          res-handler-map (p/find-handler the-cap res1-url)
          res-handler (:handler res-handler-map)
          ;; template
          tem-handler-map (p/find-handler the-cap tem1-url-one)
          tem-handler (:handler tem-handler-map)
          tem-params  (:params tem-handler-map)]
      ;; resource
      (is (map? res-handler-map))
      (is (fn? res-handler))
      (is (= "resource://res1::resource-1"
             (res-handler {:uri res1-url})))
      ;; template
      (is (map? tem-handler-map))
      (is (fn? tem-handler))
      (is (= {:id "100"} tem-params))
      (is (= (str tem1-url-one "::templated-resource-100")
             (tem-handler {:uri tem1-url-one
                           :params tem-params}))))))


(defn tool-add-handler
  [{:keys [^long a ^long b]}]
  (+ a b))


(def tool-add
  (as-> {"a" {:type "number" :description "first number"}
         "b" {:type "number" :description "second number"}} $
    (eg/make-tool-input-output-schema $ ["a" "b"])
    (eg/make-tool "add" $)
    (cap/make-tools-capability-item $ tool-add-handler)))


(deftest tools-capability-test
  (testing "empty/no tools"
    (let [tools-cap (cap/make-fixed-tools-capability [])]
      (is (= {:listChanged true}
             (p/get-capability-declaration tools-cap)))
      (is (= []
             (p/obtain-list tools-cap sd/method-tools-list)))
      (is (nil? (p/find-handler tools-cap "absent-tool")))))
  (testing "one tool"
    (let [tools-cap (cap/make-fixed-tools-capability [tool-add])
          handler-map (p/find-handler tools-cap "add")
          handler (:handler handler-map)]
      (is (= {:listChanged true}
             (p/get-capability-declaration tools-cap)))
      (is (= [(dissoc tool-add :handler)]
             (p/obtain-list tools-cap sd/method-tools-list)))
      (is (map? handler-map) "tool handler is found")
      (is (fn? handler) "tool handler is indeed a function we supplied")
      (is (= 30 (handler {:a 10 :b 20})) "tool handler works"))))


(deftest completion-capability-test
  (let [prompt-ref-item (cap/make-completions-reference-item
                         (eg/make-prompt-reference "test-prompt-ref")
                         (fn [{:keys [ref argument]}]
                           [:prompt argument]))
        resource-ref-item (cap/make-completions-reference-item
                           (eg/make-resource-template-reference "res://test")
                           (fn [{:keys [ref argument]}]
                             [:resource argument]))
        cap (cap/make-completions-capability [prompt-ref-item]
                                             [resource-ref-item])]
    (is (= {}
           (p/get-capability-declaration cap)))
    (is (= [:prompt :foo]
           (p/completion-complete cap prompt-ref-item :foo)))
    (is (= [:resource :foo]
           (p/completion-complete cap resource-ref-item :foo)))))


(deftest server-capabilities-test
  (testing "default capabilities"
    (let [default-caps cap/default-server-capabilities]
      (is (= {:logging {}}
             (cap/get-server-capability-declaration default-caps)))))
  (testing "all capabilities"
    (let [all-caps (merge cap/default-server-capabilities
                          {:prompts (cap/make-fixed-prompts-capability [])
                           :resources (cap/make-fixed-resources-capability []
                                                                           [])
                           :tools (-> [tool-add]
                                      (cap/make-fixed-tools-capability))})]
      (is (= {:logging {}
              :prompts {:listChanged true}
              :resources {:listChanged true :subscribe true}
              :tools {:listChanged true}}
             (cap/get-server-capability-declaration all-caps))))))
