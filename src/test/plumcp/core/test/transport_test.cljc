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
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.main.client :as client]
   [plumcp.core.main.server :as server]
   [plumcp.core.protocols :as p]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]))


(def client-capabilities cap/default-client-capabilities)


(def client-runtime (-> {}
                        (rt/?client-capabilities client-capabilities)
                        (rt/?traffic-logger blogger/client-logger)
                        (rt/get-runtime)))


(def command-tokens ["make" #?(:cljs "run-server-stdio-node"
                               :clj "run-server-stdio-java")])


(defn make-stdio-transport
  [command-tokens]
  (sct/run-command {:command-tokens command-tokens
                    :on-server-exit (partial u/eprintln "[Server Exit]")
                    :on-stdout-line println #_(partial u/eprintln "[Server-OUT]")
                    :on-stderr-text u/eprintln #_(partial u/eprintln "[Server-ERR]")}))


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
  [{:tname "STDIO transport" :maker #(make-stdio-transport command-tokens)}
   {:tname "Streamable HTTP transport" :maker #(make-http-transport endpoint-uri)}
   {:tname "Zero transport" :maker #(make-zero-transport server/server-options)}])


(def running-server-atom (atom nil))


(defn run-http-server
  []
  (tu/pst-rethrow
   (u/eprintln "Starting HTTP server at port 3000")
   (as-> server/server-options $
     (assoc $ :transport :http :port 3000)
     (ms/run-mcp-server $)
     (reset! running-server-atom $))))


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


(deftest test-transport
  (doseq [{:keys [tname
                  maker]} transport-makers]
    (testing tname
      (u/eprintln "Testing transport:" tname)
      (try
        (let [client-transport (maker)
              client-context   (-> {:runtime client-runtime
                                    :client-transport client-transport}
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
                   (mc/initialize! client-context))))
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
                   (mc/list-tools client-context))))
          ;;
          ;; Tests over
          ;;
          (p/stop-client-transport! client-transport true))
        (catch #?(:cljs :default :clj Exception) e
          (u/print-stack-trace e)
          (throw e))))))
