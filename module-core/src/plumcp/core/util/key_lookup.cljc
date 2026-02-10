;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util.key-lookup
  "Key lookup functions and abstraction."
  (:require
   [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.util.key-lookup :refer [defkey]])
     :clj (:import [clojure.lang IFn])))


;; --- Primitives ---


(def ^:const not-found-sentinel ::not-found)


(defn !get
  "Like `(clojure.core/get m k)` that throws if not found."
  [m k]
  (let [v (get m k not-found-sentinel)]
    (if (= v not-found-sentinel)
      (u/expected! m (str "container-map to have key" k))
      v)))


;; --- Key definition infrastructure ---


;; We ?-prefix all key definition var names to identify them visually
;; (?foo container) --> looks up and returns an item
;; (?foo container val) --> updates item/val and returns container


(defrecord KeyDefinition [key has-default? default-value
                          f-get f-assoc]
  IFn
  (#?(:cljs -invoke
      :clj invoke) [this context-map] (f-get context-map this))
  (#?(:cljs -invoke
      :clj invoke) [this context-map v] (f-assoc context-map this v)))


(defn ?has
  "Like `clojure.core/contains?` return true if the defined key exists
   in the given container map, false otherwise."
  [m ^KeyDefinition key-definition]
  (if (instance? KeyDefinition key-definition)
    (let [k (:key key-definition)]
      (contains? m k))
    (contains? m key-definition)))


(defn ?get
  "Like `clojure.core/get` perform a direct lookup on the given map
   using the key definition. Throw if key not found."
  [m ^KeyDefinition key-definition]
  (if (instance? KeyDefinition key-definition)
    (let [k (.-key key-definition)
          v (get m k not-found-sentinel)]
      (if (= v not-found-sentinel)
        (if (.-has-default? key-definition)
          (.-default-value key-definition)
          (u/expected! m (str "container-map to have key " k)))
        v))
    (!get m key-definition)))


(defn ?assoc
  "Like `clojure.core/assoc` add/update the map directly with key/val
   pair."
  [m ^KeyDefinition key-definition value]
  (if (instance? KeyDefinition key-definition)
    (let [k (.-key key-definition)]
      (assoc m k value))
    (assoc m key-definition value)))


(defn make-key-definition
  ([k f-get f-assoc]
   (->KeyDefinition k false nil f-get f-assoc))
  ([k lookup-default f-get f-assoc]
   (->KeyDefinition k true lookup-default f-get f-assoc)))


(defmacro defkey
  "Define a key fn for looking up corresponding value. Option map may
   have the following keys:
   | Keyword  | Default   | Description                                |
   |----------|-----------|--------------------------------------------|
   | :doc     | Inferred  | Docstring for the key-lookup fn            |
   | :key     | Inferred  | Key used for lookup                        |
   | :get     | Inferred  | The `get` function used for lookup         |
   | :assoc   | Inferred  | The `assoc` function used to assoc K/V pair|
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
          f-get :get
          f-assoc :assoc
          :or {doc (str "Dependency/runtime key lookup for " default-key)
               f-get (symbol #'?get)
               f-assoc (symbol #'?assoc)
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
             `(make-key-definition ~key ~default-sym ~f-get ~f-assoc)
             `(make-key-definition ~key ~f-get ~f-assoc))))))
  ([fn-name doc options]
   `(defkey ~fn-name ~(assoc options :doc doc))))
