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
   [plumcp.core.util :as u]
   [clojure.string :as str])
  #?(:cljs (:require-macros [plumcp.core.util.key-lookup :refer [defkey]])
     :clj (:import [clojure.lang IFn])))


;; --- Primitives ---


(def ^:const not-found-sentinel ::not-found)


(defn !get
  "Like `(clojure.core/get m k)` that throws if key is not found."
  [m k]
  (let [v (get m k not-found-sentinel)]
    (if (= v not-found-sentinel)
      (u/expected! m (str "container-map to have key" k))
      v)))


(defn !update
  "Like `(clojure.core/update m k f & args)` that throws if key is not
   found."
  [m k f & args]
  (let [v (get m k not-found-sentinel)]
    (if (= v not-found-sentinel)
      (u/expected! m (str "container-map to have key" k))
      (assoc m k (apply f v args)))))


;; --- Key definition infrastructure ---


;; We ?-prefix all key definition var names to identify them visually
;; (?foo container) --> looks up and returns an item
;; (?foo container val) --> updates item/val and returns container


(defrecord KeyDefinition [key has-default? default-value
                          f-get f-assoc f-update]
  IFn
  (#?(:cljs -invoke
      :clj invoke) [this context-map] (f-get context-map this))
  (#?(:cljs -invoke
      :clj invoke) [this context-map v] (f-assoc context-map this v))
  (#?(:cljs -invoke
      :clj invoke) [this context-map f arg] (f-update context-map this f
                                                      arg))
  (#?(:cljs -invoke
      :clj invoke) [this context-map f arg1 arg2] (f-update context-map
                                                            this f
                                                            arg1 arg2)))


(defn keydef?
  "Return true if argument is an instance of KeyDefinition, false
   otherwise."
  [kd]
  (instance? KeyDefinition kd))


(defn ->key
  "Return the :key attribute if argument is a KeyDefinition, arg itself
   (assuming it is an ordinary key) otherwise."
  [^KeyDefinition kd]
  (if (keydef? kd)
    (.-key kd)
    kd))


(defn ->has-default?
  "Return the :has-default? attribute if argument is a KeyDefinition,
   false otherwise."
  [^KeyDefinition kd]
  (if (keydef? kd)
    (.-has-default? kd)
    false))


(defn ->default-value
  "Return the :default-value attribute if argument is a KeyDefinition,
   nil otherwise."
  [^KeyDefinition kd]
  (if (keydef? kd)
    (.-default-value kd)
    nil))


(defn ?has
  "Like `clojure.core/contains?` return true if the defined key exists
   in the given container map, false otherwise."
  [m ^KeyDefinition kd]
  (->> (->key kd)
       (contains? m)))


(defn ?get
  "Like `clojure.core/get` perform a direct lookup on the given map
   using the key definition. Throw if key not found."
  [m ^KeyDefinition kd]
  (if (keydef? kd)
    (let [k (.-key kd)
          v (get m k not-found-sentinel)]
      (if (= v not-found-sentinel)
        (if (.-has-default? kd)
          (.-default-value kd)
          (u/expected! m (str "container-map to have key " k)))
        v))
    (!get m kd)))


(defn ?assoc
  "Like `clojure.core/assoc` add/update the map directly with key/val
   pair."
  [m ^KeyDefinition kd value]
  (as-> (->key kd) $
    (assoc m $ value)))


(defn ?update
  "Like `clojure.core/update` update the map using updater fn."
  [m ^KeyDefinition kd f & args]
  (if (keydef? kd)
    (as-> (?get m kd) $
      (apply f $ args)
      (?assoc m kd $))
    (apply !update m kd f args)))


(defn ?atom-get
  "Like `?get`, but for an atom holding a map."
  [the-atom kd]
  (-> (deref the-atom)
      (?get kd)))


(defn ?atom-assoc
  "Like `?assoc`, but for an atom holding a map."
  [the-atom kd value]
  (swap! the-atom
         ?assoc kd value))


(defn ?atom-update
  "Like `?update`, but for an atom holding a map."
  [the-atom kd f & args]
  (apply swap! the-atom
         ?update kd f args))


(defn ?atom-get-invoke
  "Like `?atom-get`, but where value is stored as arity-0 fn (thunk)."
  [the-atom ^KeyDefinition kd]
  (let [m (deref the-atom)]
    (if (keydef? kd)
      (let [k (.-key kd)
            v (get m k not-found-sentinel)]
        (if (= v not-found-sentinel)
          (if (.-has-default? kd)
            (.-default-value kd)
            (u/expected! m (str "container-map to have key " k)))
          (u/invoke v)))
      (-> (!get m kd)
          u/invoke))))


(defn ?atom-assoc-thunk
  "Like `?atom-assoc`, but where value is stored as arity-0 fn (thunk)."
  [the-atom kd value]
  (->> (constantly value)
       (swap! the-atom
              ?assoc kd)))


(defn ?atom-update-thunk
  "Like `?update`, but where the value is stored as arity-0 fn (thunk)."
  [the-atom kd f & args]
  (apply swap! the-atom
         ?update kd (comp constantly f) args))


(defn make-key-definition
  ([k f-get f-assoc f-update]
   (->KeyDefinition k false nil f-get f-assoc f-update))
  ([k lookup-default f-get f-assoc f-update]
   (->KeyDefinition k true lookup-default f-get f-assoc f-update)))


(defmacro defkey
  "Define a key fn for looking up corresponding value. Option map may
   have the following keys:
   | Keyword  | Default   | Description                                |
   |----------|-----------|--------------------------------------------|
   | :doc     | Inferred  | Docstring for the key-lookup fn            |
   | :key     | Inferred  | Key used for lookup                        |
   | :get     | Inferred  | The `get` function used for lookup         |
   | :assoc   | Inferred  | The `assoc` function used to assoc K/V pair|
   | :update  | Inferred  | The `update` fn used to update K/V pair    |
   | :default |    --     | Default value if no lookup value available |"
  ([fn-name options]
   (assert (symbol? fn-name) "Fn name should be a symbol")
   (assert (nil? (namespace fn-name)) "Fn name symbol should have no namespace")
   (assert (map? options) "Options must be a map")
   (let [has-default? (contains? options :default)
         default-sym (gensym "default")
         default (:default options)
         letter? #?(:cljs #(-> (js/RegExp. "^\\p{L}$" "u") ; unicode aware
                               (.test %)
                               boolean)
                    :clj #(Character/isLetter ^char %))
         default-key (keyword (str (ns-name *ns*))
                              (->> (str fn-name)
                                   (drop-while (complement letter?))
                                   (cons \?)
                                   str/join))
         {:keys [doc key]
          f-get :get
          f-assoc :assoc
          f-update :update
          :or {doc (str "Dependency/runtime key lookup for " default-key)
               f-get (symbol #'?get)
               f-assoc (symbol #'?assoc)
               f-update (symbol #'?update)
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
             `(make-key-definition ~key ~default-sym ~f-get ~f-assoc ~f-update)
             `(make-key-definition ~key ~f-get ~f-assoc ~f-update))))))
  ([fn-name doc options]
   `(defkey ~fn-name ~(assoc options :doc doc))))
