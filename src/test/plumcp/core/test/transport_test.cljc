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
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.api.mcp-server :as ms]
   [plumcp.core.client.stdio-client-transport :as sct]
   [plumcp.core.client.zero-client-transport :as zct]
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.main.client :as client]
   [plumcp.core.main.main-http-server :as mhs]
   [plumcp.core.main.server :as server]
   [plumcp.core.protocol :as p]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(def client-capabilities cap/default-client-capabilities)


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
    :maker #(make-zero-transport server/server-options)}])


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
       [running-server (as-> server/server-options $
                         (assoc $ :transport :http :port 3000)
                         (assoc $ :auth-options (auth-options-fn))
                         (ms/run-mcp-server $))]
       (reset! running-server-atom running-server)))))


(defn stop-http-server
  []
  (tu/pst-rethrow
   (u/eprintln "Stopping HTTP server at port 3000")
   (swap! running-server-atom (fn [server] (p/stop! server) nil))))


(use-fixtures :once #?(:cljs {:before run-http-server
                              :after stop-http-server}
                       :clj (fn [f]
                              (run-http-server)
                              (f)
                              (stop-http-server))))


(deftest test-async-transport
  (doseq [{:keys [tname
                  maker]} transport-makers]
    (testing tname
      (u/eprintln "Testing transport:" tname)
      (try
        (let [client-transport (maker)
              client-context   (-> {:capabilities client-capabilities
                                    :traffic-logger blogger/client-logger
                                    :client-transport client-transport}
                                   (merge client/client-options)
                                   (mc/make-client))]
          ;;
          ;; Handshake
          ;;
          (testing "MCP Handshake"
            (tu/until-done [done! 10]
              (->> (fn [result]
                     (u/dprint "Init Result" result)
                     ;;
                     ;; Notification sent
                     ;;
                     (testing "Sending notification"
                       (mc/notify-initialized client-context))
                     (tu/sleep-millis 10)  ; allow printing to finish
                     (is true "Initialize roundtrip should succeed")
                     (done!))
                   (mc/async-initialize! client-context))))
          ;;
          ;; MCP Request
          ;;
          (testing "MCP Request sent, and result received"
            (tu/until-done [done! 10]
              (->> (fn [result]
                     (u/dprint "Tools-list result" result)
                     ;(tu/sleep-millis 10)  ; HANGs this test; commented
                     (is result "Tools list should be obtained")
                     (done!))
                   (mc/async-list-tools client-context))))
          ;;
          ;; Tests over
          ;;
          (mc/disconnect! client-context))
        (catch #?(:cljs :default :clj Exception) e
          (u/print-stack-trace e)
          (throw e))))))


(deftest test-transport
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
        (testing "MCP Handshake"
          (uab/let-await [result (mc/initialize-and-notify! client-context)]
            (u/dprint "Initialize Result" result)
            (is (= result (mc/get-initialize-result client-context)))
            (testing "MCP Request sent, and result received"
              (uab/let-await [tools (mc/list-tools client-context)]
                (u/dprint "Tools-list (sync) result" tools)
                (is (vector? tools))
                ;; disconnect now
                (mc/disconnect! client-context)))))))))
