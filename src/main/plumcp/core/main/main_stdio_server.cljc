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
