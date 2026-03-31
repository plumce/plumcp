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
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.capability :as cap]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.api.mcp-server :as ms]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.client.zero-client-transport :as zct]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.dev.api :as dev]
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.main.client :as client]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.server-support :as ss]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-support :as ts]
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
  ([server-options]
   (-> {:info (es/make-info "Round trip Server" "0.1.0"
                            "Round trip Server v0.1.0")}
       (merge dev/server-options server-options)
       ss/make-server-options))
  ([]
   (-> {:primitives (make-server-primitives)}
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
       (ms/stop-server running-server)
       (done!)))))


(defn make-test-ingredients
  [& {:keys [server-options
             client-options]
      :or {server-options {}
           client-options {}}}]
  (let [server-options (-> server-options
                           (update :primitives (fn [delta-primitives]
                                                 (merge
                                                  (make-server-primitives)
                                                  delta-primitives)))
                           make-zero-server-options)
        running-server (-> (:runtime server-options)
                           ss/run-zero-mcp-server)
        transport (-> server-options
                      make-zero-client-transport)
        client (-> {:primitives (make-client-primitives)
                    :traffic-logger blogger/client-logger
                    :client-transport transport}
                   (merge client/client-options client-options)
                   (mc/make-client))]
    {:server-primitives (:primitives server-options)
     :running-server running-server
     :client client}))


