;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.impl.capability
  "MCP Capability definitions for client and server."
  (:require
   [clojure.set :as set]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]))


;; ----- List capability helpers -----


(defn find-named-item
  "Find matching item from given list-item - all list items must be
   maps containing `:name` (or `name-key` argument) key."
  ([items-list item-name name-key]
   (->> items-list
        (some (fn [item]
                (when (= item-name (get item name-key))
                  item)))))
  ([items-list item-name]
   (find-named-item items-list item-name :name)))


(defn find-named-handler
  "Find handler fn from the matching list-item - all list items must be
   maps containing `:name` (or `name-key` argument) and `:handler` keys."
  ([items-list item-name name-key]
   (when-let [item (find-named-item items-list item-name name-key)]
     {:handler (:handler item)}))
  ([items-list item-name]
   (find-named-handler items-list item-name :name)))


(defn find-uri-handler
  "Find handler fn from the matching list-item - all list items must be
   maps containing `:uri` (and optional `:matcher`) and `:handler` keys."
  [items-list uri]
  (->> items-list
       (some (fn [item]
               (when-let [params (or (= uri (:uri item))
                                     (when-let [matcher (:matcher item)]
                                       (matcher uri)))]
                 (if (map? params)
                   {:params params
                    :handler (:handler item)}
                   {:handler (:handler item)}))))))


(defn call-named-handler [handler [_name args]]
  (handler args))


(defn make-listed-capability
  "Given arity-0 fn `(fn get-items-list-map [])->{method-name items-list}`
   and options, create an items list capability. The default `:find-handler`
   option expects every list-item to be a map with `{:handler (fn [args])}`.
   The map is stripped of `:handler` and `:matcher` keys in `obtain-list`.

   See: `find-named-handler`, `find-uri-handler`"
  [get-items-list-map & {:keys [declaration
                                find-handler]
                         :or {declaration {:listChanged true}
                              find-handler find-named-handler}}]
  (reify
    p/IMcpCapability
    (get-capability-declaration [_] declaration)
    p/IMcpListedCapability
    (obtain-list [_ method-name] (as-> (get-items-list-map) $
                                   (get $ method-name)
                                   (mapv #(dissoc % :handler :matcher)
                                         $)))
    p/IMcpInvokableCapability
    (find-handler [_ args] (as-> (get-items-list-map) $
                             (vals $)
                             (apply concat $)
                             (find-handler $ args)))))


;; List-Changed support


(def notif-makers
  "Map of {list-method-name notification-generator-fn}"
  {;; client
   sd/method-roots-list eg/make-roots-list-changed-notification
   ;; server
   sd/method-prompts-list eg/make-prompt-list-changed-notification
   sd/method-resources-list eg/make-resource-list-changed-notification
   sd/method-resources-templates-list eg/make-resource-list-changed-notification
   sd/method-tools-list eg/make-tool-list-changed-notification})


(defn run-list-changed-notifier
  "Given arguments as follows:
   - map {list-method-name listed-capability}
   - (fn notification-sender [])->nil
   and options:
   - :idle-mills
   - :notification-options
   start a non-stop 'detect and send list-changed notifications' loop
   using the `notification-sender` fn, observing an idle period between
   idle detect-and-send iterations.
   Return an p/IStoppable instance (that may be used to break the loop)
   for lifecycle management."
  [listed-capability-map
   notification-sender
   & {:keys [^long idle-millis
             notification-options]
      :or {idle-millis 100
           notification-options {}}}]
  (let [meth-names (keys listed-capability-map)
        fetch-caps (fn []
                     (reduce-kv (fn [m method-name cap]
                                  (->> (p/obtain-list cap method-name)
                                       (assoc m method-name)))
                                {}
                                listed-capability-map))
        loop? (volatile! true)
        cache (atom (fetch-caps))
        check (fn thisfn []
                (when (deref loop?)
                  (let [cache-caps (deref cache)
                        fresh-caps (fetch-caps)]
                    (if (= fresh-caps cache-caps)
                      #?(:cljs (js/setTimeout thisfn idle-millis)
                         :clj (do
                                (Thread/sleep idle-millis)
                                (recur)))
                      (do
                        (-> (fn [each-method]
                              (let [method-caps (get fresh-caps each-method)]
                                (when (not= (get cache-caps each-method)
                                            method-caps)
                                  ;; update cache
                                  (swap! cache
                                         assoc each-method method-caps)
                                  ;; send notification
                                  (-> (get notif-makers each-method)
                                      (u/invoke notification-options)
                                      notification-sender))))
                            (run! meth-names))
                        (recur))))))]
    (doseq [each-method meth-names]
      (u/expected-enum! each-method notif-makers))
    (u/background
      (check))
    (reify p/IStoppable
      (stop! [_] (vreset! loop? false)))))


