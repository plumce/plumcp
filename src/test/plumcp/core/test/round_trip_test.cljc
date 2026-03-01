;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.round-trip-test
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is]]
   [plumcp.core.api.capability-support :as cap]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.client.zero-client-transport :as zct]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.dev.api :as dev]
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.main.client :as client]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.server-support :as ss]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


;; --- Primitives items ---

;; Client items

(def root-item-1 (cap/make-root-item "root://root1"))

(def root-item-2 (cap/make-root-item "root://root2"))


;; Server items


(def prompt-item-1 (->> (fn [{:keys [name arguments]}]
                          (-> (es/make-text-prompt-message sd/role-user
                                                           "prompt1")
                              es/prompt-message->get-prompt-result))
                        (cap/make-prompt-item "prompt1")))

(def prompt-item-2 (->> (fn [{:keys [name arguments]}]
                          (-> (es/make-text-prompt-message sd/role-user
                                                           "prompt2")
                              es/prompt-message->get-prompt-result))
                        (cap/make-prompt-item "prompt2")))


(def resource-item-1 (->> (fn [{:keys [uri params]}]
                            (es/make-text-resource-result "res://res1"
                                                          "res1"))
                          (cap/make-resource-item "res://res1" "res1")))
(def resource-item-2 (->> (fn [{:keys [uri params]}]
                            (es/make-text-resource-result "res://res2"
                                                          "res2"))
                          (cap/make-resource-item "res://res2" "res2")))


(def template-item-1 (->> (fn [{:keys [uri params]}]
                            (es/make-text-resource-result "rt1://res/1"
                                                          "res1"))
                          (cap/make-resource-template-item "rt1://res/{id}"
                                                           "tem1")))
(def template-item-2 (->> (fn [{:keys [uri params]}]
                            (es/make-text-resource-result "rt2://res/2"
                                                          "res2"))
                          (cap/make-resource-template-item "rt2://res/{id}"
                                                           "tem2")))


(def tool-schema (eg/make-tool-input-output-schema
                  {"a" {:type "number"
                        :description "first number"}
                   "b" {:type "number"
                        :description "second number"}}
                  ["a" "b"]))

(def tool-item-1 (->> (fn [{:keys [^long a ^long b]}]
                        (+ a b))
                      (cap/make-tool-item "add" tool-schema)))

(def tool-item-2 (->> (fn [{:keys [^long a ^long b]}]
                        (* a b))
                      (cap/make-tool-item "multiply" tool-schema)))


(def resource-uri-cached-roots "res://cached-roots")


(defn ^{:mcp-name "cached-roots"
        :mcp-type :resource} resource-cached-roots
  "Roots fetched and cached by the server"
  [{:keys [^{:doc "res://cached-roots"} uri]
    :as args-with-deps}]
  (->> (rs/get-client-roots args-with-deps)
       pr-str
       (es/make-text-resource-result resource-uri-cached-roots)))


(def tool-name-fetch-roots "fetch-roots")
(def tool-name-check-roots "check-roots")


(defn ^{:mcp-name "fetch-roots"
        :mcp-type :tool} tool-fetch-roots
  "Fetch roots from client"
  [{:as args-with-deps}]
  (rs/fetch-roots args-with-deps)
  "Sent a request to the client to fetch roots")


(defn ^{:mcp-name "check-roots"
        :mcp-type :tool} tool-check-roots
  "Check passed roots against cached roots"
  [{:keys [^{:doc "Roots you want to check"
             :type "object"} roots]
    :as args-with-deps}]
  (let [cached-roots (rs/get-client-roots args-with-deps)]
    (if (= roots cached-roots)
      "roots equal"
      (u/pprint-str cached-roots))))


(def roots-equal {:content [{:type "text", :text "roots equal"}],
                  :isError false})


(defn make-client-primitives
  "Make stateful (derefable) client primitives."
  []
  (let [roots (atom [root-item-1])]
    {:roots roots}))


