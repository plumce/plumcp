(ns plumcp.core.util.async-bridge
  "Bridge API that works across CLJ/synchronous and CLJS/asynchronous
   with respective platform semantics."
  (:require
   #?(:cljs [plumcp.core.util-cljs :as us])
   [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.util.async-bridge
                             :refer [let-await
                                     may-await]])))


(defn awaitable?
  [_x]
  #?(:cljs (if (nil? _x)
             false
             ;; pretend that 'thenable' is a js/Promise
             (and (fn? (aget _x "then"))
                  (fn? (aget _x "catch"))
                  (= "Promise" (us/pget-in _x ["constructor" "name"]))))
     :clj false))


(defn async-val
  "Create an async value that one can `b-await` on."
  [value]
  #?(:cljs (js/Promise.resolve value)
     :clj value))


(defmacro as-async
  "Create an async value. In CLJ, return the value synchronously as is,
   whereas in CLJS create a js/Promise that you may `let-await` on. The
   [resolve reject] symbols are bound to their namesake fns. You must
   call (resolve <value>) to resolve the awaitable, or (reject <error>)
   to error out."
  [[resolve-sym reject-sym] & body]
  (assert (symbol? resolve-sym) "resolve-sym must be a symbol")
  (assert (symbol? reject-sym) "reject-sym must be a symbol")
  (if (:ns &env) ;; :ns only exists in CLJS
    `(js/Promise. (fn [~resolve-sym ~reject-sym]
                    ~@body))
    `(let [~resolve-sym (promise)
           ~reject-sym (fn [error#]
                         (cond
                           (instance? Throwable
                                      error#) (throw error#)
                           (string? error#) (u/throw! error#)
                           (map? error#) (u/throw! "Error" error#)
                           :else (u/throw! "Error" {:context error#})))]
       ~@body
       (deref ~resolve-sym))))


(defmacro let-await
  "Given a let-binding vector of awaitable value (value in CLJ/JVM, or
   a js/Promise in CLJS/JS) bind the realized value to the `binding-sym`
   and evaluate the body of code in that context."
  [[binding-sym val-or-prom & more] & body]
  (assert (even? (count more)) "Binding vector must have even number of forms")
  (if (:ns &env) ;; :ns only exists in CLJS
    `(-> ~val-or-prom
         (.then (fn [~binding-sym]
                  ~(if (seq more)
                     `(let-await [~@more]
                                 ~@body)
                     `(do ~@body)))))
    `(let [~binding-sym ~val-or-prom
           ~@more]
       ~@body)))


(defmacro may-await
  "Given a let-binding vector of possibly awaitable value (value in CLJ,
   or a js/Promise in CLJS) bind the realized value to the `binding-sym`
   and evaluate the body of code in that context. Awaitable values are
   detected at runtime and dealt with."
  [[binding-sym val-or-prom & more] & body]
  (if (:ns &env) ;; :ns only exists in CLJS
    `(let [vop# ~val-or-prom]
       (if (awaitable? vop#)
         (-> vop#
             (.then (fn [~binding-sym]
                      ~(if (seq more)
                         `(may-await [~@more]
                                     ~@body)
                         `(do ~@body)))))
         (let [~binding-sym vop#]
           ~(if (seq more)
              `(may-await [~@more]
                          ~@body)
              `(do ~@body)))))
    `(let [~binding-sym ~val-or-prom
           ~@more]
       ~@body)))


(defn iterator?
  "Return true if argument is an iterator, false otherwise. In CLJS the
   iterator may be async or sync, in CLJ the iterator can be sync only."
  [x]
  (and (some? x)
       #?(:cljs (fn? (aget x "next"))
          :clj (instance? java.util.Iterator x))))


(defmacro do-iterator
  "Walk an (async or sync) iterator element-by-element and evaluate body
   of code in that context. In CLJ, `(iterator-seq iterator)` is walked
   synchronously, whereas in CLJS the iterator is walked by successively
   calling `.next()` and inspecting the returned Promise<{done, value}>
   or {done, value} value.
   See:
   https://developer.mozilla.org/en-US/docs/Web/JavaScript/Reference/Iteration_protocols
   https://exploringjs.com/js/book/ch_async-iteration.html"
  [[each-elem iterator] options & body]
  (assert (or (symbol? each-elem)
              (vector? each-elem)
              (map? each-elem)) "binding form may be symbol/vector/map")
  (if (:ns &env) ;; :ns only exists in CLJS
    `(let [iterator# ~iterator
           on-done# (:on-done ~options u/nop)
           walk# (fn thisfn# []
                   (may-await [result# (.next iterator#)]
                              (if (aget result# "done")
                                (on-done#)
                                (let [~each-elem (aget result# "value")]
                                  ~@body
                                  (thisfn#)))))]
       (walk#))
    `(let [on-done# (:on-done ~options u/nop)]
       (doseq [~each-elem (iterator-seq ~iterator)]
         ~@body)
       (on-done))))


(defn map-iterator
  "Return a new iterator wrapping the old, where like `clojure.core/map`
   the `middleware` fn is applied to all the iterated values."
  [middleware iterator]
  #?(:cljs (let [process (fn [result]
                           (if (aget result "done")
                             result
                             #js{:done false
                                 :value (-> (aget result "value")
                                            (middleware))}))]
             #js{:next (fn []
                         (let [nval (.next iterator)]
                           (if (awaitable? nval)
                             (js/Promise. (fn [return reject]
                                            (let-await [result nval]
                                                       (-> (process result)
                                                           (return)))))
                             (process nval))))})
     :clj (cond
            ;; Java iterator
            (iterator? iterator)
            (->> (iterator-seq iterator)
                 (map middleware))
            ;; already an iterator-seq
            (seq? iterator)
            (map middleware iterator)
            ;; otherwise
            :else
            (u/expected! iterator "an iterator"))))


(defn cons-iterator
  "Return a new iterator where x is the first element and iterator is
   the rest."
  [x iterator]
  #?(:cljs (let [taken? (atom false)]
             #js{:next (fn []
                         (if-not (deref taken?)
                           (do
                             (reset! taken? true)
                             #js{:value x
                                 :done false})
                           (.next iterator)))})
     :clj (cond
            ;; Java iterator
            (iterator? iterator)
            (->> (iterator-seq iterator)
                 (cons x))
            ;; already an iterator-seq
            (seq? iterator)
            (cons x iterator)
            ;; otherwise
            :else
            (u/expected! iterator "an iterator"))))


(defmacro await->
  "Equivalent of clojure.core/-> for awaitable intermediate results."
  [expr & forms]
  (let [result-sym (gensym 'result)
        expand-form (fn [form]
                      (if (seq? form)
                        (with-meta (concat [(first form) result-sym]
                                           (next form))
                          (meta form))
                        (list form result-sym)))]
    `(may-await [~result-sym ~expr
                 ~@(interleave (repeat result-sym)
                               (map expand-form forms))]
                ~result-sym)))


(defmacro await->>
  "Equivalent of clojure.core/->> for awaitable intermediate results."
  [expr & forms]
  (let [result-sym (gensym 'result)
        expand-form (fn [form]
                      (if (seq? form)
                        (with-meta (concat (butlast form)
                                           [result-sym]
                                           (last form))
                          (meta form))
                        (list form result-sym)))]
    `(may-await [~result-sym ~expr
                 ~@(interleave (repeat result-sym)
                               (map expand-form forms))]
                ~result-sym)))


(defmacro await-as->
  "Equivalent of clojure.core/as-> for awaitable intermediate results."
  [expr name & forms]
  `(may-await [~name ~expr
               ~@(interleave (repeat name) forms)]
              ~name))