(def list-method-names
  "Map of capability keys to respective list-method names"
  {;; Client
   :roots [sd/method-roots-list]
   ;; Server
   :prompts [sd/method-prompts-list]
   :resources [sd/method-resources-list
               sd/method-resources-templates-list]
   :tools [sd/method-tools-list]})


(defn ^{:see [run-list-changed-notifier]} get-listed-capabilities
  "Make listed-capability-map for list-changed notifier."
  [capabilities listed-capability-keys]
  (let [server-method-names (select-keys list-method-names
                                         listed-capability-keys)]
    (->> (keys server-method-names)
         (select-keys (u/remove-vals nil? capabilities))
         (reduce-kv (fn [listed-caps cap-key the-cap]
                      (->> (repeat the-cap)
                           (zipmap (get server-method-names cap-key))
                           (merge listed-caps)))
                    {}))))


(defn ^{:see [run-list-changed-notifier]} get-server-listed-capabilities
  "Make listed-capability-map for MCP server."
  [server-capabilities]
  (get-listed-capabilities server-capabilities [:prompts
                                                :resources
                                                :tools]))


(defn ^{:see [run-list-changed-notifier]} get-client-listed-capabilities
  "Make listed-capability-map for MCP client."
  [client-capabilities]
  (get-listed-capabilities client-capabilities [:roots]))


;; --- Client capability ---


(declare make-roots-capability)


(defn ^{:see [make-roots-capability]} make-roots-capability-item
  "Make a roots capability item. A collection of such items may be used
   to build roots capability."
  [^{:see [eg/make-root]} root-definition]
  root-definition)


(defn make-roots-capability
  "Make roots capability from given `(fn [])->roots-capability-items`."
  [^{:see [make-roots-capability-item]} get-roots-capability-items]
  (make-listed-capability (fn [] {sd/method-roots-list
                                  (get-roots-capability-items)})))


(defn make-deref-roots-capability
  "Make a variable (list of) roots capability from a dereferenceable ref
   (eg. atom, volatile)."
  [^{:see [make-roots-capability-item]} roots-capability-items-ref]
  (make-roots-capability (fn []
                           (deref roots-capability-items-ref))))


(defn make-fixed-roots-capability
  "Make a fixed (list of) roots capability."
  [^{:see [make-roots-capability-item]} roots-capability-items]
  (-> roots-capability-items
      constantly
      make-roots-capability))


(defn make-sampling-capability
  "Given function `(fn [create-message-request])->create-message-result`
   make sampling capability."
  [^{:see [eg/make-create-message-request
           eg/make-create-message-result]} f]
  (reify
    p/IMcpCapability
    (get-capability-declaration [_] {})
    p/IMcpSampling
    (get-sampling-response [_ request] (f request))))


(defn make-elicitation-capability
  "Given function `(fn [elicit-request])->elicit-result` make elicitation
   capability."
  [^{:see [eg/make-elicit-request
           eg/make-elicit-result]} f]
  (reify
    p/IMcpCapability
    (get-capability-declaration [_] {})
    p/IMcpElicitation
    (get-elicitation-response [_ request] (f request))))


;; --- Server capability ---


(def logging-capability
  (reify
    p/IMcpCapability
    (get-capability-declaration [_] {})))


(declare make-prompts-capability)


