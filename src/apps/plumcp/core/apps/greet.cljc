(ns plumcp.core.apps.greet
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]))


;; ===== Reference =====
;; Part 1: Getting started with Resources
;; - https://medium.com/@cstroliadavis/building-mcp-servers-536969d27809
;; Part 2: Extending Resources with Resource Templates
;; - https://medium.com/@cstroliadavis/building-mcp-servers-315917582ad1
;; Part 3: Adding Prompts
;; - https://medium.com/@cstroliadavis/building-mcp-servers-13570f347c74
;; Part 4: Creating Tools
;; - https://medium.com/@cstroliadavis/building-mcp-servers-f9ce29814f1f


;; Resource


(defn ^{:mcp-name "Hello World message"
        :mcp-type :resource
        :mime-type "text/plain"}
  greeting-resource
  "A simple greeting message"
  [{:keys [^{:doc "hello://world"} uri]}]
  ;; EITHER
  {:contents [{:uri uri
               :text "Hello, World! This is my first MCP resource."}]}

  ;; OR
  (es/make-text-resource-result uri
                                "Hello, World! This is my first MCP resource."))


(def resources
  [(vs/make-resource-from-var #'greeting-resource)])


;; Resource template


(defn ^{:mcp-name "Personal Greeting"
        :mcp-type :resource-template
        :mime-type "text/plain"}
  personal-greeting-resource-template
  "A personalized greeting message"
  [{:keys [^{:doc "greetings://{name}"} uri
           ^{:doc "URI params"} params]}]
  ;; EITHER
  {:contents [{:uri uri
               :text (format "Hello, %s! Welcome to MCP."
                             (:name params))
               :mimeType "text/plain"}]}
  ;; OR
  (es/make-text-resource-result uri
                                (format "Hello, %s! Welcome to MCP."
                                        (:name params))
                                "text/plain"))


(def resource-templates
  [(vs/make-resource-template-from-var #'personal-greeting-resource-template)])


;; Prompt


(defn ^{:mcp-name "create-greeting"
        :mcp-type :prompt} create-greeting
  "Generate a customized greeting message"
  [{:keys [^{:doc "Name of the person to greet"}
           name
           ^{:doc "The style of greeting, such a formal, excited, or
                   casual. If not specified casual will be used."
             :required? false}
           style]
    :or {style "casual"}}]
  ;; EITHER
  {:messages [{:role "user"
               :content {:type "text"
                         :text (format "Please generate a greeting in %s style to %s"
                                       style
                                       name)}}]}
  ;; OR
  (-> [(es/make-text-prompt-message
        sd/role-user
        (format "Please generate a greeting in %s style to %s"
                style
                name))]
      (eg/make-get-prompt-result {})))


(def prompts
  [(vs/make-prompt-from-var #'create-greeting)])


;; Tool


(def all-message-types ["greeting" "farewell" "thank-you"])
(def all-tones ["formal", "casual", "playful"])

(def message-types-set (set all-message-types))
(def tones-set (set all-tones))

(defn ^{:mcp-name "create-message"
        :mcp-type :tool} create-message
  "Generate a custom message with various options"
  [{^{:doc "Type of message to generate"
      :name "messageType"
      :enum ["greeting" "farewell" "thank-you"]
      :type "string"}
    message-type :messageType
    ^{:doc "Name of the person to address"
      :type "string"}
    recipient :recipient
    ^{:doc "Tone of the message"
      :enum ["formal", "casual", "playful"]
      :type "string"
      :required? false
      :default "casual"}
    tone :tone
    :or {tone "casual"}}]
  (u/eprintln "message-type" message-type)
  (u/eprintln "tone" tone)
  (u/eprintln "recipient" recipient)
  (when-not (message-types-set message-type)
    (u/throw! (str "Must provide message type as either of "
                   (pr-str all-message-types))))
  (when-not (tones-set tone)
    (u/throw! (str "Must provide tone as either of "
                   (pr-str all-tones))))
  (when-not (seq recipient)
    (u/throw! "Must provide a recipient"))
  (-> (case message-type
        "greeting" (case tone
                     "formal"  (format "Dear %s, I hope this message finds you well"
                                       recipient)
                     "playful" (format "Hey hey %s! 🎉 What's shakin'?"
                                       recipient)
                     "casual"  (format "Hi %s! How are you?" recipient))
        "farewell" (case tone
                     "formal"  (format "Best regards, %s. Until we meet again."
                                       recipient)
                     "playful" (format "Catch you later, %s! 👋 Stay awesome!"
                                       recipient)
                     "casual"  (format "Goodbye %s, take care!" recipient))
        "thank-you" (case tone
                      "formal"  (format "Dear %s, I sincerely appreciate your assistance."
                                        recipient)
                      "playful" (format "You're the absolute best, %s! 🌟 Thanks a million!"
                                        recipient)
                      "casual"  (format "Thanks so much, %s! Really appreciate it!"
                                        recipient)))
      (eg/make-text-content)
      vector
      (eg/make-call-tool-result)))


(def tools
  [(vs/make-tool-from-var #'create-message)])


(def mcp-primitives (-> (u/find-vars)
                        (vs/vars->server-primitives)))
