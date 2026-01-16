;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.stdio-client-transport-java
  "STDIO-transport client implementation for Java platform."
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as proc]
   [plumcp.core.protocols :as p]
   [plumcp.core.util :as u]
   [plumcp.core.util-java :as uj])
  (:import
   [java.lang Process]))


(defn run-server!
  [command-tokens
   on-server-exit
   on-stdout-line
   on-stderr-text
   json-reader]
  (let [^Process
        server-proc (->> (cons {} command-tokens)
                         (apply proc/start))
        server-stdin (-> server-proc
                         proc/stdin
                         io/writer)
        exit-request (volatile! false)
        stdout-reader (-> server-proc proc/stdout io/reader)
        stderr-reader (-> server-proc proc/stderr io/reader)
        on-read-line (fn [reader line-handler pre-process]
                       (uj/background-exec
                        (->> (uj/safe-line-seq reader)
                             (run! (fn [line]
                                     (if @exit-request
                                       (reduced :break)
                                       (when-let
                                        [message (try
                                                   (pre-process line)
                                                   (catch Exception _
                                                     (u/eprintln line)
                                                     nil))]
                                         (line-handler message))))))))
        msg-receiver on-stdout-line]
    ;; --
    ;; listen to server STDOUT line
    (on-read-line stdout-reader msg-receiver json-reader)
    ;; listen to server STDERR line/text
    (on-read-line stderr-reader on-stderr-text identity)
    ;; listen to server exit
    (uj/background-exec
     (while (not @exit-request)
       (if (.isAlive server-proc)
         (Thread/sleep 10)
         (do
           (vreset! exit-request true)
           (on-server-exit (.exitValue server-proc))))))
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
  (u/expected! command-tokens vector? ":command-tokens to be a vector")
  (u/expected! on-server-exit fn? ":on-server-exit to be a function")
  (u/expected! on-stdout-line fn? ":on-stdout-line to be a function")
  (u/expected! on-stderr-text fn? ":on-stderr-text to be a function")
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
                                         server-proc]} server-attrs]
                                 (if force?
                                   (.destroyForcibly server-proc)
                                   (.destroy server-proc)))
                               nil)))]
    (reify
      p/IClientTransport
      (client-transport-info [_] {:id :stdio :command-tokens command-tokens})
      (start-client-transport [_ on-message] (server-run! on-message))
      (stop-client-transport! [_ force?] (server-kill force?))
      (send-message-to-server [_ message] (server-send
                                           (fn [server-stdin]
                                             (binding [*out* server-stdin]
                                               (-> message
                                                   u/json-write
                                                   println)))))
      (upon-handshake-success [_ _] nil))))
