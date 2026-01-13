(ns plumcp.core.util.json
  "JVM JSON codec utilising Charred"
  (:require [charred.api :as json]))


(defn json-parse
  "Parse JSON string as data with keywordized keys."
  [json-str]
  (json/read-json json-str :key-fn keyword))


(defn json-parse-str
  "Parse JSON string as data with string (unchanged) keys."
  [json-str]
  (json/read-json json-str))


(defn json-write
  "Write Clojure data as string."
  [clj-data]
  (json/write-json-str clj-data))
