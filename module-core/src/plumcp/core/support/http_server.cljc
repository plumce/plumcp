(ns plumcp.core.support.http-server
  "Common interface for Ring compatible, platform specific, built in
   HTTP server."
  (:require
   #?(:cljs [plumcp.core.support.http-server-node :as hsp]
      :clj [plumcp.core.support.http-server-java :as hsp])))


(defn run-http-server
  "Run built-in HTTP server using Ring handler and options.
   Option keys and values:
   | Key          | Type               | Default                       |
   |--------------|--------------------|-------------------------------|
   |:port         |port number - int   |3000                           |
   |:error-handler|(fn [thrown-error]) |prints stack trace to STDERR   |
   |:executor     |java.util.c.Executor|uses virtual threads (JVM only)|"
  [ring-handler options]
  (hsp/run-http-server ring-handler options))
