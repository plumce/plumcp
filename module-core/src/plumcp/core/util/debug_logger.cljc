;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util.debug-logger
  "Logging for debugging only, not MCP-logging"
  (:require
   [clojure.string :as str]
   [plumcp.core.util :as u :refer [#?(:cljs format)]])
  #?(:cljs (:require-macros [plumcp.core.util.debug-logger])))


(defn throwable?
  [x]
  #?(:cljs (instance? js/Error x)
     :clj (instance? Throwable x)))


(def ^:dynamic *log-context* {})


(defmacro with-logging-context
  [context & body]
  `(binding [*log-context* ~context]
     ~@body))


(defn stringify-context
  "return"
  [context]
  (let [origin-keys [:ns :line :column]
        coord-keys (concat origin-keys [:end-column
                                        :end-line
                                        :file])]
    (->> [(if (every? #(contains? context %) origin-keys)
            (->> (replace context origin-keys)
                 (apply format "%s:%d/%d"))
            "")
          (->> (apply dissoc context coord-keys)
               seq
               sort
               flatten
               (apply pr)
               with-out-str)]
         (filter seq)
         (map #(str \[ % \]))
         (str/join " "))))


(defn eprint-log
  [ctx msg tod]
  (-> "[DEBUG] %s %s %s"
      (format (stringify-context ctx)
              (if (nil? msg)
                (if (throwable? tod)
                  "ERROR:"
                  "")
                msg)
              (cond
                (throwable? tod) (ex-message tod)
                (string? tod) tod
                (nil? tod) ""
                :else (u/pprint-str tod)))
      u/eprintln)
  (when (throwable? tod)
    (u/print-stack-trace tod)))


(def LOG? "Log only if this is true - false by default"
  false #_true)


(defmacro log
  "Log message or exception for debugging."
  ([message throwable-or-data]
   (when LOG?
     `(eprint-log (conj ~(assoc (meta &form)
                                :ns (name (ns-name *ns*)))
                        *log-context*)
                  ~message
                  ~throwable-or-data)))
  ([message-or-throwable]
   `(log nil ~message-or-throwable)))
