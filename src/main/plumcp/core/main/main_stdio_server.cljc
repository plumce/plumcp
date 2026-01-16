;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.main.main-stdio-server
  "The main entrypoint for STDIO-transport based MCP server for both JVM
   and Node.js."
  (:require
   [plumcp.core.api.mcp-server :as ms]
   [plumcp.core.main.server :as server]
   [plumcp.core.util :as u]))


(defn #?(:clj -main
         :cljs main) [& _]
  (u/eprintln "Starting STDIO server")
  (try (ms/run-mcp-server (merge {:transport :stdio}
                                 server/server-options))
       (catch #?(:clj Exception
                 :cljs js/Error) e
         (u/eprintln e)))
  (u/eprintln "STDIO server started"))
