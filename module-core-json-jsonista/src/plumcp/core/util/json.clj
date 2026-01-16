;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


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