(defn make-server-primitives
  "Make stateful (derefable) server primitives."
  []
  (let [prompts (atom [prompt-item-1])
        resources (atom [resource-item-1
                         (vs/make-resource-from-var #'resource-cached-roots)])
        templates (atom [template-item-1])
        tools (atom [tool-item-1
                     (vs/make-tool-from-var #'tool-fetch-roots)
                     (vs/make-tool-from-var #'tool-check-roots)])]
    {:prompts prompts
     :resources resources
     :resource-templates templates
     :tools tools}))


(defn make-zero-client-transport
  [{:keys [runtime
           jsonrpc-handler]
    :as server-options}]
  (-> zs/make-zero-handler
      (partial runtime jsonrpc-handler)
      (zct/make-zero-client-transport)))


(defn make-zero-server-options
  ([server-primitives]
   (-> {:primitives server-primitives
        :info (es/make-info "Round trip Server" "0.1.0"
                            "Round trip Server v0.1.0")}
       (merge dev/server-options)
       ss/make-server-options))
  ([]
   (-> (make-server-primitives)
       make-zero-server-options)))


(deftest test-list-changed-roots
  ;; 1. start-server
  ;; 2. client connects
  ;; 3. server fetches roots
  ;;   3.1 assert original roots
  ;; 4. client adds a new root
  ;; 5. server gets list-changed notification
  ;;   5.1 assert event-received
  ;; 6. server fetches new roots list
  ;;   6.1 assert new roots
  (tu/async-test [done!]
    (let [client-primitives (make-client-primitives)
          server-options (make-zero-server-options)
          running-server (-> (:runtime server-options)
                             ss/run-zero-mcp-server)
          transport (-> server-options
                        make-zero-client-transport)
          client (-> {:primitives client-primitives
                      :traffic-logger blogger/client-logger
                      :client-transport transport}
                     (merge client/client-options)
                     (mc/make-client))]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; server fetches roots
       (mc/call-tool client tool-name-fetch-roots {})
       ;; server asserts original roots
       (uab/let-await [ar (mc/call-tool client tool-name-check-roots
                                        {:roots [root-item-1]})]
         (is (= roots-equal ar)
             "original roots"))
       ;; client adds new root
       (-> client-primitives
           (get :roots)
           (swap! conj root-item-2))
       ;; here's hoping that client notifies list-changed to server
       ;; and server re-fetched roots - we check by reading resource
       (uab/until
        #(uab/let-await [roots-result (->> resource-uri-cached-roots
                                           (mc/read-resource client))]
           ;; wait until 2 roots show up
           (= 2 (-> (get-in roots-result [:contents 0 :text])
                    edn/read-string
                    count)))
        1000)
       ;; server asserts new roots now
       (uab/let-await [ar (mc/call-tool client tool-name-check-roots
                                        {:roots [root-item-1
                                                 root-item-2]})]
         (is (= roots-equal ar)
             "updated roots"))
       ;; all done
       (mc/disconnect! client)
       (p/stop! running-server)
       (done!)))))


(defn make-test-ingredients
  []
  (let [server-primitives (make-server-primitives)
        server-options (-> server-primitives
                           make-zero-server-options)
        running-server (-> (:runtime server-options)
                           ss/run-zero-mcp-server)
        transport (-> server-options
                      make-zero-client-transport)
        client (-> {:primitives (make-client-primitives)
                    :traffic-logger blogger/client-logger
                    :client-transport transport}
                   (merge client/client-options)
                   (mc/make-client))]
    {:server-primitives server-primitives
     :running-server running-server
     :client client}))


(deftest test-list-changed-prompts
  ;; 1. start-server
  ;; 2. client connects
  ;; 3. client fetches prompts
  ;;   3.1 assert original prompts
  ;; 4. server adds a new prompt
  ;; 5. client gets list-changed notification
  ;;   5.1 assert event-received
  ;; 6. client fetches new prompts list
  ;;   6.1 assert new prompts
  (tu/async-test [done!]
    (let [{:keys [server-primitives
                  running-server
                  client]} (make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; client fetches prompts
       (uab/let-await [prompts (mc/list-prompts client)]
         ;; client asserts original prompts
         (is (= [(dissoc prompt-item-1 :handler)]
                prompts)
             "original prompts"))
       ;; server adds a new prompt
       (-> server-primitives
           (get :prompts)
           (swap! conj prompt-item-2))
       ;; expect the server to send list-changed to the client
       ;; that ends up re-fetched and cached by the client
       u/nop
       (uab/until
        #(let [prompts (cs/get-from-cache client cs/?cc-prompts-list)]
           (= 2 (count prompts)))
        1000)
       ;; client fetches new prompts list
       (uab/let-await [prompts (mc/list-prompts client)]
         ;; client asserts updated prompts
         (is (= [(dissoc prompt-item-1 :handler)
                 (dissoc prompt-item-2 :handler)]
                prompts)
             "updated prompts"))
       ;; all done
       (mc/disconnect! client)
       (p/stop! running-server)
       (done!)))))


(deftest test-list-changed-resources
  ;; 1. start-server
  ;; 2. client connects
  ;; 3. client fetches resources
  ;;   3.1 assert original resources
  ;; 4. server adds a new resource
  ;; 5. client gets list-changed notification
  ;;   5.1 assert event-received
  ;; 6. client fetches new resources list
  ;;   6.1 assert new resources
  (tu/async-test [done!]
    (let [{:keys [server-primitives
                  running-server
                  client]} (make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; client fetches resources
       (uab/let-await [resources (mc/list-resources client)]
         ;; client asserts original resources
         (is (= (->> [resource-item-1
                      (vs/make-resource-from-var #'resource-cached-roots)]
                     (mapv #(dissoc % :handler)))
                resources)
             "original resources"))
       ;; server adds a new resource
       (-> server-primitives
           (get :resources)
           (swap! conj resource-item-2))
       ;; expect the server to send list-changed to the client
       ;; that ends up re-fetched and cached by the client
       u/nop
       (uab/until
        #(let [resources (cs/get-from-cache client
                                            cs/?cc-resources-list)]
           (= 3 (count resources)))
        1000)
       ;; client fetches new resources list
       (uab/let-await [resources (mc/list-resources client)]
         ;; client asserts updated resources
         (is (= (->> [resource-item-1
                      (vs/make-resource-from-var #'resource-cached-roots)
                      resource-item-2]
                     (mapv #(dissoc % :handler)))
                resources)
             "updated resources"))
       ;; all done
       (mc/disconnect! client)
       (p/stop! running-server)
       (done!)))))


(deftest test-list-changed-resource-templates
  ;; 1. start-server
  ;; 2. client connects
  ;; 3. client fetches resource templates
  ;;   3.1 assert original resource templates
  ;; 4. server adds a new resource template
  ;; 5. client gets list-changed notification
  ;;   5.1 assert event-received
  ;; 6. client fetches new resource templates list
  ;;   6.1 assert new resource templates
  )


(deftest test-list-changed-tools
  ;; 1. start-server
  ;; 2. client connects
  ;; 3. client fetches tools
  ;;   3.1 assert original tools
  ;; 4. server adds a new tool
  ;; 5. client gets list-changed notification
  ;;   5.1 assert event-received
  ;; 6. client fetches new tools list
  ;;   6.1 assert new tools
  )
