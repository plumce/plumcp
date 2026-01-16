;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.zero-server
  "Zero-transport server implementation."
  (:require
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.server-handler :as sh]))


(defn zero-session-request
  "Add session to a request. Arg msg-pusher: `(fn [context message])`"
  [request msg-pusher]
  (let [sess-id (rt/?session-id request)
        session (or (rs/get-server-session request sess-id)
                    (rs/set-server-session request sess-id
                                           msg-pusher))]
    (rt/?session request session)))


(defn make-zero-handler
  "Return (fn [jsonrpc-message]) that accepts a request map directly.
   Arg on-message: `(fn [message])`."
  [server-runtime mcp-jsonrpc-handler on-message]
  (let [push-msg-receiver (fn zero-push-msg-receiver [context message]
                            (on-message message))
        session-request-fn (fn [request]
                             (zero-session-request request
                                                   push-msg-receiver))]
    (-> mcp-jsonrpc-handler
        (sh/wrap-mcp-session session-request-fn)
        (rt/wrap-runtime server-runtime))))
