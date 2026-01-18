;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.support.support-util
  "Support utilities for this library. Unlike the generic `util` ns,
   this ns implements protocol-aware, dependency-aware utilities."
  (:require [plumcp.core.protocol :as p])
  #?(:cljs (:require-macros [plumcp.core.support.support-util])))


(defmacro with-running
  [[sym running] & body]
  (assert (symbol? sym) "binding 'sym' must be a symbol")
  `(let [~sym ~running]
     (try
       ~@body
       (finally
         (p/stop! ~sym)))))
