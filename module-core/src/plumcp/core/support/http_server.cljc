;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


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
