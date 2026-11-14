;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.schema.schema-support
  "Support and utility functions related to schema."
  (:require
   [plumcp.core.schema.era26-schema-defs :as e26sd]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]))


(defn era26-request-params-meta?
  "Return true if given JSON-RPC request params meta belongs to Era26,
   false otherwise."
  [request-params-meta]
  (->> e26sd/key-protocol-version  ; request protocol version
       (get request-params-meta)
       (contains? e26sd/protocol-versions-supported-set)))


(defn era26-request?
  "Return true if given JSON-RPC request belongs to Era26, false
   otherwise."
  [jsonrpc-request]
  (-> (jr/request-params-meta jsonrpc-request)
      era26-request-params-meta?))


(def can-log-levels
  {sd/log-level-0-emergency #{sd/log-level-0-emergency}
   sd/log-level-1-alert     #{sd/log-level-0-emergency
                              sd/log-level-1-alert}
   sd/log-level-2-critical  #{sd/log-level-0-emergency
                              sd/log-level-1-alert
                              sd/log-level-2-critical}
   sd/log-level-3-error     #{sd/log-level-0-emergency
                              sd/log-level-1-alert
                              sd/log-level-2-critical
                              sd/log-level-3-error}
   sd/log-level-4-warning   #{sd/log-level-0-emergency
                              sd/log-level-1-alert
                              sd/log-level-2-critical
                              sd/log-level-3-error
                              sd/log-level-4-warning}
   sd/log-level-5-notice    #{sd/log-level-0-emergency
                              sd/log-level-1-alert
                              sd/log-level-2-critical
                              sd/log-level-3-error
                              sd/log-level-4-warning
                              sd/log-level-5-notice}
   sd/log-level-6-info      #{sd/log-level-0-emergency
                              sd/log-level-1-alert
                              sd/log-level-2-critical
                              sd/log-level-3-error
                              sd/log-level-4-warning
                              sd/log-level-5-notice
                              sd/log-level-6-info}
   sd/log-level-7-debug     #{sd/log-level-0-emergency
                              sd/log-level-1-alert
                              sd/log-level-2-critical
                              sd/log-level-3-error
                              sd/log-level-4-warning
                              sd/log-level-5-notice
                              sd/log-level-6-info
                              sd/log-level-7-debug}})


(defn can-log?
  [current-level-name log-level-name]
  (-> can-log-levels
      (get current-level-name)
      (contains? log-level-name)))


(defn era26-log-level
  [request-params-meta]
  (get request-params-meta e26sd/key-log-level))
