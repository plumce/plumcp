(ns plumcp.core.client.zero-client-transport
  "Zero-transport client implementation."
  (:require
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.protocols :as p]
   [plumcp.core.util :as u]))


(defn make-zero-client-transport
  "Given a function `server-handler-maker` as follows:
   (fn [on-message]) -> (fn [jsonrpc-message])
   return a Zero client transport instance that connects to Zero server."
  [server-handler-maker]
  (let [server-atom (atom nil)
        reset-server! (fn
                        ([on-message]
                         (reset! server-atom
                                 {:handler-fn (-> on-message
                                                  server-handler-maker)
                                  :on-message on-message}))
                        ([]
                         (reset! server-atom nil)))
        session-id (u/uuid-v4)]
    (reify
      p/IClientTransport
      (client-transport-info [_] {:id :zero})
      (start-client-transport [_ on-message] (reset-server! on-message))
      (stop-client-transport! [_ _force?] (reset-server!))
      (send-message-to-server [_ message] (if-let [{:keys [handler-fn
                                                           on-message]}
                                                   (deref server-atom)]
                                            (-> message
                                                (rt/?session-id session-id)
                                                handler-fn ; may return nil
                                                (some-> on-message))
                                            (u/throw! "Transport not initialized")))
      (upon-handshake-success [_ _] nil))))
