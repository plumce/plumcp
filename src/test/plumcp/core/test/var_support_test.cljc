;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.var-support-test
  "Test the var support"
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.schema-defs :as sd]))


;; --- Client capability ---


(defn ^{:mcp-type :sampling} sampling-one
  "Sampling one"
  [^{:see [sd/CreateMessageRequest]}
   {;; required
    messages :messages
    max-tokens :maxTokens
    ;; options
    model-prefs :modelPreferences
    system-prompt :systemPrompt
    include-context :includeContext
    temperature :temperature
    stop-sequences :stopSequences
    metadata :metadata
    ;;
    :as kwargs}]
  (eg/make-create-message-result "model-name"
                                 sd/role-user
                                 "content"))


(deftest test-sampling-var
  (let [s1 (vs/make-sampling-handler-from-var #'sampling-one)]
    (is (= {:model "model-name", :role "user", :content "content"}
           (s1 {})))))


(defn ^{:mcp-type :elicitation} elicitation-one
  "Elicitation one"
  [^{:see [sd/ElicitRequest]}
   {;; required
    message :message
    requested-schema :requestedSchema
    ;;
    :as kwargs}]
  (eg/make-elicit-result "action"))


(deftest test-elicitation-var
  (let [e1 (vs/make-elicitation-handler-from-var #'elicitation-one)]
    (is (= {:action "action"}
         (e1 {})))))


;; --- Server capability ---


(defn ^{:mcp-type :prompt
        :mcp-name "prompt-one"} prompt-one
  "A prompt for fetching discount classes for members"
  [{:keys [^{:doc "Membership since which date"} membership-date
           ^{:doc "Membership richness"} richness-code]}]
  (-> (es/make-text-prompt-message sd/role-user "AEC, VXII")
      es/prompt-message->get-prompt-result))


(deftest test-prompt-var
  (let [pn "prompt-one"
        p1 (vs/make-prompt-from-var #'prompt-one)
        pc (ic/make-prompts-capability [p1])
        ph (-> (p/find-handler pc pn)
               :handler)]
    (is (= {:messages
            [{:role "user", :content {:type "text", :text "AEC, VXII"}}]}
         (ph {})))))


(defn ^{:mcp-type :resource
        :mcp-name "resource-one"} resource-one
  "Resource one"
  [{:keys [^{:doc "rs1://res"} uri]}]
  (es/make-text-resource-result uri "res1"))


(deftest test-resource-var
  (let [ruri "rs1://res"
        r1 (vs/make-resource-from-var #'resource-one)
        rc (ic/make-resources-capability [r1] [])
        rh (-> (p/find-handler rc ruri)
               :handler)]
    (is (= {:contents [{:uri "rs1://res", :text "res1"}]}
         (rh {:uri ruri})))))


(defn ^{:mcp-type :resource-template
        :mcp-name "template-one"} template-one
  "Resource template one"
  [{:keys [^{:doc "rt1://res/{id}"} uri
           ^{:doc "URI template params"} params]}]
  (es/make-text-resource-result uri (str "tem1:" (:id params))))


(deftest test-resource-template-var
  (let [ruri "rt1://res/10"
        t1 (vs/make-resource-template-from-var #'template-one)
        rc (ic/make-resources-capability [] [t1])
        tm (p/find-handler rc ruri)
        th (:handler tm)
        tp (:params tm)]
    (is (= {:id "10"}
           tp))
    (is (= {:contents [{:uri ruri, :text "tem1:10"}]}
           (th (-> (dissoc tm :handler)
                   (merge {:uri ruri})))))))


(defn ^{:mcp-type :tool} motd
  "Message of the day"
  [_]
  "Do or do not. There is no try. -Yoda")


(defn ^{:mcp-type :tool
        :mcp-name "bmi"} bmi
  "Body Mass Index (BMI) calculator."
  [{:keys [^{:doc "Body weight in kg" :type "number"} ^double weight
           ^{:doc "Height in meters" :type "number"} ^double height]}]
  (let [v (double (/ weight (* height height)))]
    (cond
      (< v 16) (es/make-text-tool-result-error "BMI is abnormally low")
      (> v 40) (es/make-text-tool-result-error "BMI is abnormally high")
      :else (es/make-text-tool-result (str v)))))


(deftest test-tool
  (testing "Arg-less tool"
    (let [tn "motd"
          t1 (vs/make-tool-from-var #'motd)
          tc (ic/make-tools-capability [t1])
          th (-> (p/find-handler tc tn)
                 :handler)]
      (is (= {:content [{:type "text",
                         :text "Do or do not. There is no try. -Yoda"}],
              :isError false}
             (th {})))))
  (testing "Arg-ful tool"
    (let [tn "bmi"
          t2 (vs/make-tool-from-var #'bmi)
          tc (ic/make-tools-capability [t2])
          th (-> (p/find-handler tc tn)
                 :handler)]
      (is (= {:content [{:type "text", :text "22.839506172839506"}],
              :isError false}
             (th {:height 1.80 :weight 74})))
      (is (= {:content [{:type "text", :text "BMI is abnormally high"}],
              :isError true}
             (th {:weight 74 :height 0.1}))
          "erroneous input"))))
