;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.transport-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.api.mcp-server :as ms]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.client.stdio-client-transport :as sct]
   [plumcp.core.client.zero-client-transport :as zct]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.dev.api :as dev]
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.main.client :as client]
   [plumcp.core.main.main-http-server :as mhs]
   [plumcp.core.server.server-support :as ss]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(def client-capabilities ic/default-client-capabilities)


(defn ^{:mcp-type :tool
        :mcp-name "delete-session"} delete-session-tool
  "Delete session"
  [{:as kwargs}]
  (rs/remove-server-session kwargs (rt/?session-id kwargs))
  "session-deleted")


(def test-server-options
  (-> {:primitives {:tools
                    [(->> #'delete-session-tool
                          vs/make-tool-from-var)]}
       :info (es/make-info "Test Server" "0.1.0"
                           "Test Server v0.1.0")}
      (merge dev/server-options)
      ss/make-server-options))


(def command-tokens
  "Absolute path to executable file. Required to test `:dir` option."
  [(str tu/project-dir "/script/"
        (if tu/os-windows?
          #?(:cljs "make-run-server-stdio-node.bat"
             :clj "make-run-server-stdio-java.bat")
          #?(:cljs "make-run-server-stdio-node.sh"
             :clj "make-run-server-stdio-java.sh")))])


