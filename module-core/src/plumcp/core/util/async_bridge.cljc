;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


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
  "Create an async value that one can await."
  [value]
  #?(:cljs (js/Promise.resolve value)
     :clj value))


(defn async-thunk
  "Create an async thunk that one can await."
  [thunk]
  #?(:cljs (js/Promise. (fn [return _]
                          (return (thunk))))
     :clj (thunk)))


#?(:cljs
   (defn timeout-promise
     [task-promise timeout-millis ex]
     (->> (us/make-timeout-promise timeout-millis ex)
          (us/race-promises task-promise))))


(defmacro as-async
  "Evaluate body of code in background, returning synchronous result in
   CLJ, whereas a js/Promise in CLJS that you may `let-await` on. The
   [resolve reject] symbols are bound to their namesake fns. You MUST
   call (resolve <value>) to resolve the awaitable, or (reject <error>)
   to error out.
   Options:
   :timeout-millis - (long) timeout in milliseconds awaiting result"
  [[resolve-sym reject-sym] & opts+body]
  (assert (symbol? resolve-sym) "resolve-sym must be a symbol")
  (assert (symbol? reject-sym) "reject-sym must be a symbol")
  (let [[options body] (if (and (> (count opts+body) 1)
                                (map? (first opts+body)))
                         [(first opts+body) (rest opts+body)]
                         [{} opts+body])]
    (if (:ns &env) ;; :ns only exists in CLJS
      `(let [result-promise# (js/Promise. (fn [~resolve-sym ~reject-sym]
                                            (try
                                              ~@body
                                              (catch js/Error e#
                                                (~reject-sym e#)))))
             timeout-millis# (:timeout-millis ~options)]
         (if (nil? timeout-millis#)
           result-promise#
           (let [ex# (ex-info "Timed out awaiting execution to end"
                              {:timeout-millis timeout-millis#})]
             (timeout-promise result-promise#
                              timeout-millis#
                              ex#))))
      `(let [~resolve-sym (promise)
             ~reject-sym (fn [error#]
                           (cond
                             (instance? Throwable
                                        error#) (throw error#)
                             (string? error#) (u/throw! error#)
                             (map? error#) (u/throw! "Error" error#)
                             :else (u/throw! "Error" {:context error#})))
             timeout-millis# (:timeout-millis ~options)]
         (u/background
           (try
             ~@body
             (catch Exception e#
               (~reject-sym e#))))
         (let [retval# (if (nil? timeout-millis#)
                         (deref ~resolve-sym)
                         (deref ~resolve-sym timeout-millis#
                                ::timed-out))]
           (if (= retval# ::timed-out)
             (throw (ex-info "Timed out awaiting execution to end"
                             {:timeout-millis timeout-millis#}))
             retval#))))))


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
