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
   [plumcp.core.api.capability-support :as cs]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.test.test-util :as tu]))


;; --- Client capability ---


(def root-one (cs/make-root-item "file:///home/user/projects/myproject1"
                                 {:name "My Project1"}))


(def root-two (cs/make-root-item "file:///home/user/projects/myproject2"
                                 {:name "My Project2"}))


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
             (p/find-handler roots-cap {})))))
  (testing "mutable roots"
    (let [roots-ref (atom [root-one])
          roots-cap (-> roots-ref
                        (cap/make-deref-roots-capability))]
      (is (= {:listChanged true}
             (p/get-capability-declaration roots-cap)))
      (is (= [root-one]
             (p/obtain-list roots-cap sd/method-roots-list)))
      (swap! roots-ref conj root-two)
      (is (= [root-one
              root-two]
             (p/obtain-list roots-cap sd/method-roots-list))))))


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
          prompt1-handler (fn [{:keys [arg]}]
                            (-> sd/role-user
                                (es/make-text-prompt-message arg)
                                (es/prompt-message->get-prompt-result)))
          prompt1 (cs/make-prompt-item prompt1-name prompt1-handler)
          prompts-cap (cap/make-fixed-prompts-capability [prompt1])]
      (is (= {:listChanged true}
             (p/get-capability-declaration prompts-cap)))
      (is (= [(dissoc prompt1 :handler)]
             (p/obtain-list prompts-cap sd/method-prompts-list)))
      (let [handler-map (p/find-handler prompts-cap prompt1-name)
            handler (:handler handler-map)]
        (is (map? handler-map) "lookup by prompt1-name returns a handler map")
        (is (fn? handler) "prompt1 handler is a function")
        (is (= {:messages [{:role "user", :content {:type "text", :text "10"}}]}
               (handler {:arg "10"})) "prompt1 handler executes as expected"))))
  (testing "two prompts"
    (let [prompt1-name "prompt-1"
          prompt1-handler (fn [{:keys [^long n]}]
                            (-> sd/role-user
                                (es/make-text-prompt-message (+ 1 n))
                                (es/prompt-message->get-prompt-result)))
          prompt1 (cs/make-prompt-item prompt1-name prompt1-handler)
          prompt2-name "prompt-2"
          prompt2-handler (fn [{:keys [^long n]}]
                            (-> sd/role-user
                                (es/make-text-prompt-message (+ 2 n))
                                (es/prompt-message->get-prompt-result)))
          prompt2 (cs/make-prompt-item prompt2-name prompt2-handler)
          prompts-cap (cap/make-fixed-prompts-capability [prompt1
                                                          prompt2])
          handler-map (p/find-handler prompts-cap prompt2-name)
          handler (:handler handler-map)]
      (is (fn? handler) "prompt2 handler is a function")
      (is (= {:messages [{:role "user", :content {:type "text", :text "12"}}]}
             (handler {:n 10})) "prompt2 handler is called")))
  (testing "mutable prompts"
    (let [prompt1-name "prompt-1"
          prompt2-name "prompt-2"
          prompt1 (cs/make-prompt-item prompt1-name identity)
          prompt2 (cs/make-prompt-item prompt2-name identity)
          prompts-ref (atom [prompt1])
          prompts-cap (-> prompts-ref
                          (cap/make-deref-prompts-capability))]
      (is (= {:listChanged true}
             (p/get-capability-declaration prompts-cap)))
      (is (= [(dissoc prompt1 :handler)]
             (p/obtain-list prompts-cap sd/method-prompts-list)))
      (swap! prompts-ref conj prompt2)
      (is (= [(dissoc prompt1 :handler)
              (dissoc prompt2 :handler)]
             (p/obtain-list prompts-cap sd/method-prompts-list))))))


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
          res1-handler (fn [{:keys [uri]}]
                         (->> (str uri "::resource-1")
                              (es/make-text-resource-result uri)))
          res1 (cs/make-resource-item res1-url res1-name res1-handler)
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
      (is (= {:contents [{:uri "resource://res1"
                          :text "resource://res1::resource-1"}]}
             (res-handler {:uri res1-url})))
      ;; template
      (is (map? tem-handler-map))
      (is (fn? tem-handler))
      (is (= {:id "100"} tem-params))
      (is (= (str tem1-url-one "::templated-resource-100")
             (tem-handler {:uri tem1-url-one
                           :params tem-params})))))
  (testing "mutable resources"
    (let [hh (fn [{:keys [uri]}]
               (es/make-text-resource-result uri uri))
          rs1 (cs/make-resource-item "test://res1" "res1" hh)
          rs2 (cs/make-resource-item "test://res2" "res2" hh)
          rt1 (cs/make-resource-template-item "test://rt1/{id}" "rt1" hh)
          rt2 (cs/make-resource-template-item "test://rt2/{id}" "rt2" hh)
          rsref (atom [rs1])
          rtref (atom [rt1])
          rcap (cap/make-deref-resources-capability rsref rtref)]
      (is (= [(dissoc rs1 :handler :matcher)]
             (p/obtain-list rcap sd/method-resources-list)))
      (is (= [(dissoc rt1 :handler :matcher)]
             (p/obtain-list rcap sd/method-resources-templates-list)))
      (swap! rsref conj rs2)
      (swap! rtref conj rt2)
      (is (= [(dissoc rs1 :handler :matcher)
              (dissoc rs2 :handler :matcher)]
             (p/obtain-list rcap sd/method-resources-list)))
      (is (= [(dissoc rt1 :handler :matcher)
              (dissoc rt2 :handler :matcher)]
             (p/obtain-list rcap sd/method-resources-templates-list))))))


