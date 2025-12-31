(require '[babashka.fs :as fs]
         '[selmer.parser :as sel])


(def charred
  {:out-dir "out/charred"
   :deps-edn '{:deps {com.cnuernber/charred {:mvn/version "1.037"}}}
   :nsdoc "JVM JSON codec utilising Charred"
   :nsrequire '[charred.api :as json]
   :jsonparse '(json/read-json json-str :key-fn keyword)
   :jsonparsestr '(json/read-json json-str)
   :jsonwrite '(json/write-json-str clj-data)})


(def jsonista
  {:out-dir "out/jsonista"
   :deps-edn '{:deps {metosin/jsonista {:mvn/version "0.3.13"}}}
   :nsdoc "JVM JSON codec utilising Jsonista"
   :nsrequire '[jsonista.core :as json]
   :jsonparse '(json/read-value json-str json/keyword-keys-object-mapper)
   :jsonparsestr '(json/read-value json-str)
   :jsonwrite '(json/write-value-as-string clj-data)})


(def cheshire
  {:out-dir "out/cheshire"
   :deps-edn '{:deps {cheshire/cheshire {:mvn/version "6.1.0"}}}
   :nsdoc "JVM JSON codec utilising Cheshire"
   :nsrequire '[cheshire.core :as json]
   :jsonparse '(json/parse-string json-str true)
   :jsonparsestr '(json/parse-string json-str)
   :jsonwrite '(json/generate-string clj-data)})


(def data-json
  {:out-dir "out/data-json"
   :deps-edn '{:deps {org.clojure/data.json {:mvn/version "2.5.1"}}}
   :nsdoc "JVM JSON codec utilising clojure/data.json"
   :nsrequire '[clojure.data.json :as json]
   :jsonparse '(json/read-str json-str :key-fn keyword)
   :jsonparsestr '(json/read-str json-str)
   :jsonwrite '(json/write-str clj-data)})


(defn emit-target [target]
  (let [basedir (:out-dir target)
        deps-edn-path (str basedir "/deps.edn")
        src-dir-path (str basedir "/src/plumcp/core/util")
        src-file-path (str src-dir-path "/json.clj")]
    ;; create dir
    (fs/create-dirs basedir)
    ;; create deps.edn
    (spit deps-edn-path (str (:deps-edn target)))
    ;; create source file dir
    (fs/create-dirs src-dir-path)
    ;; create source file
    (let [template (slurp "json.clj.template")
          clj-file (sel/render template {:nsdoc (:nsdoc target)
                                         :nsrequire (:nsrequire target)
                                         :jsonparse (:jsonparse target)
                                         :jsonparsestr (:jsonparsestr target)
                                         :jsonwrite (:jsonwrite target)})]
      (spit src-file-path clj-file))))


(defn emit-all []
  (doseq [each [charred
                jsonista
                cheshire
                data-json]]
    (emit-target each)))


(emit-all)
