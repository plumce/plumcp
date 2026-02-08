;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.impl.method-handler
  "Support for writing method handlers."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


;; --- Prompt handler ---


(defn ^{:see sd/GetPromptResult} get-prompt-result?
  [x]
  (and (map? x)
       (vector? (:messages x))))


(defn prompt-message?
  [x]
  (and (map? x)
       (string? (:role x))
       (map? (:content x))))


(defn ^{:see [sd/GetPromptResult
              eg/make-get-prompt-result]} as-get-prompt-result
  [sora-retval]  ; SORA: Sync-OR-Async
  (uab/may-await [x sora-retval]
    (condp u/invoke x
      jr/jsonrpc-response? x
      get-prompt-result?   x
      vector?              (eg/make-get-prompt-result x {})
      prompt-message?      (eg/make-get-prompt-result [x] {})
      (u/expected!
       x
       "argument to be either of get-prompt-result/prompt-message-vector"))))


(defn make-get-prompt-handler
  "Make prompt handler fn from the given `(fn [kwargs]) -> prompt-result`."
  [f]
  (fn get-prompt-handler [kwargs]
    (-> (f kwargs)
        as-get-prompt-result)))


;; --- Resource handler ---


(defn ^{:see sd/ReadResourceResult} read-resource-result?
  [x]
  (and (map? x)
       (vector? (:contents x))))


(defn ^{:see sd/ReadResourceResult} as-read-resource-result
  [sora-retval]  ; SORA: Sync-OR-Async
  (uab/may-await [x sora-retval]
    (condp u/invoke x
      jr/jsonrpc-response?  x
      read-resource-result? x
      vector?               (eg/make-read-resource-result x {})
      (u/expected!
       x
       "argument to be either of read-resource-result/content-vector"))))


(defn make-read-resource-handler
  "Make read-resource handler fn from the given
   `(fn [kwargs]) -> read-resource-result`."
  [f]
  (fn call-resource-handler [kwargs]
    (-> (f kwargs)
        as-read-resource-result)))


;; --- Tool handler ---


(defn ^{:see sd/CallToolResult} call-tool-result?
  [x]
  (and (map? x)
       (vector? (:content x))))


(defn ^{:see sd/CallToolResult} make-call-tool-result
  ([content-vector error?]
   (u/expected! content-vector vector? "content-vector to be a vector")
   {:content content-vector
    :isError (boolean error?)})
  ([content-vector]
   (make-call-tool-result content-vector false)))


(defn ^{:see sd/CallToolResult} as-call-tool-result
  [sora-retval]  ; SORA: Sync-OR-Async
  (uab/may-await [x sora-retval]
    (condp u/invoke x
      jr/jsonrpc-response? x
      call-tool-result?    x
      vector?              (make-call-tool-result x)
      string?              (-> [(eg/make-text-content x {})]
                               make-call-tool-result)
      number?              (-> [(eg/make-text-content (str x) {})]
                               make-call-tool-result)
      boolean?             (-> [(eg/make-text-content (str x) {})]
                               make-call-tool-result)
      (u/expected!
       x
       "argument to be either of call-tool-result/content-vector/string"))))


(defn make-call-tool-handler
  "Make call-tool handler fn from the given
   `(fn [kwargs]) -> call-tool-result`."
  [f]
  (fn call-tool-handler [kwargs]
    (try
      (-> (f kwargs)
          as-call-tool-result)
      (catch #?(:cljs js/Error
                :clj Exception) ex
        (rs/log-mcpcall-failure kwargs ex)
        (make-call-tool-result [] true)))))
