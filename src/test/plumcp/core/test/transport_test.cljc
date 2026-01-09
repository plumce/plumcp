(ns plumcp.core.test.transport-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.client.zero-client-transport :as zct]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.protocols :as p]
   [plumcp.core.dev.bling-logger :as blogger]
   [plumcp.core.server.zero-server :as zs]
   [plumcp.core.main.server :as server]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u]))


(def client-capabilities cap/default-client-capabilities)


(def client-runtime (-> {}
                        (rt/?client-capabilities client-capabilities)
                        (rt/?traffic-logger blogger/client-logger)
                        (rt/get-runtime)))


(defn make-zero-transport
  [{:keys [runtime
           jsonrpc-handler]
    :as server-options}]
  (-> zs/make-zero-handler
      (partial runtime jsonrpc-handler)
      (zct/make-zero-client-transport)))


(def transport-makers
  [#_{:tname "STDIO transport" :maker #(make-stdio-transport command-tokens)}
   #_{:tname "Streamable HTTP transport" :maker #(make-http-transport endpoint-uri)}
   {:tname "Zero transport" :maker #(make-zero-transport server/server-options)}])


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
                     (tu/sleep-millis 10)  ; allow printing to finish
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
