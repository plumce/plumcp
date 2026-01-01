(ns plumcp.core.util
  "Common (CLJ, CLJS) utility functions and macros"
  (:require [clojure.string :as str]))


;; --- Type coercion ---


(defn as-str
  "Turn given argument (by reading name if named entity) into a string."
  [x]
  (if (keyword? x)
    (if-let [the-ns (namespace x)]
      (str the-ns "/" (name x))
      (name x))
    (str x)))


;; --- String pruning ---


(defn stripl
  "Remove specified token from the left side of string."
  [s token]
  (if (str/starts-with? s token)
    (subs s (count token))
    s))


(defn stripr
  "Remove specified token from the right side of string."
  [s token]
  (if (str/ends-with? s token)
    (subs s 0 (- (count s)
                 (count token)))
    s))
