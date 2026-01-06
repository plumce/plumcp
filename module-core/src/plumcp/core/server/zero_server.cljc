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