(defn ^{:see [make-prompts-capability]} make-prompts-capability-item
  "Make a prompts capability item. A collection of such items may be used
   to build prompts capability. The capability item is made by associating
   `(fn get-prompt-handler [kwargs-map])->get-prompt-result` with a prompt
   definition."
  [^{:see [eg/make-prompt]} prompt-definition
   ^{:see [eg/make-get-prompt-request
           eg/make-get-prompt-result]} get-prompt-handler]
  (-> prompt-definition
      (assoc :handler get-prompt-handler)))


(defn make-prompts-capability
  "Make prompts capability from given `(fn [])->prompts-capability-items`."
  [^{:see [make-prompts-capability-item]} get-prompts-capability-items]
  (make-listed-capability (fn [] {sd/method-prompts-list
                                  (get-prompts-capability-items)})))


(defn make-deref-prompts-capability
  "Make a variable (list of) prompts capability from a dereferenceable
   ref (eg. atom, volatile)."
  [^{:see [make-prompts-capability-item]} prompts-capability-items-ref]
  (make-prompts-capability (fn []
                             (deref prompts-capability-items-ref))))


(defn make-fixed-prompts-capability
  "Make a fixed (list of) prompts capability."
  [^{:see [make-prompts-capability-item]} prompts-capability-items]
  (-> prompts-capability-items
      constantly
      make-prompts-capability))


(declare make-resources-capability)


(defn ^{:see make-resources-capability} make-resources-capability-resource-item
  "Make a resources capability (resource) item. A collection of such items
   may be used to build resources capability. The capability item is made by
   associating `(fn read-resource-handler [kwargs-map])->read-resource-result`
   with a resource definition."
  [^{:see [eg/make-resource]} resource-definition
   ^{:see [eg/make-read-resource-request
           eg/make-read-resource-result]} read-resource-handler]
  (-> resource-definition
      (assoc :handler read-resource-handler)))


(defn add-uri-template-matcher
  "Add a URI-template matcher that returns a map of {param-keyword param-val}"
  [m]
  (u/expected! (:uriTemplate m) string?
               ":uriTemplate value in map to be a string")
  (letfn [(make-matcher [ut]
            (let [param-names (u/uri-template->variable-names ut)
                  param-regex (u/uri-template->matching-regex ut)]
              (fn uri-template-matcher [uri]
                (when-let [[_ & param-vals] (re-matches param-regex uri)]
                  (-> (map keyword param-names)
                      (zipmap param-vals))))))]
    (let [ut (:uriTemplate m)]
      (update m :matcher (fn [old]
                           (or old
                               (make-matcher ut)))))))


(defn ^{:see [make-resources-capability]}
  make-resources-capability-resource-template-item
  "Make a resources capability (resource template) item. A collection of such
   items may be used to build resources capability. The capability item is made
   by associating `(fn read-resource-handler [kwargs-map])->read-resource-result`
   with a resource definition."
  [^{:see [eg/make-resource-template]} resource-template-definition
   ^{:see [eg/make-read-resource-request
           eg/make-read-resource-result]} read-resource-handler]
  (-> resource-template-definition
      (assoc :handler read-resource-handler)
      (set/rename-keys {:uri :uriTemplate})
      (add-uri-template-matcher)))


(defn make-resources-capability
  "Make resources capability from the given args:
   - `(fn [])->resources-capability-resource-items` and
   - `(fn [])->resources-capability-resource-template-items`"
  [^{:see [make-resources-capability-resource-item]}
   get-resources-capability-resource-items
   ^{:see [make-resources-capability-resource-template-item]}
   get-resources-capability-resource-template-items
   & {:keys [declaration
             find-handler]
      :or {declaration {:listChanged true
                        :subscribe true}
           find-handler find-uri-handler}}]
  (-> (fn [] {sd/method-resources-list
              (get-resources-capability-resource-items)
              sd/method-resources-templates-list
              (get-resources-capability-resource-template-items)})
      (make-listed-capability {:declaration declaration
                               :find-handler find-handler})))


