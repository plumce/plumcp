;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.test-util
  #?(:cljs (:require
            [clojure.test :as t :include-macros true]
            ["wait-sync" :as wait-sync]
            [plumcp.core.util :as u]
            [plumcp.core.util.async-bridge :as uab])
     :clj (:require
           [clojure.edn :as edn]
           [clojure.string :as str]
           [clojure.test :as t]
           [plumcp.core.util :as u]
           [plumcp.core.util-java :as uj]
           [plumcp.core.util.async-bridge :as uab]))
  #?(:cljs (:require-macros [plumcp.core.test.test-util
                             :refer [find-os-windows?
                                     find-project-dir
                                     read-edn-file
                                     async-test
                                     async-each]])))


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


#?(:clj (defmacro find-os-windows?
          "Find whether project OS at compile-time is Windows."
          []
          (-> (System/getProperty "os.name")
              str/lower-case
              (str/starts-with? "windows"))))


(def os-windows? "Project OS Windows?" (find-os-windows?))


#?(:clj (defmacro find-project-dir
          "Find project dir at compile-time."
          []
          (System/getProperty "user.dir")))


(def project-dir "Project directory" (find-project-dir))


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


(defmacro async-test
  "CLJC replacement for CLJS `cljs.test/async` macro - is dummy in CLJ."
  [[done-sym] & body]
  (assert (symbol? done-sym) "First arg must be a vector of one symbol")
  (if (:ns &env) ;; :ns only exists in CLJS
    `(t/async ~done-sym ~@body)
    `(let [~done-sym u/nop] ~@body)))


(defn async-each*
  [coll body-f]
  (async-test [done]
    (let [coll-atom (atom coll)
          next-f (fn thisfn []
                   (if (nil? @coll-atom)
                     (uab/async-thunk done)
                     (let [item (first @coll-atom)]
                       (swap! coll-atom next)
                       (body-f thisfn item))))]
      (next-f))))


(defmacro async-each
  "Like async-test, but for a collection of parameters, each of which
   needs to be used to evaluate the body. The evaluations are chained
   one after another."
  [[each coll] & body]
  `(async-each* ~coll (fn [next# ~each]
                        (uab/may-await [_# (do ~@body)]
                          (next#)))))
