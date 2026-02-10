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
   [plumcp.core.util :as u]
   [plumcp.core.util.key-lookup :as kl])
  #?(:cljs (:require-macros [plumcp.core.deps.runtime
                             :refer [defrtkey]])))


;; --- Dependency-bag utility ---


(def ^:const k-runtime :plumcp.core/runtime)


(defn !>get
  "Like `(clojure.core/get-in m [k-runtime k])` that throws if not found."
  [m k]
  (let [v (get-in m [k-runtime k] kl/not-found-sentinel)]
    (if (= v kl/not-found-sentinel)
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
  (not= (get-in m [k-runtime k] kl/not-found-sentinel)
        kl/not-found-sentinel))


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


(defn ?>has
  "Return true if the defined key exists at the runtime path in given
   context map, false otherwise."
  [context-map key-definition]
  (let [k (:key key-definition)]
    (>contains? context-map k)))


(defn ?>get
  "Like `clojure.core/get-in` perform a path lookup on the given context
   map using the key definition. Throw if key not found."
  [context-map key-definition]
  (let [k (:key key-definition)]
    (if (:has-default? key-definition)
      (>get context-map k (:default-value key-definition))
      (!>get context-map k))))


(defn ?>assoc
  "Like `clojure.core/assoc-in` add/update the context map at the path
   with key/val pair."
  [context-map key-definition value]
  (let [k (:key key-definition)]
    (>assoc context-map k value)))


;; --- Key definitions ---


;; Keys with default values


(defmacro defrtkey
  [fn-name options]
  (assert (symbol? fn-name) "Fn name should be a symbol")
  (assert (nil? (namespace fn-name)) "Fn name symbol should have no namespace")
  (assert (map? options) "Options must be a map")
  `(kl/defkey ~fn-name ~(assoc options
                               :get (symbol #'?>get)
                               :assoc (symbol #'?>assoc))))


(defrtkey ?traffic-logger      {:default stl/nop-traffic-logger})
(defrtkey ?client-capabilities {:default cap/default-client-capabilities})
(defrtkey ?server-capabilities {:default cap/default-server-capabilities})
(defrtkey ?notification-listeners
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
(defrtkey ?session-store {:default (sm/make-in-memory-server-session-store)})
(defrtkey ?mcp-logger {:default nil})
(defrtkey ?server-instructions {:default nil})


;; Keys without default values


(defrtkey ?session-id {})
(defrtkey ?session {})
(defrtkey ?request-context {})
(defrtkey ?request-id {})
(defrtkey ?request-params-meta {})
(defrtkey ?response-stream {})
(defrtkey ?server-info {})

(defrtkey ?client-context {})
(defrtkey ?client-info {})


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
