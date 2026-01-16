;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.stdio-client-transport-node
  "STDIO-transport client implementation for Node.js platform."
  (:require
   ["child_process" :as cp]
   ["readline" :as readline]
   [plumcp.core.protocols :as p]
   [plumcp.core.util :as u]
   [plumcp.core.util-cljs :as us]))


(defn run-server!
  [command-tokens
   on-server-exit
   on-stdout-line
   on-stderr-text
   json-reader]
  (let [[command & args] command-tokens
        server-proc (cp/spawn command (-> (vec args)
                                          clj->js)
                              #js{:stdio #js ["pipe"  ; stdin
                                              "pipe"  ; stdout
                                              "pipe"  ; "inherit" ; stderr
                                              ]})
        server-stdin (.-stdin server-proc)
        msg-receiver on-stdout-line]
    ;; --
    ;; listen for STDOUT 'line' events
    (-> readline
        (.createInterface #js{:input (.-stdout server-proc)
                              :crlfDelay js/Infinity})
        (.on "line" (fn [line]
                      (when-let [message (try
                                           (json-reader line)
                                           (catch js/Error _
                                             (u/eprintln line)
                                             nil))]
                        (msg-receiver message)))))
    ;; listen for STDERR output
    (-> (.-stderr server-proc)
        (.on "data" (fn [text]
                      (on-stderr-text text))))
    ;; listen for server exit
    (.on server-proc
         "close" (fn [exit-code]
                   (on-server-exit exit-code)))
    (.on server-proc
         "exit" (fn [exit-code _signal]
                  (on-server-exit exit-code)))
    ;; --
    {:server-proc server-proc
     :server-stdin server-stdin}))


(defn run-command
  "Run given command, returning a protocol p/IClientTransport instance.
   Required options:
   :command-tokens - [command-string arg1 arg2...])
   :on-server-exit - (fn [exit-code-integer])
   :on-stdout-line - (fn [jsonrpc-message-string])
   :on-stderr-text - (fn [stderr-message-string])"
  [{:keys [command-tokens
           on-server-exit
           on-stdout-line
           on-stderr-text]
    :or {on-server-exit (partial u/eprintln "[Server Exit]")
         on-stdout-line println
         on-stderr-text u/eprintln}}]
  (let [server-atom (atom nil)
        server-run! (fn [on-message]
                      (swap! server-atom
                             (fn [server-attrs]
                               (or server-attrs
                                   (run-server! command-tokens
                                                on-server-exit
                                                (or on-message
                                                    on-stdout-line)
                                                on-stderr-text
                                                u/json-parse)))))
        server-send (fn [f]
                      (if-let [{:keys [server-stdin]} @server-atom]
                        (f server-stdin)
                        (u/throw! "STDIO Transport not started")))
        server-kill (fn [force?]
                      (swap! server-atom
                             (fn [server-attrs]
                               (when-let
                                [{:keys [^Process
                                         server-proc
                                         server-stdin]} server-attrs]
                                 (.end server-stdin)
                                 (if force?
                                   (.kill server-proc "SIGKILL")
                                   (.kill server-proc "SIGINT")))
                               nil)))]
    (reify
      p/IClientTransport
      (client-transport-info [_] {:id :stdio :command-tokens command-tokens})
      (start-client-transport [_ on-message] (server-run! on-message))
      (stop-client-transport! [_ force?] (server-kill force?))
      (send-message-to-server [_ message] (-> (fn [server-stdin]
                                                (->> message
                                                     u/json-write
                                                     (us/writeln server-stdin)))
                                              server-send))
      (upon-handshake-success [_ _] nil))))
