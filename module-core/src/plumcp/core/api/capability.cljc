;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.capability
  "Convenience functions for building MCP capability."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.impl.method-handler :as mh]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]))


;; --- Client ---


(defn ^{:see [eg/make-root]} make-root-item
  "Make a roots capability item. A collection of such items is used
   to make roots capability."
  [uri
   & {:as options}]
  (-> (eg/make-root uri options)
      (ic/make-roots-capability-item)))


;; --- Server ---


(defn ^{:see [eg/make-prompt
              ic/make-prompts-capability]} make-prompt-item
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
        (ic/make-prompts-capability-item get-prompt-handler))))


(defn ^{:see [eg/make-resource
              ic/make-resources-capability]}  make-resource-item
  "Make a resources capability item. A collection of such items is used
   to make resources capability. The `handler` is an arity-1 function
   accepting resource kwargs, returning read-resource result."
  [uri name
   ^{:see [eg/make-read-resource-request
           eg/make-read-resource-result]} handler
   & {:as options}]
  (let [read-resource-handler (-> handler
                                  mh/make-read-resource-handler)]
    (-> (eg/make-resource uri name options)
        (ic/make-resources-capability-resource-item read-resource-handler))))


(defn ^{:see [eg/make-resource-template
              ic/make-resources-capability]} make-resource-template-item
  "Make a resource template item. A collection of such items is used
   to make resources capability. The `handler` is an arity-1 function
   accepting resource kwargs, returning read-resource result."
  [uri-template name
   ^{:see [eg/make-read-resource-request
           eg/make-read-resource-result]} handler
   & {:as options}]
  (let [read-resource-handler (-> handler
                                  mh/make-read-resource-handler)]
    (-> (eg/make-resource-template uri-template name options)
        (ic/make-resources-capability-resource-item read-resource-handler))))


(defn ^{:see [eg/make-tool
              ic/make-tools-capability]} make-tool-item
  "Make a tools capability item. A collection of such items is used
   to make tools capability. The `handler` is an arity-1 function
   accepting tool kwargs, returning call-tool result."
  [name ^{:see [eg/make-tool-input-output-schema]} input-schema handler
   & {:as options}]
  (let [call-tool-handler (-> handler
                              mh/make-call-tool-handler)]
    (-> (eg/make-tool name input-schema options)
        (ic/make-tools-capability-item call-tool-handler))))


;; --- Primitives ---


;; Clients


(defn make-sampling-config
  "Make sampling handler fn from the given
   `(fn [kwargs]) -> sampling-result` and return the following:
   {:handler sampling-handler-fn
    :tools   sampling-tools-declaration}."
  [f & {:keys [mcp-sampling-tools]}]
  {:handler (fn sampling-handler [kwargs]
              (try
                (f kwargs)
                (catch #?(:cljs js/Error
                          :clj Exception) ex
                  (rs/log-mcpcall-failure kwargs ex)
                  (jr/jsonrpc-failure sd/error-code-internal-error
                                      (ex-message ex) (ex-data ex)))))
   :tools mcp-sampling-tools})


(defn ^{:see [sd/ElicitRequest
              sd/ElicitRequestParams
              sd/ElicitRequestFormParams
              sd/ElicitRequestURLParams
              sd/ElicitResult]} make-elicitation-routing-handler
  "Make elicitation handler function (fn [kwargs])->elicitation-result
   from given :elicitation-form and :elicitation-url handler functions
   that return a 'unimplemented' error response by default.
   Options:
   :form-handler - receives sd/ElicitRequestFormParams kwargs as a map
   :url-handler  - receives sd/ElicitRequestURLParams kwargs as a map"
  [{:keys [form-handler url-handler]}]
  (let [fallback (fn [token]
                   (let [msg (-> "Elicitation %s-handling is unimplemented"
                                 (format token))]
                     (fn [kwargs]
                       (jr/jsonrpc-failure sd/error-code-internal-error
                                           msg kwargs))))
        form-handler (or form-handler (fallback "Form"))
        url-handler (or url-handler (fallback "URL"))]
    (fn [kwargs]
      (case (:mode kwargs)
        "form" ^{:see sd/ElicitRequestFormParams} (form-handler kwargs)
        "url" ^{:see sd/ElicitRequestURLParams} (url-handler kwargs)
        (form-handler kwargs)))))


