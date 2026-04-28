;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.mcp-runtime
  "Public API for operations
   - related to context (payload with runtime)
   - common across client and server"
  (:require
   [plumcp.core.deps.runtime :as rt]))


;; Request utility


(defn get-request-params-meta
  "Every MCP request potentially includes optional metadata, which
   you may extract from args passed to a server handler function."
  [kwargs]
  (rt/?request-params-meta kwargs))


(defn get-request-id
  "Every MCP request includes a request ID, which you may extract from
   the keyword-args passed to a server handler function."
  [kwargs]
  (rt/?request-id kwargs))


;; Runtime utility


(defn get-runtime
  "Get the runtime/dependencies associated with the argument-map passed
   to a primitive (prompt/resource/tool) handler."
  [args]
  (rt/get-runtime args))


(defn remove-runtime
  "Remove the runtime/dependencies associated with the given context,
   i.e. a JSON-RPC message or argument-map."
  [context]
  (rt/dissoc-runtime context))