(defn tool-add-handler
  [{:keys [^long a ^long b]}]
  (+ a b))


(def tool-add
  (cs/make-tool-item "add"
                     (-> {"a" {:type "number" :description "first number"}
                          "b" {:type "number" :description "second number"}}
                         (eg/make-tool-input-output-schema ["a" "b"]))
                     tool-add-handler))


(defn tool-mul-handler
  [{:keys [^long a ^long b]}]
  (* a b))


(def tool-mul
  (cs/make-tool-item "add"
                     (-> {"a" {:type "number" :description "first number"}
                          "b" {:type "number" :description "second number"}}
                         (eg/make-tool-input-output-schema ["a" "b"]))
                     tool-mul-handler))


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
      (is (= {:content [{:type "text", :text "30"}], :isError false}
             (handler {:a 10 :b 20})) "tool handler works")))
  (testing "mutable tools"
    (let [tools-ref (atom [tool-add])
          tools-cap (cap/make-deref-tools-capability tools-ref)]
      (is (= {:listChanged true}
             (p/get-capability-declaration tools-cap)))
      (is (= [(dissoc tool-add :handler)]
             (p/obtain-list tools-cap sd/method-tools-list)))
      (swap! tools-ref conj tool-mul)
      (is (= [(dissoc tool-add :handler)
              (dissoc tool-mul :handler)]
             (p/obtain-list tools-cap sd/method-tools-list))))))


(deftest completion-capability-test
  (let [prompt-ref-item (cs/make-completions-reference-item
                         (eg/make-prompt-reference "test-prompt-ref")
                         (fn [{:keys [ref argument]}]
                           [:prompt argument]))
        resource-ref-item (cs/make-completions-reference-item
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


;; --- Common ---


(defn test-lc-notifier
  [item1 item2 list-method deref-cap-maker notification-method]
  (let [mlist (atom [item1])
        cap (deref-cap-maker mlist)
        received (atom [])
        notifier (cap/run-list-changed-notifier {list-method cap}
                                                #(swap! received conj %))]
    (tu/until-done [done! 10]
      (tu/sleep-millis 100)
      (swap! mlist conj item2)
      (tu/sleep-millis 1000)
      (done!))
    (is (= [{:method notification-method, :params {}, :jsonrpc "2.0"}]
           (deref received)) "received notifications")
    (p/stop! notifier)))


(deftest list-changed-test
  (testing "no change, no notification"
    (let [root1 (cs/make-root-item "file:///tmp")
          roots-cap (cap/make-fixed-roots-capability [root1])
          received (atom [])
          notifier (cap/run-list-changed-notifier
                    {sd/method-roots-list roots-cap}
                    #(swap! received conj %))]
      (tu/until-done [done! 10]
        (tu/sleep-millis 1000)
        (done!))
      (is (= [] (deref received)) "no received notifications")
      (p/stop! notifier)))
  (testing "roots - change and notification"
    (let [root1 (cs/make-root-item "file:///tmp")
          root2 (cs/make-root-item "file:///tmp2")]
      (test-lc-notifier root1 root2
                        sd/method-roots-list
                        cap/make-deref-roots-capability
                        sd/method-notifications-roots-list_changed)))
  (testing "prompts - change and notification"
    (let [ph (fn [{:keys [arg]}]
               (-> sd/role-user
                   (es/make-text-prompt-message arg)
                   (es/prompt-message->get-prompt-result)))
          prompt1 (cs/make-prompt-item "p1" ph)
          prompt2 (cs/make-prompt-item "p1" ph)]
      (test-lc-notifier prompt1 prompt2
                        sd/method-prompts-list
                        cap/make-deref-prompts-capability
                        sd/method-notifications-prompts-list_changed)))
  (testing "resources - change and notification"
    (let [hh (fn [{:keys [uri]}]
               (es/make-text-resource-result uri uri))
          rs1 (cs/make-resource-item "test://res1" "res1" hh)
          rs2 (cs/make-resource-item "test://res2" "res2" hh)
          rt1 (cs/make-resource-template-item "test://rt1/{id}" "rt1" hh)
          rt2 (cs/make-resource-template-item "test://rt2/{id}" "rt2" hh)
          rsref (atom [rs1])
          rtref (atom [rt1])
          rcap (cap/make-deref-resources-capability rsref rtref)
          received (atom [])
          notifier (cap/run-list-changed-notifier
                    {sd/method-resources-list rcap
                     sd/method-resources-templates-list rcap}
                    #(swap! received conj %))]
      (tu/until-done [done! 10]
        (tu/sleep-millis 100)
        (swap! rsref conj rs2)
        (swap! rtref conj rt2)
        (tu/sleep-millis 1000)
        (done!))
      (is (= [{:method sd/method-notifications-resources-list_changed,
               :params {}, :jsonrpc "2.0"}
              {:method sd/method-notifications-resources-list_changed,
               :params {}, :jsonrpc "2.0"}]
             (deref received)) "received notifications")
      (p/stop! notifier)))
  (testing "tools - change and notification"
    (let [tool1 tool-add
          tool2 tool-mul]
      (test-lc-notifier tool1 tool2
                        sd/method-tools-list
                        cap/make-deref-tools-capability
                        sd/method-notifications-tools-list_changed))))
