;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


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
