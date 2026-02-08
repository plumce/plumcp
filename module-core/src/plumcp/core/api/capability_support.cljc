;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.capability-support
  "Convenience functions for building MCP capability."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.impl.method-handler :as mh]))


;; --- Client ---


(defn ^{:see [eg/make-root]} make-root-item
  "Make a roots capability item. A collection of such items is used
   to make roots capability."
  [uri
   & {:as options}]
  (-> (eg/make-root uri options)
      (cap/make-roots-capability-item)))


;; --- Server ---


(defn ^{:see [eg/make-prompt
              cap/make-deref-prompts-capability
              cap/make-fixed-prompts-capability]} make-prompt-item
  "Make a prompts capability item. A collection of such items is used
   to make prompts capability. The `handler` is an arity-1 function
   accepting prompt kwargs, returning get-prompt result."
  [name
   ^{:see [eg/make-get-prompt-request
           eg/make-get-prompt-result]} handler
   & {:as options}]
  (let [get-prompt-handler (-> handler
                               mh/make-get-prompt-handler)]
    (-> (eg/make-prompt name options)
        (cap/make-prompts-capability-item get-prompt-handler))))


(defn ^{:see [eg/make-resource
              cap/make-deref-resources-capability
              cap/make-fixed-resources-capability]}  make-resource-item
  "Make a resources capability item. A collection of such items is used
   to make resources capability. The `handler` is an arity-1 function
   accepting resource kwargs, returning read-resource result."
  [uri name handler
   & {:as options}]
  (let [read-resource-handler (-> handler
                                  mh/make-read-resource-handler)]
    (-> (eg/make-resource uri name options)
        (cap/make-resources-capability-resource-item read-resource-handler))))


(defn ^{:see [eg/make-resource-template
              cap/make-deref-resources-capability
              cap/make-fixed-resources-capability]} make-resource-template-item
  "Make a resource template item. A collection of such items is used
   to make resources capability. The `handler` is an arity-1 function
   accepting resource kwargs, returning read-resource result."
  [uri-template name handler
   & {:as options}]
  (let [read-resource-handler (-> handler
                                  mh/make-read-resource-handler)]
    (-> (eg/make-resource-template uri-template name options)
        (cap/make-resources-capability-resource-item read-resource-handler))))


(defn ^{:see [eg/make-tool
              cap/make-deref-tools-capability
              cap/make-fixed-tools-capability]} make-tool-item
  "Make a tools capability item. A collection of such items is used
   to make tools capability. The `handler` is an arity-1 function
   accepting tool kwargs, returning call-tool result."
  [name ^{:see [eg/make-tool-input-output-schema]} input-schema handler
   & {:as options}]
  (let [call-tool-handler (-> handler
                              mh/make-call-tool-handler)]
    (-> (eg/make-tool name input-schema options)
        (cap/make-tools-capability-item call-tool-handler))))