(defn make-deref-resources-capability
  "Make a variable (list of) resources/templates capability from
   dereferenceable resource/resource-template ref (eg. atom, volatile)."
  [^{:see make-resources-capability-resource-item}
   resources-capability-resource-items-ref
   ^{:see make-resources-capability-resource-template-item}
   resources-capability-resource-template-items-ref
   & {:keys [declaration]
      :or {declaration {:listChanged true
                        :subscribe true}}}]
  (let [r-items (fn [] (deref resources-capability-resource-items-ref))
        rt-items (fn []
                   (deref resources-capability-resource-template-items-ref))]
    (make-resources-capability r-items
                               rt-items
                               {:declaration declaration
                                :find-handler find-uri-handler})))


(defn make-fixed-resources-capability
  "Make a fixed (list of) resources/templates capability."
  [^{:see make-resources-capability-resource-item}
   resources-capability-resource-items
   ^{:see make-resources-capability-resource-template-item}
   resources-capability-resource-template-items
   & {:keys [declaration]
      :or {declaration {:listChanged true
                        :subscribe true}}}]
  (let [r-items (constantly resources-capability-resource-items)
        rt-items (constantly resources-capability-resource-template-items)]
    (make-resources-capability r-items
                               rt-items
                               {:declaration declaration
                                :find-handler find-uri-handler})))


(declare make-tools-capability)


(defn ^{:see [make-tools-capability]} make-tools-capability-item
  "Make a tools capability item. A collection of such items may be
   used to build tools capability. The capability item is made by
   associating `(fn call-tool-handler [kwargs-map])->call-tool-result`
   with a tool definition."
  [^{:see [eg/make-tool]} tool-definition
   ^{:see [eg/make-call-tool-request
           eg/make-call-tool-result]} call-tool-handler]
  (-> tool-definition
      (assoc :handler call-tool-handler)))


(defn make-tools-capability
  "Make tools capability from `(fn [])->tools-capability-items`."
  [^{:see [make-tools-capability-item]} get-tools-capability-items]
  (-> (fn [] {sd/method-tools-list
              (get-tools-capability-items)})
      (make-listed-capability {})))


(defn make-deref-tools-capability
  "Make a variable (list of) tools capability from a dereferenceable
   ref (eg. atom, volatile)."
  [^{:see [make-tools-capability-item]} tools-capability-items-ref]
  (make-tools-capability (fn []
                           (deref tools-capability-items-ref))))


(defn make-fixed-tools-capability
  "Make a fixed (list of) tools capability."
  [^{:see [make-tools-capability-item]} tools-capability-items]
  (-> tools-capability-items
      constantly
      make-tools-capability))


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


(defn make-completions-capability
  "Make completion capability from given prompt/resource refs/factory-fns
   and respective completion handlers `(fn [ref arg])->completion-result`."
  [^{:see [sd/PromptReference
           eg/make-prompt-reference]} get-prompt-refs
   ^{:see [sd/ResourceTemplateReference
           eg/make-resource-template-reference]} get-resource-refs
   & {:keys [find-prompt-item
             find-resource-item]
      :or {find-prompt-item find-named-item
           find-resource-item (fn [items-list item-uri]
                                (find-named-item items-list item-uri
                                                 :uri))}}]
  (let [err-format "'%s' to be a (fn []) or a collection"
        coll->fn (fn [arg arg-name]
                   (condp u/invoke arg
                     fn?   arg
                     coll? (constantly arg)
                     (u/expected! arg (format err-format arg-name))))
        get-prompt-refs (coll->fn get-prompt-refs "get-prompt-refs")
        get-resource-refs (coll->fn get-resource-refs "get-resource-refs")]
    (reify
      p/IMcpCapability
      (get-capability-declaration [_] {})
      p/IMcpCompletion
      (completion-complete [_ complete-ref complete-arg]
        (case (:type ^{:see [sd/CompleteRequest
                             sd/CompleteResult]} complete-ref)
          ;; -- prompt refs --
          "ref/prompt"
          (let [prompt-refs (get-prompt-refs)]
            (if-let [prompt-ref (find-prompt-item prompt-refs
                                                  (:name complete-ref))]
              (-> (:handler prompt-ref)
                  (u/invoke {:ref prompt-ref
                             :argument complete-arg}))
              (jr/jsonrpc-failure
               sd/error-code-invalid-params
               "Invalid or unsupported ref/prompt name"
               {:valid (mapv :name prompt-refs)})))
          ;; -- resource refs --
          "ref/resource"
          (let [resource-refs (get-resource-refs)]
            (if-let [resource-ref (find-resource-item resource-refs
                                                      (:uri complete-ref))]
              (-> (:handler resource-ref)
                  (u/invoke {:ref resource-ref
                             :argument complete-arg}))
              (jr/jsonrpc-failure
               sd/error-code-invalid-params
               "Invalid or unsupported ref/resource URI/template"
               {:valid (mapv :uri resource-refs)})))
          (u/expected! complete-ref
                       "prompt or resource reference under :type"))))))


