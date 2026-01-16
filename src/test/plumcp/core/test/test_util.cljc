;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.test-util
  #?(:cljs (:require
            ["wait-sync" :as wait-sync])
     :clj (:require
           [clojure.edn :as edn]
           [plumcp.core.util :as u]
           [plumcp.core.util-java :as uj]))
  #?(:cljs (:require-macros [plumcp.core.test.test-util
                             :refer [read-edn-file]])))


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


#?(:clj
   (defmacro read-edn-file
     "Read specified EDN filename if it exists, return nil otherwise."
     [edn-filename]
     (if (uj/file-exists? edn-filename)
       (-> edn-filename
           slurp
           edn/read-string)
       (u/eprintln
        "WARNING: Cannot find file test-config.edn
         Copy test-config.template.edn as test-config.edn and edit suitably"))))


(def test-config "Test config" (read-edn-file "test-config.edn"))


(defmacro pst-rethrow
  "Evaluate body of code. Catch and print stack trace for any exception
   thrown and rethrow."
  [& body]
  `(try
     ~@body
     (catch #?(:cljs :default :clj Exception) e#
       (u/print-stack-trace e#)
       (throw e#))))