;; Commented out as this needs server support for paginated items
#_(deftest test-list-paginated-items
    ;; 1. start-server (with paginated endpoints returning pages of items)
    ;; 2. client connects
    ;; 3. client fetches prompts
    ;;   3.1 client asserts the final list
    ;; 4. server tracks the cursors received
    ;;   4.1 assert cursors
    :FIXME)


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
       (uab/until
        #(let [prompts (-> (cs/get-from-cache client cs/?cc-prompts-list)
                           sd/result-key-prompts)]
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
       (ms/stop-server running-server)
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
       (uab/until
        #(let [resources (-> client
                             (cs/get-from-cache cs/?cc-resources-list)
                             sd/result-key-resources)]
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
       (ms/stop-server running-server)
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
  (tu/async-test [done!]
    (let [{:keys [server-primitives
                  running-server
                  client]} (make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; client fetches resource templates
       (uab/let-await [templates (mc/list-resource-templates client)]
         ;; client asserts original resource templates
         (is (= [(dissoc template-item-1 :handler)]
                templates)
             "original resource templates"))
       ;; server adds a new resource template
       (-> server-primitives
           (get :resource-templates)
           (swap! conj template-item-2))
       ;; expect the server to send list-changed to the client
       ;; that ends up re-fetched and cached by the client
       (uab/until
        #(let [templates (-> client
                             (cs/get-from-cache cs/?cc-resource-templates-list)
                             sd/result-key-resource-templates)]
           (= 2 (count templates)))
        1000)
       ;; client fetches new resource templates list
       (uab/let-await [templates (mc/list-resource-templates client)]
         ;; client asserts updated resource templates
         (is (= (->> [template-item-1
                      template-item-2]
                     (mapv #(dissoc % :handler)))
                templates)
             "updated resource templates"))
       ;; all done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))


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
  (tu/async-test [done!]
    (let [{:keys [server-primitives
                  running-server
                  client]} (make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; client fetches tools
       (uab/let-await [tools (mc/list-tools client)]
         ;; client asserts original tools
         (is (= (->> [tool-item-1
                      (vs/make-tool-from-var #'tool-fetch-roots)
                      (vs/make-tool-from-var #'tool-check-roots)]
                     (mapv #(dissoc % :handler)))
                tools)
             "original tools"))
       ;; server adds a new tool
       (-> server-primitives
           (get :tools)
           (swap! conj tool-item-2))
       ;; expect the server to send list-changed to the client
       ;; that ends up re-fetched and cached by the client
       (uab/until
        #(let [tools (-> (cs/get-from-cache client cs/?cc-tools-list)
                         sd/result-key-tools)]
           (= 4 (count tools)))
        1000)
       ;; client fetches new tools list
       (uab/let-await [tools (mc/list-tools client)]
         ;; client asserts updated tools
         (is (= (->> [tool-item-1
                      (vs/make-tool-from-var #'tool-fetch-roots)
                      (vs/make-tool-from-var #'tool-check-roots)
                      tool-item-2]
                     (mapv #(dissoc % :handler)))
                tools)
             "updated tools"))
       ;; all done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))


(deftest test-client-cancels-task
  ;; 1. start server
  ;; 2. client connects to server
  ;; 3. client calls a server-tool that blocks until flag=true
  ;; 4. client times out in one second
  ;; 5. client cancels the job
  ;; 6. server receives cancelled notification (assert)
  ;; 7. assert request cancelled at server end
  ;; 8. disconnect client
  ;; 9. stop server
  (tu/async-test [done!]
    (let [tool-name "block-until-flag"
          tool-started? (atom false)
          !tool-result (atom nil)
          server-processed-cancel? (atom false)
          tools [(->> (fn [kwargs]
                        (reset! tool-started? true)
                        (tu/async-do
                         (uab/until #(ms/cancel-request-received? kwargs)
                                    10)
                         "unblocked"))
                      (cap/make-tool-item tool-name
                                          (eg/make-tool-input-output-schema
                                           {} [])))]
          {:keys [server-primitives
                  running-server
                  client]} (-> {:server-options {:primitives {:tools tools}
                                                 :notification-handlers
                                                 {sd/method-notifications-cancelled
                                                  (fn [arg]
                                                    (ss/cancel-client-request arg)
                                                    (reset! server-processed-cancel?
                                                            true))}}}
                               make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; call a tool asynchronously
       (let [request (eg/make-call-tool-request tool-name {})
             request-id (:id request)]
         (tu/async-do
          (u/background
            (uab/may-await [result (-> request
                                       (cs/request->response client)
                                       (cs/response->result-or-throw! "call-tool"))]
              ;; unreachable code: client no more has pending request
              (reset! !tool-result result)))
          ;; wait for tool to start
          (uab/until #(true? (deref tool-started?)) 10)
          ;; cancel the task
          (mc/cancel-sent-request client request-id)
          ;; wait for server to process notification
          (uab/until #(true? (deref server-processed-cancel?)) 10)
          ;; allow time for the server to communicate the response
          (uab/until 100)
          ;; assertions
          (is (true? (deref server-processed-cancel?)))
          (is (nil? (deref !tool-result)) "Cancelled Tool response not recorded")
          (is (nil? (-> (cs/?client-cache client)
                        cs/?cc-pending-client-requests
                        (get request-id))))))
       ;; All done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))


(deftest test-server-cancels-task
  ;; 1. start server
  ;; 2. client connects to server
  ;; 3. client calls a server-tool that requests a client sampling
  ;; 4. client sampling blocks until cancel-processed flag=true
  ;; 5. the server-tool in #3 times out and cancels the sent request
  ;; 6. assert request cancelled at client end
  ;; 7. assert request cancelled at server end
  ;; 8. disconnect client
  ;; 9. stop server
  (tu/async-test [done!]
    (let [tool-name "ask-client-for-sampling"
          !tool-started? (atom false)
          !sampling-handler-ran? (atom false)
          !client-processed-cancel? (atom false)
          sampling-request (es/make-sampling-text-message-request sd/role-user
                                                                  "test"
                                                                  123)
          sampling-callback (ms/make-callback-context "sampling-handler")
          sampling-handler (-> (fn [kwargs]
                                 (tu/async-do
                                  (reset! !sampling-handler-ran? true)
                                  ;; wait until request is cancelled
                                  (uab/until #(deref !client-processed-cancel?)
                                             10)
                                  ;; now return the result as usual
                                  (es/make-sampling-text-message-result
                                   "test-model" sd/role-user "test")))
                               cap/make-sampling-handler)
          server-callbacks {"sampling-handler" u/nop}
          client-notific-h (fn [notif]
                             (cs/cancel-server-request notif)
                             (reset! !client-processed-cancel? true))

          tools [(->> (fn [kwargs]
                        (tu/async-do
                         (reset! !tool-started? true)
                         (u/background
                           (ms/send-request-to-client kwargs
                                                      sampling-request
                                                      sampling-callback))
                         ;;wait for the request to proceed
                         (uab/until 100)
                         ;; cancel the earlier-sent request
                         (ms/cancel-sent-request kwargs
                                                 (:id sampling-request))
                         ;;
                         "cancel-notification-sent"))
                      (cap/make-tool-item tool-name
                                          (eg/make-tool-input-output-schema
                                           {} [])))]
          {:keys [server-primitives
                  running-server
                  client]} (-> {:client-options {:primitives
                                                 {:sampling sampling-handler}
                                                 :notification-handlers
                                                 {sd/method-notifications-cancelled
                                                  client-notific-h}}
                                :server-options {:primitives
                                                 {:tools tools
                                                  :callbacks server-callbacks}}}
                               make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; call a server tool
       (let [request (eg/make-call-tool-request tool-name {})
             request-id (:id request)]
         (uab/let-await [result (-> request
                                    (cs/request->response client)
                                    (cs/response->result-or-throw! "call-tool"))]
           (is (= {:content [{:type "text", :text "cancel-notification-sent"}],
                   :isError false}
                  result)))
         (uab/until 500))
       (is (true? (deref !tool-started?)) "Server tool ran")
       (is (true? (deref !sampling-handler-ran?)) "Sampling handler ran")
       (is (true? (deref !client-processed-cancel?))
           "Client-notification handler ran")
       ;; All done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))


(defn ^{:mcp-type :tool} progress-meter
  "Progress meter"
  [{:as kwargs}]
  (let [all-prog (atom [20 40 60 80 100])
        ext-prog (fn ^long []  ; extract!
                   (-> all-prog
                       (swap-vals! next)
                       ffirst))
        notify-p (fn [progress-percent]
                   (as-> (ms/get-request-params-meta kwargs) $
                     (:progressToken $)
                     (eg/make-progress-notification $ progress-percent
                                                    {:total 100})
                     (ms/send-notification-to-client kwargs $)))
        run-loop (fn thisfn [progress-percent return]
                   (if (< ^long progress-percent 100)
                     (do (notify-p progress-percent)
                         #?(:cljs (js/setTimeout
                                   #(thisfn (ext-prog) return) 100)
                            :clj (do (Thread/sleep 100)
                                     (recur (ext-prog) return))))
                     (return "all-done")))]
    (uab/as-async [return reject]
      (run-loop (ext-prog) return))))


(deftest test-server-progress-tracking
  (tu/async-test [done!]
    (let [tools [(vs/make-tool-from-var #'progress-meter)]
          store (atom [])
          uprog (fn [progress-notification]
                  (swap! store
                         conj (:params progress-notification))
                  (cs/update-client-request-progress progress-notification))
          {:keys [running-server
                  client]} (-> {:server-options {:primitives {:tools tools}}
                                :client-options {:notification-handlers
                                                 {sd/method-notifications-progress uprog}}}
                               make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; 1. start server (already done)
       ;; 2. client connects (already done)
       ;; 3. client calls a server tool, which sends progress until done
       ;; 4. assert client received progress updates
       ;; 5. disconnect client
       ;; 6. stop server
       (reset! store [])
       (let [client-cache-atom (cs/?client-cache client)
             request (eg/make-call-tool-request "progress-meter" {})
             request-id (:id request)
             p-token (-> request :params :_meta :progressToken)]
         ;; register progress tokens
         (mc/register-client-request-progress-tokens client
                                                     request-id [p-token])
         ;; assert request-id/progress-token in progress-tracking-dict
         (is (= [p-token]
                (-> (cs/?cc-progress-tracking-dict client-cache-atom)
                    (get request-id)))
             "request-id entry in dict")
         (is (= request-id
                (-> (cs/?cc-progress-tracking-dict client-cache-atom)
                    (get p-token)))
             "progress-token entry in dict")
         (uab/may-await [result (-> request
                                    (cs/request->response client)
                                    (cs/response->result-or-throw! "call-tool"))]
           (is (= {:content [{:type "text", :text "all-done"}]
                   :isError false}
                  result))
           (is (= [{:progressToken p-token :progress 20 :total 100}
                   {:progressToken p-token :progress 40 :total 100}
                   {:progressToken p-token :progress 60 :total 100}
                   {:progressToken p-token :progress 80 :total 100}]
                  (deref store)))
           (let [pt-dict (cs/?cc-progress-tracking-dict client-cache-atom)]
             (is (not (contains? pt-dict request-id))
                 "request-id entry removed from dict")
             (is (not (contains? pt-dict p-token))
                 "progress-token entry removed from dict"))
           (is (nil?
                (mc/get-client-request-progress client request-id))
               "request has ended so progress not available now")))
       ;; all done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))


(defn ^{:mcp-type :callback
        :mcp-name "receive-sampling"} receive-sampling
  "Receive sampling"
  [{:as sampling-result}]
  (let [cc (ms/get-callback-context sampling-result)]
    (reset! (:done? cc) true)))


(defn ^{:mcp-type :tool
        :mcp-name "request-sampling"} request-sampling
  "Requst sampling from client"
  [{:as kwargs}]
  (let [progress-token (-> (ms/get-request-params-meta kwargs)
                           :progressToken)
        sampling-request (as-> (eg/make-text-content "test") $
                           (eg/make-sampling-message sd/role-user $)
                           (vector $)
                           (eg/make-create-message-request $ 100)
                           ;; patch tool-request's progress token into
                           ;; the sampling-request
                           (assoc-in $ [:params :_meta
                                        :progressToken] progress-token))
        done-flag-atom (atom false)
        callback-context (ms/make-callback-context "receive-sampling"
                                                   {:done? done-flag-atom})]
    (ms/send-request-to-client kwargs sampling-request callback-context)
    (uab/let-await [_ (uab/until #(true? (deref done-flag-atom)) 100)]
      "all-done")))


(defn ^{:mcp-type :sampling
        :mcp-name "sampling-handler"} sampling-handler
  "Respond to sampling request from server"
  [{:as kwargs}]
  (let [client (cs/jsonrpc-message-with-deps->client kwargs)
        ptoken (-> (ms/get-request-params-meta kwargs) :progressToken)
        !progs (atom [20 40 60 80])
        x-prog (fn []  ; extract!
                 (-> !progs
                     (swap-vals! next)
                     ffirst))
        loopit (fn thisfn [return]
                 (if-let [progress (x-prog)]
                   (do
                     (->> (eg/make-progress-notification ptoken progress
                                                         {:total 100})
                          (cs/send-message-to-server client))
                     #?(:cljs (js/setTimeout #(thisfn return) 100)
                        :clj (do (Thread/sleep 100)
                                 (recur return))))
                   ;; return
                   (->> (eg/make-text-content "test-sampling")
                        (eg/make-create-message-result "test-model" sd/role-user)
                        (jr/jsonrpc-success (ms/get-request-id kwargs))
                        return)))]
    (uab/as-async [return reject]
      (loopit return))))


(deftest test-client-progress-tracking
  (tu/async-test [done!]
    (let [tools [(vs/make-tool-from-var #'request-sampling)]
          calls (vs/make-callback-from-var #'receive-sampling)
          samph (vs/make-sampling-handler-from-var #'sampling-handler)
          store (atom [])
          uprog (fn [progress-notification]
                  (let [progress (:params progress-notification)]
                    (swap! store
                           conj progress)
                    (ss/update-server-request-progress progress-notification)))
          {:keys [running-server
                  client]} (-> {:server-options {:notification-handlers
                                                 {sd/method-notifications-progress uprog}
                                                 :primitives {:tools tools
                                                              :callbacks calls}}
                                :client-options {:primitives {:sampling samph}}}
                               make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       ;; 1. start server (already done)
       ;; 2. client connects to the server (already done)
       ;; 3. client calls server tool that requests client sampling
       ;; 4. client sampling sends progress updates until done
       ;; 5. assert server receives progress updates
       ;; 6. disconnect client
       ;; 7. stop server
       (reset! store [])
       (let [request (-> (eg/make-call-tool-request "request-sampling" {})
                         (rt/upsert-runtime (ts/make-runtime-server-session)))
             request (-> request
                         (rt/?request-id (:id request)))
             p-token (-> request :params :_meta :progressToken)]
         ;; register progress tokens for progress tracking
         (ms/register-server-request-progress-tokens request [p-token])
         ;; make the tool call
         (uab/may-await [_ (-> request
                               (cs/request->response client)
                               (cs/response->result-or-throw! "call-tool"))]
           (is (= [{:progressToken p-token, :progress 20, :total 100}
                   {:progressToken p-token, :progress 40, :total 100}
                   {:progressToken p-token, :progress 60, :total 100}
                   {:progressToken p-token, :progress 80, :total 100}]
                  (deref store))
               "recorded progress items must match")
           (is (nil?
                (ms/get-server-request-progress request)))))
       ;; all done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))


(defn ^{:mcp-type :tool
        :mcp-name "log-simple"} tool-log-simple
  "Emit simple log"
  [{:keys [^{:doc "Message to log" :type "string"} message]
    :as kwargs}]
  (ms/log-0-emergency kwargs message)
  (ms/log-1-alert     kwargs message)
  (ms/log-2-critical  kwargs message)
  (ms/log-3-error     kwargs message)
  (ms/log-4-warning   kwargs message)
  (ms/log-5-notice    kwargs message)
  (ms/log-6-info      kwargs message)
  (ms/log-7-debug     kwargs message)
  "logging-done")


(defn ^{:mcp-type :tool
        :mcp-name "log-logger"} tool-log-logger
  "Emit log with logger"
  [{:keys [^{:doc "Logger for msg" :type "string"} logger
           ^{:doc "Message to log" :type "string"} message]
    :as kwargs}]
  (ms/with-logger [kwargs logger]
    (tool-log-simple kwargs)))


(deftest test-mcp-logging
  ;; 1. start server
  ;; 2. client connects
  ;; 3. client calls server-tool that logs messages
  ;; 4. assert client receives log messages
  ;; 5. disconnect client
  ;; 6. stop server
  (tu/async-test [done!]
    (let [tools [(vs/make-tool-from-var #'tool-log-simple)
                 (vs/make-tool-from-var #'tool-log-logger)]
          store (atom [])
          logit (fn [log-notification]
                  (swap! store conj (-> log-notification
                                        ms/remove-runtime
                                        :params))
                  (cs/log-message log-notification))
          {:keys [running-server
                  client]} (-> {:server-options {:primitives {:tools tools}}
                                :client-options {:notification-handlers
                                                 {sd/method-notifications-message logit}}}
                               make-test-ingredients)]
      (tu/async-do
       ;; client connects
       (mc/initialize-and-notify! client)
       (testing "log simple"
         ;; log all messages
         (mc/set-log-level client sd/log-level-7-debug)
         ;; client calls server-tool that logs simply
         (reset! store [])
         (uab/let-await [result (mc/call-tool client "log-simple"
                                              {:message "Test message"})]
           (tu/async-do
            (is (= {:content [{:type "text", :text "logging-done"}],
                    :isError false}
                   result))
            ;; wait for all log entries to arrive
            (uab/until #(= 8 (count (deref store))) 10)
            (is (= [{:level "emergency" :data "Test message"}
                    {:level "alert"     :data "Test message"}
                    {:level "critical"  :data "Test message"}
                    {:level "error"     :data "Test message"}
                    {:level "warning"   :data "Test message"}
                    {:level "notice"    :data "Test message"}
                    {:level "info"      :data "Test message"}
                    {:level "debug"     :data "Test message"}]
                   (deref store))))))
       (testing "log with logger"
         ;; log all messages
         (mc/set-log-level client sd/log-level-7-debug)
         ;; client calls server-tool that logs with logger
         (reset! store [])
         (uab/let-await [result (mc/call-tool client "log-logger"
                                              {:logger "Test logger"
                                               :message "Test message"})]
           (tu/async-do
            (is (= {:content [{:type "text", :text "logging-done"}],
                    :isError false}
                   result))
            ;; wait for all log entries to arrive
            (uab/until #(= 8 (count (deref store))) 10)
            (is (= [{:level "emergency" :data "Test message" :logger "Test logger"}
                    {:level "alert"     :data "Test message" :logger "Test logger"}
                    {:level "critical"  :data "Test message" :logger "Test logger"}
                    {:level "error"     :data "Test message" :logger "Test logger"}
                    {:level "warning"   :data "Test message" :logger "Test logger"}
                    {:level "notice"    :data "Test message" :logger "Test logger"}
                    {:level "info"      :data "Test message" :logger "Test logger"}
                    {:level "debug"     :data "Test message" :logger "Test logger"}]
                   (deref store))))))
       (testing "setting log level"
         ;; log only emergency messages
         (mc/set-log-level client sd/log-level-0-emergency)
         (reset! store [])
         (uab/let-await [result (mc/call-tool client "log-simple"
                                              {:message "Test message"})]
           (tu/async-do
            (is (= {:content [{:type "text", :text "logging-done"}],
                    :isError false}
                   result))
            ;; wait for all log entries to arrive
            (uab/until #(= 1 (count (deref store))) 10)
            (is (= [{:level "emergency" :data "Test message"}]
                   (deref store))))))
       ;; all done
       (mc/disconnect! client)
       (ms/stop-server running-server)
       (done!)))))
