(ns plumcp.core.util.json
  "JVM JSON codec utilising clojure/data.json"
  (:require [clojure.data.json :as json]))


(defn json-parse
  "Parse JSON string as data with keywordized keys."
  [json-str]
  (json/read-str json-str :key-fn keyword))


(defn json-parse-str
  "Parse JSON string as data with string (unchanged) keys."
  [json-str]
  (json/read-str json-str))


(defn json-write
  "Write Clojure data as string."
  [clj-data]
  (json/write-str clj-data))
