(ns plumcp.core.util.json
  "JVM JSON codec utilising Cheshire"
  (:require [cheshire.core :as json]))


(defn json-parse
  "Parse JSON string as data with keywordized keys."
  [json-str]
  (json/parse-string json-str true))


(defn json-parse-str
  "Parse JSON string as data with string (unchanged) keys."
  [json-str]
  (json/parse-string json-str))


(defn json-write
  "Write Clojure data as string."
  [clj-data]
  (json/generate-string clj-data))
