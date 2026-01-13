(ns plumcp.core.util.json
  "JVM JSON codec utilising Jsonista"
  (:require [jsonista.core :as json]))


(defn json-parse
  "Parse JSON string as data with keywordized keys."
  [json-str]
  (json/read-value json-str json/keyword-keys-object-mapper))


(defn json-parse-str
  "Parse JSON string as data with string (unchanged) keys."
  [json-str]
  (json/read-value json-str))


(defn json-write
  "Write Clojure data as string."
  [clj-data]
  (json/write-value-as-string clj-data))
