;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.stdio-client-transport
  "STDIO-transport client interface."
  (:require
   #?(:cljs [plumcp.core.client.stdio-client-transport-node :as stdio]
      :clj [plumcp.core.client.stdio-client-transport-java :as stdio])
   [plumcp.core.util :as u]))


(defn run-command
  "Run given command, returning a protocol p/IClientTransport instance.
   Required options:
   :command-tokens - [command-string arg1 arg2...])
   :dir            - current directory for process (string)
   :env            - environment variables map
   :on-server-exit - (fn [exit-code-integer])
   :on-stdout-line - (fn [jsonrpc-message-string])
   :on-stderr-text - (fn [stderr-message-string])"
  [{:keys [command-tokens
           dir
           env
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
  (stdio/run-command (-> {:command-tokens command-tokens
                          :on-server-exit on-server-exit
                          :on-stdout-line on-stdout-line
                          :on-stderr-text on-stderr-text}
                         (u/assoc-some :dir dir
                                       :env env))))
