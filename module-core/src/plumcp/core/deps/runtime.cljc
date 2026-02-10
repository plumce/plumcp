;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.deps.runtime
  "Runtime is nothing but a dependency bag of key-value pairs."
  (:require
   [plumcp.core.deps.session-mem :as sm]
   [plumcp.core.impl.capability :as cap]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.deps.runtime :refer [defkey]])
     :clj (:import [clojure.lang IFn])))


;; --- Dependency-bag utility ---


(def ^:const k-runtime :plumcp.core/runtime)
(def ^:const not-found-sentinel ::not-found)


(defn !get
  "Like `(clojure.core/get m k)` that throws if not found."
  [m k]
  (let [v (get m k not-found-sentinel)]
    (if (= v not-found-sentinel)
      (u/expected! m (str "container-map to have key" k))
      v)))


(defn !>get
  "Like `(clojure.core/get-in m [k-runtime k])` that throws if not found."
  [m k]
  (let [v (get-in m [k-runtime k] not-found-sentinel)]
    (if (= v not-found-sentinel)
      (u/expected! m (str "container-map to have path" [k-runtime k]))
      v)))


(defn >get
  "Like `(clojure.core/get-in m [k-runtime k] not-found)`."
  [m k not-found]
  (get-in m [k-runtime k] not-found))


(defn >contains?
  "Like `clojure.core/contains?` for path [k-runtime k]."
  [m k]
  #_(contains? (get m k-runtime) k)
  (not= (get-in m [k-runtime k] not-found-sentinel)
        not-found-sentinel))


(defn >assoc
  "Like `(clojure.core/assoc-in m [k-runtime k] v)`."
  [m k v]
  (assoc-in m [k-runtime k] v))


(defn has-runtime?
  "Return true if the context map has the dependency bag (a map that is
   containing dependencies), false otherwise."
  [container-map]
  (contains? container-map k-runtime))


(defn get-runtime
  "Return deps-bag if available in the context map, throw otherwise."
  [container-map]
  (if (has-runtime? container-map)
    (get container-map k-runtime)
    (u/expected! container-map (str "container-map to have key"
                                    k-runtime))))


(defn upsert-runtime
  "Insert or update (existing) given deps bag into the context map."
  [container-map dependencies]
  (if (has-runtime? container-map)
    (update container-map k-runtime merge dependencies)
    (assoc container-map k-runtime dependencies)))


(defn copy-runtime
  "Copy deps bag from source context map to destination context map. Any
   deps bag already in the destination is fully removed and replaced.
   Optionally, you may specify the keys to be selected when copying."
  ([map-dest map-src]
   (->> {}
        (get map-src k-runtime)
        (assoc map-dest k-runtime)))
  ([map-dest map-src ks]
   (as-> {} $
     (get map-src k-runtime $)
     (select-keys $ ks)
     (assoc map-dest k-runtime $))))


(defn dissoc-runtime
  "Remove deps bag from given context map."
  [container-map]
  (dissoc container-map k-runtime))


;; --- Key definition/helpers ---


(defn ?has
  "Like `clojure.core/contains?` return true if the defined key exists
   in the given runtime map, flase otherwise."
  [runtime-map key-definition]
  (let [k (:key key-definition)]
    (contains? runtime-map k)))


(defn ?>has
  "Return true if the defined key exists at the runtime path in given
   context map, false otherwise."
  [context-map key-definition]
  (let [k (:key key-definition)]
    (>contains? context-map k)))


(defn ?get
  "Like `clojure.core/get` perform a direct lookup on the given runtime
   map using the key definition. Throw if key not found."
  [runtime-map key-definition]
  (let [k (:key key-definition)]
    (if (contains? runtime-map k)
      (get runtime-map k)
      (if (:has-default? key-definition)
        (:default-value key-definition)
        (u/expected! runtime-map (str "runtime-map to have key " k))))))


(defn ?>get
  "Like `clojure.core/get-in` perform a path lookup on the given context
   map using the key definition. Throw if key not found."
  [context-map key-definition]
  (let [k (:key key-definition)]
    (if (:has-default? key-definition)
      (>get context-map k (:default-value key-definition))
      (!>get context-map k))))


