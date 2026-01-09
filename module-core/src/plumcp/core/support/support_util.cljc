(ns plumcp.core.support.support-util
  "Support utilities for this library. Unlike the generic `util` ns,
   this ns implements protocol-aware, dependency-aware utilities."
  (:require [plumcp.core.protocols :as p])
  #?(:cljs (:require-macros [plumcp.core.support.support-util])))


(defmacro with-running
  [[sym running] & body]
  (assert (symbol? sym) "binding 'sym' must be a symbol")
  `(let [~sym ~running]
     (try
       ~@body
       (finally
         (p/stop! ~sym)))))
