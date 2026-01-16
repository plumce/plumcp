;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util-java
  "Clojure (JVM) specific utility fns and macros."
  (:require
   [clojure.java.io :as io]
   [clojure.java.process :as jp]
   [clojure.string :as str])
  (:import
   [java.io IOException]
   [java.util.concurrent ExecutorService Executors]))


(defn file-exists?
  [filename]
  (.exists (io/file filename)))


(def ^ExecutorService virtual-executor
  (Executors/newVirtualThreadPerTaskExecutor))


(defmacro background-exec
  "Execute body of code in a virtual thread. Returns nothing."
  [& body]
  `(.start (Thread/ofVirtual)
           (^{:once true} fn* [] ~@body)))


(defmacro when-io-open
  "Execute given body of code with implicit open stream. Return `nil` on
   stream-closed condition depicted by `java.io.IOException` thrown with
   'Stream closed' message."
  [& body]
  `(try (do ~@body)
        (catch IOException e#
          (when-not (#{"Stream closed"
                       "Stream Closed"}
                     (.getMessage e#))
            (throw e#)))))


(defn safe-io-seq
  "Given a lazy sequence backed by InputStream/Reader, return a safer
   version that ends the sequence on stream-closed, instead of throwing
   `java.io.IOException`."
  [io-seq]
  (when-let [the-seq (when-io-open (seq io-seq))]
    (when-let [[elem] (when-io-open [(first the-seq)])]
      (cons elem
            (lazy-seq (safe-io-seq (when-io-open (next the-seq))))))))


(defn safe-line-seq
  "Safer version of `clojure.core/line-seq` that ends the sequence on
   stream closed, instead of throwing `java.io.IOException`."
  [rdr]
  (->> (when-io-open (line-seq rdr))
       safe-io-seq))


(defn require-and-resolve
  "Dynamically resolve the fully-qualified var by name, and return the
   var. Substitute for `clojure.core/requiring-resolve` but return nil
   if var cannot be resolved (insted of throwing an exception)."
  [fully-qualified-var-name]
  (try
    (-> (symbol fully-qualified-var-name)
        requiring-resolve)
    (catch Exception _
      nil)))


(defn env-val
  "Return the environment variable value if defined, nil otherwise."
  [env-var-name]
  (System/getenv (str env-var-name)))


(def platform-opener
  "Platform-specific command or executable name to open a file/URL."
  (let [platform (-> (System/getProperty "os.name")
                     str/lower-case)]
    (cond
      (str/starts-with? platform "mac os x") "/usr/bin/open"
      (str/starts-with? platform "windows") "start"  ; ["cmd" "/c" "start"]
      :else #_linux "xdg-open")))


(defn browse-url
  "Open given URL in browser, returning java.lang.Process object."
  ([url]
   ;; clojure.java.browse/browse-url doesn't return java.lang.Process,
   ;; which is required to close the browser, so use platform-opener
   ;; when env var YUMCP_BROWSER is not defined
   (let [browser (or (env-val "PLUMCP_BROWSER")
                     platform-opener)]
     (browse-url url browser)))
  ([url browser-executable-name]
   ;; not setting DISCARD causes 'xdg-open' to hang
   (jp/start {:out :discard
              :err :discard} browser-executable-name url)))
