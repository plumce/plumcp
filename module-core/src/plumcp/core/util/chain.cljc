(ns plumcp.core.util.chain
  "Async-compatible code pipeline."
  (:require
   [plumcp.core.util :as u]))


(defn chain->
  "Chain given context through a sequence of step functions
   (fn step-fn [context out-fn])."
  [context & steps]
  (if (seq steps)
    (let [f (first steps)]
      (f context
         (fn [post-context]
           (apply chain-> post-context (rest steps)))))
    context))


;; --- Utility to make step-functions operating on chaining context maps ---

;; Naming hint:
;; ------------
;;
;; 3-letter naming represents [in process out]
;;
;; For in/out:
;; > implies transition - input or output
;; - implies inertness - no operation
;;
;; For process:
;; ! implies asynchronous
;; - implies synchronous/regular


(defn -!-
  "Make step-fn that accepts context map, invokes async proc-fn with it
   and puts the result out."
  [proc-fn]
  (fn [context f]
    (proc-fn context
             (fn [out-val]
               (-> out-val
                   (f))))))


(defn -!>
  "Make step-fn that accepts context map, invokes async proc-fn with it
   and puts the result under out-key in the context map."
  [proc-fn out-key]
  (fn [context f]
    (proc-fn context
             (fn [out-val]
               (-> context
                   (assoc out-key out-val)
                   (f))))))


(defn >!>
  "Make step-fn that accepts the value at in-key from context map,
   invokes async proc-fn with it and puts the result under out-key in
   the context-map."
  [in-key proc-fn post out-key]
  (fn [context f]
    (let [in-val (get context in-key)]
      (u/expected! in-val some?
                   (str "input key " in-key
                        " to exist in context"))
      (proc-fn in-val
               (fn [out-val]
                 (-> context
                     (assoc out-key (post out-val))
                     (f)))))))


(defn >->
  "Make step-fn that accepts the value at in-key from context map,
   invokes synchronous proc-fn with it and puts the result under out-key
   in the context-map."
  [in-key proc-fn out-key]
  (fn [context f]
    (let [in-val (get context in-key)]
      (u/expected! in-val some?
                   (str "input key " in-key
                        " to exist in context"))
      (-> context
          (assoc out-key (proc-fn in-val))
          (f)))))


(defn >--
  "Make step-fn that accepts the value at in-key from context map,
   invokes synchronous proc-fn with it and returns the result (updated
   context)."
  [in-key proc-fn]
  (fn [context f]
    (let [in-val (get context in-key)]
      (u/expected! in-val some?
                   (str "input key " in-key
                        " to exist in context"))
      (proc-fn in-val)
      (f context))))


(defn ---
  "Make step-fn that accepts context map as input, invokes synchronous
   proc-fn with it and returns (updated) context map."
  [proc-fn]  ; shorthand: turn isolated fn into chained
  (fn [context f]
    (-> context
        proc-fn
        f)))


(defn -->
  "Make step-fn that accepts context map as input, invokes synchronous
   proc-fn with it and puts the result under out-key in the context-map."
  [f out-key]  ; shorthand: fold isolated fn's result under out-key
  (fn [context g]
    (-> context
        (assoc out-key (f context))
        g)))