(defn ?assoc
  "Like `clojure.core/assoc` add/update the runtime map directly with
   key/val pair."
  [runtime-map key-definition value]
  (let [k (:key key-definition)]
    (assoc runtime-map k value)))


(defn ?>assoc
  "Like `clojure.core/assoc-in` add/update the context map at the path
   with key/val pair."
  [context-map key-definition value]
  (let [k (:key key-definition)]
    (>assoc context-map k value)))


(defrecord KeyDefinition [key has-default? default-value]
  IFn
  (#?(:cljs -invoke
      :clj invoke) [this context-map] (?>get context-map this))
  (#?(:cljs -invoke
      :clj invoke) [this context-map v] (?>assoc context-map this v)))


(defn make-key-definition
  ([k]
   (->KeyDefinition k false nil))
  ([k lookup-default]
   (->KeyDefinition k true lookup-default)))


(defmacro defkey
  "Define a key fn for looking up corresponding value. Option map may
   have the following keys:
   | Keyword  | Default   | Description                                |
   |----------|-----------|--------------------------------------------|
   | :doc     | Inferred  | Docstring for the key-lookup fn            |
   | :key     | Inferred  | Key used for lookup                        |
   | :default |    --     | Default value if no lookup value available |"
  ([fn-name options]
   (assert (symbol? fn-name) "Fn name should be a symbol")
   (assert (nil? (namespace fn-name)) "Fn name symbol should have no namespace")
   (assert (map? options) "Options must be a map")
   (let [has-default? (contains? options :default)
         default-sym (gensym "default")
         default (:default options)
         default-key (keyword (str (ns-name *ns*)) (str fn-name))
         {:keys [doc key]
          :or {doc (str "Dependency/runtime key lookup for " default-key)
               key default-key}} options]
     `(let [~default-sym ~default]
        (def ~(->> (fn [m]
                     (merge m {:arglists (list 'quote
                                               '([context-map]
                                                 [context-map value]))
                               :doc doc}))
                   (vary-meta fn-name))
          ~doc
          ~(if has-default?
             `(make-key-definition ~key ~default-sym)
             `(make-key-definition ~key))))))
  ([fn-name doc options]
   `(defkey ~fn-name ~(assoc options :doc doc))))


;; --- Key definitions ---


;; We ?-prefix all key definition var names to identify them visually
;; (?foo container) --> looks up and returns an item
;; (?foo container val) --> updates item/val and returns container


;; Keys with default values


(defkey ?traffic-logger      {:default stl/nop-traffic-logger})
(defkey ?client-capabilities {:default cap/default-client-capabilities})
(defkey ?server-capabilities {:default cap/default-server-capabilities})
(defkey ?notification-listeners
  {:default (zipmap
             [sd/method-notifications-initialized
              sd/method-notifications-cancelled
              sd/method-notifications-progress
              sd/method-notifications-resources-list_changed
              sd/method-notifications-resources-updated
              sd/method-notifications-message
              sd/method-notifications-prompts-list_changed
              sd/method-notifications-tools-list_changed
              sd/method-notifications-roots-list_changed]
             (repeat u/nop))})
(defkey ?session-store {:default (sm/make-in-memory-server-session-store)})
(defkey ?mcp-logger {:default nil})
(defkey ?server-instructions {:default nil})


;; Keys without default values


(defkey ?session-id {})
(defkey ?session {})
(defkey ?request-context {})
(defkey ?request-id {})
(defkey ?request-params-meta {})
(defkey ?response-stream {})
(defkey ?server-info {})

(defkey ?client-context {})
(defkey ?client-info {})


;; --- Meta utility functions ---


(defn has-session?
  [context]
  (?>has context ?session))


(defn has-response-stream?
  [context]
  (?>has context ?response-stream))


;; --- Middleware ---


(defn wrap-runtime
  "Wrap given runtime onto the request before the handler function
   `(fn handler [request])` can process it."
  [handler runtime]
  (fn runtime-attaching-handler [request]
    (-> (upsert-runtime request runtime)
        (handler))))


(defn wrap-session-required
  "Wrap given handler `(fn [request-map]) -> response-map` needing a
   session with session-check, so that the wrapped function returns
   `session-missing-response` if request doesn't contain a session."
  [handler session-missing-response]
  (fn session-key-checker [request]
    (if (has-session? request)
      (handler request)
      session-missing-response)))
