(ns plumcp.core.util-cljs
  (:require
   [clojure.string :as str]))


;; ENV detection
;; Ref: https://www.geeksforgeeks.org/javascript/how-to-check-the-current-runtime-environment-is-a-browser-in-javascript
(def env-node-js? "True in Node.js env, false otherwise" (exists? js/process))
(def env-sworker? "True in service-worker, false otherwise" (exists? js/importScripts))
(def env-browser? "True in browser env, false otherwise" (exists? js/window))


(defn writeln
  "Write a line of text followed by a newline."
  [dest text & more]
  (as-> (cons text more) $
    (str/join $)
    (str $ "\n")
    (.write dest $)))


(defn as-str
  "Turn given argument (by reading name if named entity) into a string."
  [x]
  (if (keyword? x)
    (if-let [the-ns (namespace x)]
      (str the-ns "/" (name x))
      (name x))
    (str x)))


;; ClojureScript cannot use property names with underscore in (.-foo_bar baz)
;; so we write it as (aget baz "foo_bar") in the functions below


(defn pget
  "Get JS property value from given object at a given property name.
   Return `nil` if the object is `nil`, instead of throwing an error.
   Like clojure.core/get for JS objects."
  [obj prop-name]
  (when (some? obj)
    (aget obj (as-str prop-name))))


(defn pget-in
  "Get JS property value from given object at nested property names.
   Return `nil` if `nil` object or missing property is encountered.
   Like clojure.core/get-in for JS objects."
  [obj prop-names]
  (loop [target obj
         path (seq prop-names)]
    (if (or (nil? target)
            (nil? path))
      target
      (recur (pget target (first path))
             (next path)))))


(defn pkeys
  "Return a vector of property names for a given JavaScript object."
  [obj]
  (when obj
    (-> obj
        js/Object.keys
        vec)))


(defn uget
  "Unified get - combination of clojure.core/get and pget."
  [haystack needle]
  (or (get haystack needle)
      (pget haystack needle)))


(defn uget-in
  "Unified get-in - combination of clojure.core/get-in and pget-in."
  [haystack needles]
  (loop [target haystack
         path (seq needles)]
    (if (or (nil? target)
            (nil? path))
      target
      (recur (uget target (first path))
             (next path)))))


(defn select-props
  "Given a JS object, return a map containing the property keys and
   corresponding values from the object."
  ([obj prop-keys]
   (when (some? obj)
     (reduce (fn [m k]
               (assoc m k
                      (aget obj (as-str k))))
             {}
             prop-keys)))
  ([obj]
   (when (some? obj)
     (select-props obj (js/Object.keys obj)))))


(defn after-millis
  "Execute no-arg fn `f` after the given number of `milli` ms."
  [millis f]
  (js/setTimeout f millis))


(defn make-promise
  "Given a `(fn [resolve reject])` that uses `(resolve <value>)` to
   deliver the result, or `(reject <error>)` to deliver an error, return
   a JavaScript Promise object."
  [f]
  (js/Promise. f))


(defn make-resolved
  "Make JavaScript Promise of a value. The caller can access it using
   `(.then p (fn [value] ...))`"
  [value]
  (js/Promise.resolve value))


(defn make-rejected
  "Make JavaScript Promise of an error. The caller can access it using
   `(.catch p (fn [error] ...))`"
  [error]
  (js/Promise.reject error))


(defn stream-chain
  "Given an `(atom [...])` return a lazy promise-chain that extracts all
   vector elements until the atom value is `nil`. When the atom has no
   more elements temporarily, `(after-idle (fn [] ...))` is called to
   fetch next batch of elements from the atom. Fn `f` receiving value in
   `(.then js-promise f)` has the arity `(fn [[elem next-promise]])`."
  [stream-atom after-idle]
  (let [[svec _] (swap-vals! stream-atom (fn [stream]
                                           (when (some? stream)
                                             [])))
        val->prom (fn [elem]
                    (js/Promise. (fn [resolve reject]
                                   (resolve elem))))
        fop->prom (fn [f]  ; fop: future op
                    (js/Promise. (fn [resolve reject]
                                   (after-idle (fn []
                                                 (resolve (f)))))))
        prom-chain (fn thisfn
                     ([elems prom-chain-maker]
                      (if (seq elems)
                        (val->prom [(first elems)
                                    (thisfn (next elems) prom-chain-maker)])
                        (fop->prom prom-chain-maker)))
                     ([elems]
                      (thisfn elems (constantly nil))))
        remaining #(lazy-seq (stream-chain stream-atom after-idle))]
    (when svec
      (prom-chain svec remaining))))
