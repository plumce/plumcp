(ns plumcp.core.test.test-util
  #?(:cljs (:require ["wait-sync" :as wait-sync]))
  #?(:cljs (:require-macros [plumcp.core.test.test-util])))


(defn sleep-millis
  [^long millis]
  #?(:cljs (wait-sync (double (/ millis 1000)))
     :clj (Thread/sleep millis)))


(defmacro until-done
  "Eval body of code in the context of a false toggle, then wait until
   toggle is set to true."
  [[toggle-sym idle-millis] & body]
  (assert (symbol? toggle-sym) "toggle-sym must be a symbol")
  (assert (int? idle-millis) "idle-millis must be an int")
  `(let [toggle# (volatile! false)
         ~toggle-sym (fn [] (vreset! toggle# true))]
     ~@body
     (while (not @toggle#)
       (sleep-millis ~idle-millis))))