;; --- Client/Server capabilities ---


(def default-client-capabilities {:experimental nil
                                  :roots        nil
                                  :sampling     nil
                                  :elicitation  nil})


(def default-server-capabilities {:experimental nil
                                  :logging      logging-capability
                                  :completions  nil
                                  :prompts      nil
                                  :resources    nil
                                  :tools        nil})


;; ~~ Reads ~~

;; Client

(defn get-capability-roots       [capabilities] (get capabilities :roots))
(defn get-capability-sampling    [capabilities] (get capabilities :sampling))
(defn get-capability-elicitation [capabilities] (get capabilities :elicitation))


;; Server

(defn get-capability-logging     [capabilities] (get capabilities :logging))
(defn get-capability-completions [capabilities] (get capabilities :completions))
(defn get-capability-prompts     [capabilities] (get capabilities :prompts))
(defn get-capability-resources   [capabilities] (get capabilities :resources))
(defn get-capability-tools       [capabilities] (get capabilities :tools))

;; Client & Server

(defn get-capability-experimental [capabilities] (get capabilities
                                                      :experimental))

;; ~~ Updates ~~

(defn- update-capability
  [capabilities type capability]
  (if (contains? capabilities type)
    (assoc capabilities type capability)
    capabilities))


;; Client


(defn update-roots-capability
  [capabilities capability]
  (update-capability capabilities :roots capability))


(defn update-sampling-capability
  [capabilities capability]
  (update-capability capabilities :sampling capability))


(defn update-elicitation-capability
  [capabilities capability]
  (update-capability capabilities :elicitation capability))


;; Server


(defn update-logging-capability
  [capabilities capability]
  (update-capability capabilities :logging capability))


(defn update-completions-capability
  [capabilities capability]
  (update-capability capabilities :completions capability))


(defn update-prompts-capability
  [capabilities capability]
  (update-capability capabilities :prompts capability))


(defn update-resources-capability
  [capabilities capability]
  (update-capability capabilities :resources capability))


(defn update-tools-capability
  [capabilities capability]
  (update-capability capabilities :tools capability))


;; Client & Server


(defn update-experimental-capability
  [capabilities capability]
  (update-capability capabilities :experimental capability))


;; --- Client/Server capability declaration ---


(defn capability->declaration [capability]
  (and capability
       (p/get-capability-declaration capability)))


(defn get-client-capability-declaration
  "Get capability properties from corresponding client capabilities map
   structured as {<kw-name> <capability>}. The `kw-name` is either of:
   ```
   :experimental
   :roots
   :sampling
   :elicitation
   ```"
  [client-capabilities]
  (let [{:keys [experimental
                roots
                sampling
                elicitation]} client-capabilities]
    (u/assoc-some {}
                  :experimental (capability->declaration experimental)
                  :roots        (capability->declaration roots)
                  :sampling     (capability->declaration sampling)
                  :elicitation  (capability->declaration elicitation))))


(defn get-server-capability-declaration
  "Get capability properties from corresponding server capabilities map
   structured as {<kw-name> <capability>}. The `kw-name` is either of:
   ```
   :experimental
   :logging
   :completions
   :prompts
   :resources
   :tools
   ```"
  [server-capabilities]
  (let [{:keys [experimental
                logging
                completions
                prompts
                resources
                tools]} server-capabilities]
    (u/assoc-some {}
                  :experimental (capability->declaration experimental)
                  :logging      (capability->declaration logging)
                  :completions  (capability->declaration completions)
                  :prompts      (capability->declaration prompts)
                  :resources    (capability->declaration resources)
                  :tools        (capability->declaration tools))))