(defn make-stdio-transport
  ([command-tokens]
   (make-stdio-transport command-tokens nil nil))
  ([command-tokens dir env]
   (-> {:command-tokens command-tokens
        :dir dir
        :env env
        :on-server-exit (partial u/eprintln "[Server Exit]")
        :on-stdout-line println #_(partial u/eprintln "[Server-OUT]")
        :on-stderr-text u/eprintln #_(partial u/eprintln "[Server-ERR]")}
       (u/assoc-some :dir dir
                     :env env)
       sct/run-command)))


(def endpoint-uri "http://localhost:3000/mcp")


(defn make-http-transport
  [endpoint-uri]
  (client/make-http-transport endpoint-uri))


(defn make-zero-transport
  [{:keys [runtime
           jsonrpc-handler]
    :as server-options}]
  (-> zs/make-zero-handler
      (partial runtime jsonrpc-handler)
      (zct/make-zero-client-transport)))


(def transport-makers
  [{:tname "STDIO transport"
    :maker #(make-stdio-transport command-tokens)}
   {:tname "STDIO transport with opts"
    :maker #(make-stdio-transport command-tokens
                                  ".."
                                  {"PLUMCP_TEST_FOO" "PLUMCP_TEST_BAR"})}
   {:tname "Streamable HTTP transport"
    :maker #(make-http-transport endpoint-uri)}
   {:tname "Zero transport"
    :maker #(make-zero-transport test-server-options)}])


(def http-and-zero-transport-makers
  [(nth transport-makers 2)
   (last transport-makers)])


(def running-server-atom (atom nil))


(defn run-http-server
  []
  #_{:clj-kondo/ignore [:unused-binding]}
  (let [[none auth0 scalekit workos] [u/nop
                                      mhs/auth-auth0
                                      mhs/auth-scalekit
                                      mhs/auth-workos]
        ;; auth-options-fn - uncomment any one
        auth-options-fn none #_auth0 #_scalekit #_workos]
    (tu/pst-rethrow
     (u/eprintln "Starting HTTP server at port 3000")
     (uab/may-await
       [running-server (as-> test-server-options $
                         (assoc $ :transport :http :port 3000)
                         (assoc $ :auth-options (auth-options-fn))
                         (ms/run-mcp-server $))]
       (reset! running-server-atom running-server)))))


(defn stop-http-server
  []
  (tu/pst-rethrow
   (u/eprintln "Stopping HTTP server at port 3000")
   (swap! running-server-atom (fn [server]
                                (ms/stop-server server) nil))))


(use-fixtures :once #?(:cljs {:before run-http-server
                              :after stop-http-server}
                       :clj (fn [f]
                              (run-http-server)
                              (f)
                              (stop-http-server))))


(deftest test-happy-transport
  (tu/async-each [{:keys [tname
                          maker]} transport-makers]
    (testing tname
      (u/eprintln "Testing transport:" tname)
      (let [client-transport (maker)
            client-context   (-> {:capabilities client-capabilities
                                  :traffic-logger blogger/client-logger
                                  :client-transport client-transport}
                                 (merge client/client-options)
                                 (mc/make-client))]
        (tu/async-do
         (testing "MCP Handshake"
           (uab/let-await [init-result (mc/initialize-and-notify! client-context)]
             (u/dprint "Initialize Result" init-result)
             (is (= init-result
                    (mc/get-initialize-result client-context)))))
         (testing "MCP Request sent, and result received"
           (uab/let-await [tools (mc/list-tools client-context)]
             (u/dprint "Tools-list (sync) result" tools)
             (is (vector? tools))))
         (testing "Disconnect"
           (mc/disconnect! client-context)
           (is (nil? (mc/get-initialize-result client-context)))))))))


(deftest test-unhappy:client-op-without-handshake
  (tu/async-each [{:keys [tname
                          maker]} transport-makers]
    (testing tname
      (u/eprintln "Testing transport:" tname)
      (let [client-transport (maker)
            client-context   (-> {:capabilities client-capabilities
                                  :traffic-logger blogger/client-logger
                                  :client-transport client-transport}
                                 (merge client/client-options)
                                 (mc/make-client))]
        (testing "Client Op without handshake (HTTP 400)"
          (uab/let-await [tools (mc/list-tools client-context
                                               {:on-error (fn [_id error]
                                                            error)})]
            (u/dprint "Tools-list (sync) result" tools)
            (is (= (if (= tname "Streamable HTTP transport")
                     {:code -32600,
                      :message
                      "Session is missing. Did you call method `initialize`?",
                      :data {},
                      :plumcp.core/http-status 400}
                     {:code -32600,
                      :message "Initialization notification not received yet",
                      :data {}})
                   tools))))))))


(deftest test-unhappy:fake-handshake
  (tu/async-each [{:keys [tname
                          maker]} http-and-zero-transport-makers]
    (testing tname
      (u/eprintln "Testing transport:" tname)
      (let [client-transport (maker)
            client-context   (-> {:capabilities client-capabilities
                                  :traffic-logger blogger/client-logger
                                  :client-transport client-transport}
                                 (merge client/client-options)
                                 (mc/make-client))]
        (testing "Fake handshake"
          (->> {:mcp-session-id "fake-session-id"}
               (cs/set-session-context! client-context))
          (cs/notify-initialized client-context)
          (uab/let-await [tools (mc/list-tools client-context
                                               {:on-error (fn [_ error]
                                                            error)})]
            (is (= (if (= "Streamable HTTP transport"
                          tname)
                     {:code -32601,
                      :message "Session-ID is not associated with any session",
                      :data {},
                      :plumcp.core/http-status 404}
                     [{:name "delete-session",
                       :inputSchema
                       {:type "object", :properties {}, :required []},
                       :description "Delete session"}])
                   tools)
                "tools should fail for HTTP")))))))


(deftest test-unhappy:server-terminates-session
  (tu/async-each [{:keys [tname
                          maker]} http-and-zero-transport-makers]
    (testing tname
      (u/eprintln "Testing transport:" tname)
      (let [client-transport (maker)
            client-context   (-> {:capabilities client-capabilities
                                  :traffic-logger blogger/client-logger
                                  :client-transport client-transport}
                                 (merge client/client-options)
                                 (mc/make-client))]
        (testing "Client Op after server deletes session (HTTP 404)"
          (tu/async-do
           ;; Initialize
           (uab/let-await [init-result (mc/initialize-and-notify! client-context)]
             (u/dprint "Initialize Result" init-result)
             (is (= init-result
                    (mc/get-initialize-result client-context))))
           ;; Delete server session
           (uab/let-await [result (mc/call-tool client-context
                                                "delete-session"
                                                {}
                                                {:on-error (fn [_id error]
                                                             error)})]
             (is (= {:content [{:type "text", :text "session-deleted"}],
                     :isError false,
                     :data {:_meta nil}}
                    (-> result
                        (update-in [:data :_meta] dissoc :progressToken)))))
           ;; Client-op now
           (uab/let-await [tools (mc/list-tools client-context
                                                {:on-error (fn [_ error]
                                                             error)})]
             (u/dprint "Tools-list (sync) result" tools)
             (is (= (if (= tname "Streamable HTTP transport")
                      {:code -32601,
                       :message "Session-ID is not associated with any session",
                       :data {},
                       :plumcp.core/http-status 404}
                      {:code -32600,
                       :message "Initialization notification not received yet",
                       :data {}})
                    tools)))
           ;; disconnect now
           (mc/disconnect! client-context)))))))


;; Commented out because NOT ready to test in CLJS yet
#_(deftest test-unhappy:unreachabe-host
    (let [maker #(make-http-transport "http://localhost:32123/mcp")
          transport (maker)
          client (-> {:capabilities client-capabilities
                      :traffic-logger blogger/client-logger
                      :client-transport transport}
                     (merge client/client-options)
                     (mc/make-client))]
      (tu/async-do
       ;; Initialize
       (uab/let-await [init-result (mc/initialize-and-notify! client)]
         (u/dprint "Initialize Result" init-result)
         (is (= init-result
                (mc/get-initialize-result client))))
       ;; disconnect now
       (mc/disconnect! client))))