(defn ^{:see [make-elicitation-routing-handler]} make-elicitation-handler
  "Make elicitation handler fn from the given
   `(fn [kwargs]) -> elicitation-result`."
  [f]
  (fn elicitation-handler [kwargs]
    (try
      (f kwargs)
      (catch #?(:cljs js/Error
                :clj Exception) ex
        (rs/log-mcpcall-failure kwargs ex)
        (jr/jsonrpc-failure sd/error-code-internal-error
                            (ex-message ex) (ex-data ex))))))


(defn primitives->client-capabilities
  "Make client capabilities from given MCP primitives in the following
   input structure:
   {:roots       - root capability items (either of the following)
                   - vector of root items
                   - deref'able vector of root items (e.g. atom)
                   - arity-0 function returning a vector of root items
    :sampling    - sampling-handler
                 - OR {:handler sampling-handler} with optional keys
                   - optional key `:tools` (sampling tools declaration)
    :elicitation - elicitation-handler}
   See:
   `vars->client-primitives`"
  [{:keys [^{:see [make-root-item]} roots
           ^{:see [make-sampling-config]} sampling
           ^{:see [make-elicitation-handler]} elicitation]}]
  (let [cap-roots (some-> roots
                          ic/make-roots-capability)
        cap-sampling (when sampling
                       (ic/make-sampling-capability sampling))
        cap-elicitation (when elicitation
                          (ic/make-elicitation-capability elicitation))]
    (-> ic/default-client-capabilities
        (ic/update-roots-capability cap-roots)
        (ic/update-sampling-capability cap-sampling)
        (ic/update-elicitation-capability cap-elicitation))))


;; Servers


(defn make-completions-reference-item
  "Make a completions capability reference item. A collection of such items
   may be used to build completions capability. The capability item is made
   by associating `(fn completion-handler [kwargs-map])->completion-result`
   with a reference definition."
  [^{:see [eg/make-prompt-reference
           eg/make-resource-template-reference]} reference-definition
   ^{:see [eg/make-complete-request
           eg/make-complete-result]} completion-handler]
  (-> reference-definition
      (assoc :handler completion-handler)))


(defn primitives->server-capabilities
  "Make server capabilities from given MCP primitives in this structure:
   {:callbacks - map of {<callback-name> <callback-handler>}
                 - IGNORED here as a non-capability, used elsewhere
    :prompts   - prompt capability items (either of the following)
                 - vector of prompt items
                 - deref'able vector of prompt items (e.g. atom)
                 - arity-0 function returning a vector of prompt items
    :resources - resource capability items (either of the following)
                 - vector of resource items
                 - deref'able vector of resource items (e.g. atom)
                 - arity-0 function returning a vector of resource items
    :resource-templates
               - resource-template capability items (either of the following)
                 - vector of resource-template items
                 - deref'able vector of resource-template items (e.g. atom)
                 - arity-0 fn returning a vector of resource-template items
    :tools     - tool capability items (either of the following)
                 - vector of tool items
                 - deref'able vector of tool items (e.g. atom)
                 - arity-0 function returning a vector of tool items
    :completion-prompt-refs - vector of prompt ref items
    :completion-resource-refs - vector of resource ref items}"
  [{:keys [^{:see [make-prompt-item]} prompts
           ^{:see [make-tool-item]} tools
           ^{:see [make-resource-item]} resources
           ^{:see [make-resource-template-item]} resource-templates
           ^{:see [make-completions-reference-item]} completion-prompt-refs
           ^{:see [make-completions-reference-item]} completion-resource-refs]}]
  (let [cap-prompts (some-> prompts
                            ic/make-prompts-capability)
        cap-resources (when (or resources resource-templates)
                        (ic/make-resources-capability resources
                                                      resource-templates))
        cap-tools (some-> tools
                          ic/make-tools-capability)
        cap-completion (when (or completion-prompt-refs
                                 completion-resource-refs)
                         (ic/make-completions-capability
                          (or completion-prompt-refs [])
                          (or completion-resource-refs [])))]
    (-> ic/default-server-capabilities
        (ic/update-prompts-capability cap-prompts)
        (ic/update-resources-capability cap-resources)
        (ic/update-tools-capability cap-tools)
        (ic/update-completions-capability cap-completion))))
