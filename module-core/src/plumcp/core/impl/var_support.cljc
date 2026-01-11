(ns plumcp.core.impl.var-support
  "Var integration and support for capability primitives."
  (:require
   [clojure.set :as set]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


;; ----- Var metadata to capability primitives -----


(def default-mcp-artifact-opts
  {:required-arg-meta-keys [:doc]
   :required-arg-meta-exeg "^{:doc \"description\"} arg-name"})


(defn validate-kwargs!
  "Throw exception when the given var-meta arglists is not kwargs."
  [arglists]
  (when-not (and (= 1 (count arglists))
                 (= 1 (count (first arglists)))
                 (map? (ffirst arglists)))
    (u/throw! "Expected arity-1 function with map-destructuring"
              {:arglists arglists
               :expected "Example: (defn foo [{:keys [bar]}])"})))


(defn validate-var-arglists
  "Validate arglists in an MCP artifact handler var. Return the list of
   arg symbols or throw exception with relevant error message."
  [arglists {:keys [required-arg-meta-keys
                    required-arg-meta-exeg]
             :or {required-arg-meta-keys (get default-mcp-artifact-opts
                                              :required-arg-meta-keys)
                  required-arg-meta-exeg (get default-mcp-artifact-opts
                                              :required-arg-meta-exeg)}}]
  (validate-kwargs! arglists)
  (let [args-spec (ffirst arglists)  ; this is a map
        args-syms (concat (:keys args-spec)
                          (->> (keys args-spec)
                               (remove keyword?)))]
    (when-not (every? symbol? args-syms)
      (u/throw! "Expected symbols as argument names"
                {:arglists arglists
                 :expected "Example: (defn foo [{:keys [bar]}])"}))
    (doseq [each-sym args-syms]
      (let [arg-meta (meta each-sym)]
        (when-not (->> required-arg-meta-keys
                       (every? #(string? (get arg-meta %))))
          (u/throw! (format "Metadata %s missing for argument %s"
                            required-arg-meta-keys
                            each-sym)
                    {:argument each-sym
                     :expected (str "Example: " required-arg-meta-exeg)}))
        (when (and (contains? arg-meta :required?)
                   (not (boolean? (:required? arg-meta))))
          (u/throw! (str "Metadata :required? not a boolean for argument "
                         each-sym)
                    {:argument each-sym
                     :expected "Example: ^{... :required? false} arg-name"}))))
    args-syms))


(def resource-opts default-mcp-artifact-opts)
(def prompt-opts default-mcp-artifact-opts)
(def tool-opts
  {:required-arg-meta-keys [:type :doc]
   :required-arg-meta-emsg "^{:type \"number\" :doc \"description\"} arg-name"})


;; ----- Callback making -----


(def callback-opts
  {:required-arg-meta-keys []
   :required-arg-meta-emsg ""})


(defn make-callback-from-var
  "Given a var instance of a callback function, extract metadata and
   return a callback map {callback-name callback-fn}."
  [var-instance & {:keys [var-handler]
                   :or {var-handler identity}}]
  (u/expected! var-instance var? "argument to be a var")
  (let [vm (meta var-instance)
        callback-name (or (:mcp-name vm)
                          (str (:name vm)))
        arglists (:arglists vm)]
    (validate-var-arglists arglists callback-opts)
    {callback-name (-> var-instance
                       var-handler)}))


;; ----- Prompt making -----


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


(defn ^{:see sd/Prompt} make-prompt-from-var
  "Given a var instance of a prompt function, extract metadata and
   construct MCP prompt details. Example below:
   ```
   (defn ^{:mcp-name \"create-greeting\"} create-greeting
     \"Generate a customized greeting message\"
     [{:keys [^{:doc \"Name of the person to greet\"} name
              ^{:doc \"The style of greeting, such a formal, excited, or
                       casual. If not specified 'casual' will be used.\"
                :required? false} style]
       :or {style \"casual\"}}]
     ,,,)
   ```
   Note:
   - Attributes `:mcp-name` (inferred from symbol when not specified) and
     `:mime-type` are optional.
   - Argument `uri` and its `:doc` metadata are mandatory. The `:doc` value
     should be the resource URI."
  [var-instance & {:keys [var-handler]
                   :or {var-handler identity}}]
  (u/expected! var-instance var? "argument to be a var")
  (let [vm (meta var-instance)
        mcp-name (or (:mcp-name vm)
                     (str (:name vm)))
        descrip  (or (:doc vm)
                     (u/expected! (:doc vm)
                                  (format "prompt var '%s' to have a docstring"
                                          (:name vm))))
        arglists (:arglists vm)
        arg-syms (validate-var-arglists arglists prompt-opts)
        args-vec (->> arg-syms
                      (mapv (fn [sym]
                              (let [sm (meta sym)]
                                {:name (or (:name sm)
                                           (str sym))
                                 :description (:doc sm)
                                 :required (:required? sm true)}))))
        handler  (-> var-instance
                     var-handler
                     make-get-prompt-handler)]
    (-> (eg/make-prompt mcp-name {:description descrip
                                  :args args-vec})
        (cap/make-prompts-capability-item handler))))


;; ----- Resource making -----


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


(defn ^{:see sd/Resource} make-resource-from-var
  "Given a var instance of a resource function, extract metadata and
   construct MCP resource details. Example below:
   ```
   (defn ^{:mcp-name \"Hello world message\"
           :mime-type \"text/plain\"} greeting
     \"A simple greeting message\"
     [{:keys [^{:doc \"hello://world\"} uri]}]
     ,,,)
   ```
   Note:
   - Attributes `:mcp-name` (inferred from symbol when not specified) and
     `:mime-type` are optional.
   - Argument `uri` and its `:doc` metadata are mandatory. The `:doc` value
     should be the resource URI."
  [var-instance & {:keys [var-handler]
                   :or {var-handler identity}}]
  (u/expected! var-instance var? "argument to be a var")
  (let [vm (meta var-instance)
        mcp-name (or (:mcp-name vm)
                     (str (:name vm)))
        descrip  (or (:doc vm)
                     (u/expected!
                      (:doc vm)
                      (format "resource/template var '%s' to have a docstring"
                              (:name vm))))
        arglists (:arglists vm)
        arg-syms (validate-var-arglists arglists resource-opts)
        uri-str  (if-some [uri-sym (some #(when (= (name %) "uri") %)
                                         arg-syms)]
                   (let [uri-str (-> (meta uri-sym)
                                     :doc)]
                     (if (string? uri-str)
                       uri-str
                       (u/throw!
                        "Expected `uri` arg `:doc` metadata to be string"
                        {:found uri-sym
                         :expected "Example: ^{:doc \"...\"} uri"})))
                   (u/expected! arg-syms
                                "an arg symbol named `uri`"))
        handler  (-> var-instance
                     var-handler
                     make-read-resource-handler)]
    (-> (eg/make-resource uri-str mcp-name
                          (-> {:description descrip}
                              (u/assoc-some :mime-type (:mime-type vm))))
        (cap/make-resources-capability-resource-item handler))))


;; ----- Resource-template making -----


(defn add-uri-template-matcher
  "Add a URI-template matcher that returns a map of {param-name param-val}"
  [m]
  (u/expected! (:uriTemplate m) string?
               ":uriTemplate value in map to be a string")
  (letfn [(make-matcher [ut]
            (let [param-names (u/uri-template->variable-names ut)
                  param-regex (u/uri-template->matching-regex ut)]
              (fn uri-template-matcher [uri]
                (when-let [[_ & param-vals] (re-matches param-regex uri)]
                  (zipmap param-names param-vals)))))]
    (let [ut (:uriTemplate m)]
      (update m :matcher (fn [old]
                           (or old
                               (make-matcher ut)))))))


(defn ^{:see sd/ResourceTemplate}  make-resource-template-from-var
  "Given a var instance of a resource-template function, extract metadata
   and construct MCP resource-template details. Example below:
   ```
   (defn ^{:mcp-name \"Personal greeting message\"
           :mime-type \"text/plain\"} personal-greeting
     \"A personalized greeting message\"
     [{:keys [^{:doc \"hello://{name}\"} uri params]}]
     ,,,)
   ```
   Note:
   - Attributes `:mcp-name` (inferred from symbol when not specified) and
     `:mime-type` are optional.
   - Argument `uri` and its `:doc` metadata are mandatory. The `:doc` value
     should be the resource URI-template string.
   - URI-template params are passed as the `params` argument."
  [var-instance & {:as opts}]
  (-> (make-resource-from-var var-instance opts)
      (set/rename-keys {:uri :uriTemplate})
      (add-uri-template-matcher)))


;; ----- Tool making -----


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


(defn ^{:see [sd/Tool
              sd/BaseMetadata]} make-tool-from-var
  "Given a var instance of a tool function, extract metadata and
   construct MCP tool details. Example below:
   ```
   (defn ^{:mcp-name \"create_user\"} make-user
     \"Create a user from given params.\"
     [{:keys [^{:type \"string\"
                :doc \"Name of the user\"} user-name
              ^{:type \"number\"
                :doc \"User's age in years\"} user-age
              ^{:name \"location\"
                :type \"string\"
                :required? false
                :doc \"User's location - area name\"} user-location]}]
     ,,,)
   ```
   Note:
   - Attributes `:tool-name` and (arg) `:name` are optional - when not
     specified, names are inferred from the symbols.
   - Attribute `:required?` is optional for args, assumed true when not
     specified. When specified, must be a boolean value."
  [var-instance & {:keys [var-handler]
                   :or {var-handler identity}}]
  (when-not (var? var-instance)
    (u/expected! var-instance "argument to be a var"))
  (let [vm (meta var-instance)
        mcp-name (or (:mcp-name vm)
                     (str (:name vm)))
        tool-doc (or (:doc vm)
                     (u/expected! (:doc vm)
                                  (format "tool var '%s' to have a docstring"
                                          (:name vm))))
        arglists (:arglists vm)
        arg-syms (validate-var-arglists arglists tool-opts)
        properties (zipmap (->> arg-syms
                                (map (fn [sym]
                                       (or (:name (meta sym))
                                           (str sym)))))
                           (->> arg-syms
                                (map meta)
                                (map #(-> {:type (:type %)
                                           :description (:doc %)}
                                          (u/copy-keys % [:default
                                                          :minimum
                                                          :maximum])))))
        required (->> arg-syms
                      (remove (fn [sym]
                                (false? (:required? (meta sym)))))
                      (mapv str))
        inschema {:type "object"
                  :properties properties
                  :required required}
        handler  (-> var-instance
                     var-handler
                     make-call-tool-handler)]
    (-> (eg/make-tool mcp-name inschema
                      {:description tool-doc})
        (cap/make-tools-capability-item handler))))


;; ----- Sampling -----


(defn make-sampling-handler
  "Make sampling handler fn from the given
   `(fn [kwargs]) -> sampling-result`."
  [f]
  (fn sampling-handler [kwargs]
    (try
      (f kwargs)
      (catch #?(:cljs js/Error
                :clj Exception) ex
        (rs/log-mcpcall-failure kwargs ex)
        (jr/jsonrpc-failure sd/error-code-internal-error
                            (ex-message ex) (ex-data ex))))))


(defn ^{:see [sd/CreateMessageRequest
              sd/CreateMessageResult]} make-sampling-handler-from-var
  "Given a var instance of a sampling function, extract metadata and
   construct MCP sampling details. Example below:
   ```
   (defn ^{:mcp-type :sampling} sample-llm
     \"Accept (sampling) CreateMessageRequest, return CreateMessageResult.\"
     [{messages :messages
       max-tokens :maxTokens}]
     ;; return an instance of CreateMessageResult
     ,,,)
   ```"
  [var-instance & {:keys [var-handler]
                   :or {var-handler identity}}]
  (when-not (var? var-instance)
    (u/expected! var-instance "argument to be a var"))
  (-> var-instance
      var-handler
      make-sampling-handler))


;; ----- Elicitation -----


(defn make-elicitation-handler
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


(defn ^{:see [sd/ElicitRequest
              sd/ElicitResult]} make-elicitation-handler-from-var
  "Given a var instance of an elicitation function, extract metadata and
   construct MCP elicitation details. Example below:
   ```
   (defn ^{:mcp-type :sampling} user-elicitation
     \"Accept (elicitation) ElicitRequest, return ElicitResult.\"
     [{message :message
       requested-schema :requestedSchema}]
     ;; return an instance of ElicitResult
     ,,,)
   ```"
  [var-instance & {:keys [var-handler]
                   :or {var-handler identity}}]
  (when-not (var? var-instance)
    (u/expected! var-instance "argument to be a var"))
  (-> var-instance
      var-handler
      make-elicitation-handler))


;; ----- Vars -----


;; Clients


(defn vars->client-primitives
  "Make MCP client primitives from given vars, returning a map with the
   following:
   {:sampling    sampling-capability?
    :elicitation elicitation-capability?}"
  ([vars opts]
   (->> vars
        (map #(u/expected! % var? "to be a var"))
        (filter #(-> (meta %)
                     (get :mcp-type)))
        (map (fn [each-var]
               (let [mcp-type (-> (meta each-var)
                                  (get :mcp-type))]
                 (case mcp-type
                   :sampling {:sampling (make-sampling-handler-from-var
                                         each-var opts)}
                   :elicitation {:elicitation (make-elicitation-handler-from-var
                                               each-var opts)}
                   (u/expected-enum! mcp-type
                                     #{:sampling :elicitation})))))
        (reduce merge {})))
  ([vars]
   (vars->client-primitives vars {})))


(defn primitives->client-capabilities
  "Make client capabilities from given MCP primitives in the following
   input structure:
   {:sampling    sampling-handler
    :elicitation elicitation-handler}
   See:
   `vars->client-primitives`"
  [{:keys [sampling
           elicitation]}]
  (let [cap-sampling (when sampling
                       (cap/make-sampling-capability sampling))
        cap-elicitation (when elicitation
                          (cap/make-elicitation-capability elicitation))]
    (-> cap/default-client-capabilities
        (cap/update-sampling-capability cap-sampling)
        (cap/update-elicitation-capability cap-elicitation))))


;; Servers


(defn vars->server-primitives
  "Make MCP primitives from given (or auto-discovered from current ns)
   vars, returning a map with following:
   {:callbacks {name handler}
    :prompts   [,,,]
    :tools     [,,,]
    :resources [,,,]
    :resource-templates [,,,]}"
  ([vars opts]
   (->> vars
        (map #(u/expected! % var? "to be a var"))
        (filter #(-> (meta %)
                     (get :mcp-type)))
        (map (fn [each-var]
               (let [mcp-type (-> (meta each-var)
                                  (get :mcp-type))]
                 (case mcp-type
                   :callback {:callbacks (make-callback-from-var each-var opts)}
                   :prompt   {:prompts   (make-prompt-from-var   each-var opts)}
                   :tool     {:tools     (make-tool-from-var     each-var opts)}
                   :resource {:resources (make-resource-from-var each-var opts)}
                   :resource-template {:resource-templates
                                       (make-resource-template-from-var each-var
                                                                        opts)}
                   (u/expected-enum! mcp-type
                                     #{:prompt :tool :resource
                                       :resource-template})))))
        (group-by (comp first keys))
        (reduce-kv (fn [m k v]
                     (let [f ({:callbacks #(apply merge {} %)} k identity)]
                       (assoc m k (f (mapv k v)))))
                   {})))
  ([vars]
   (vars->server-primitives vars {})))


(defn primitives->fixed-server-capabilities
  "Make server capabilities from given MCP primitives (or auto-discovered
   from current namespace) in the following input structure:
   {:prompts   [,,,]
    :tools     [,,,]
    :resources [,,,]
    :resource-templates [,,,]}
   See:
   `make-primitives-from-vars`"
  [{:keys [prompts
           tools
           resources
           resource-templates]}]
  (let [cap-prompts (cap/make-fixed-prompts-capability prompts)
        cap-resources (cap/make-fixed-resources-capability
                       resources
                       resource-templates)
        cap-tools (cap/make-fixed-tools-capability tools)]
    (-> cap/default-server-capabilities
        (cap/update-prompts-capability cap-prompts)
        (cap/update-resources-capability cap-resources)
        (cap/update-tools-capability cap-tools))))
