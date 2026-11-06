;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.schema.schema-util
  "Utility functions for constructing schema components."
  (:require
   [clojure.string :as str]
   [clojure.zip :as z]
   [plumcp.core.util :as u]))


;; ----- in-schema utility -----


(defn find-schema-node
  "Find node in a schema using given predicate. Helper function."
  [location pred]
  (loop [loc location]
    (cond
      (nil? loc) nil
      (pred (z/node loc)) loc
      (z/end? loc) nil
      :else (recur (z/next loc)))))


(defn find-schema-location
  "Find zipper location for given key in the schema. Helper function."
  [schema k]
  (find-schema-node
   (z/vector-zip schema)
   #(and (vector? %)
         (= k (first %)))))


(defn find-schema-entry
  "Find a (map) entry in a schema, defined as a vector and first element
   matches the given key."
  [schema k]
  (when-let [loc (find-schema-location schema k)]
    (z/node loc)))


(defn update-schema-entry
  "Update (map) entry identified by specified key, using given function
   (fn [old-entry])->new-entry."
  [schema k f]
  (if-let [loc (find-schema-location schema k)]
    (z/root (z/replace loc (f (z/node loc))))
    schema))


(defn optkey
  "Turn given keyword into an optional key with '?' suffix."
  [k]
  (u/expected! k keyword? "a keyword to make optional key")
  (let [q-suffix "?"
        the-name (name k)]
    (if (str/ends-with? the-name q-suffix)
      k
      (keyword (namespace k) (str the-name q-suffix)))))


;; ----- rebindable -----


(defn ^:redef validate-schema
  "Function (fn [spec])->spec that validates the Malli schema before it
   is returned. Modules with Malli dependency should modify it in dev."
  [spec]
  spec)


;; ----- schema definition -----


(defn map->kv-seq
  "Turn a map into a flat sequence of alternating key/val."
  [m]
  (interleave (keys m) (vals m)))


(declare ts-object)


(defn ts-attrs
  "Lay out given TypeScript-flavoured attribute definitions as Malli
   schema attributes."
  [prop-key prop-spec & more]
  (assert (even? (count more)))
  (assert (keyword? prop-key))
  (assert (every? (comp keyword? first) (partition 2 more)))
  (->> (partition 2 more)
       (cons [prop-key prop-spec])
       (map (fn [[pkey pspec]]
              (let [pname (u/as-str pkey)
                    pspec (if (map? pspec)
                            (ts-object pspec)
                            pspec)]
                ;; In TypeScript, optional attribute names are suffixed
                ;; with '?' (exclusive) - we strip to retrieve the name
                (if (str/ends-with? pname "?")
                  [(-> pname
                       (u/stripr "?")
                       keyword)
                   {:optional true} pspec]
                  [pkey pspec]))))))


(defn ts-object
  "Create Malli schema of the following structure
   [:map
    [prop-key prop-spec]
    ...]
   as if to define attributes of a TypeScript object."
  ([prop-key prop-spec & more]
   (->> (apply ts-attrs prop-key prop-spec more)
        (cons :map)
        vec
        validate-schema))
  ([props-map]
   (->> (map->kv-seq props-map)
        (apply ts-object))))


(defn ts-extends
  "Create Malli schema as in `ts-object` extending one or more TypeScript
   objects."
  ([multi-parent-definitions
    child-prop-key child-prop-spec & more]
   ;; multi-parent-definition should be a vector of zero or more definitions,
   ;; multi-parent-definition should not be directly a definition
   (assert (not= :map (first multi-parent-definitions)))
   (let [child-attrs (when (and child-prop-key
                                child-prop-spec)
                       (apply ts-attrs child-prop-key child-prop-spec more))
         multi-parent-attrs (mapcat rest multi-parent-definitions)
         all-parent-keys (map first multi-parent-attrs)
         all-child-keys  (map first child-attrs)
         all-common-keys (->> (concat all-parent-keys all-child-keys)
                              frequencies
                              (u/filter-vals #(> ^long % 1)))
         all-parent-attrs (-> (fn [m [k & _more :as attr-spec]]
                                (if (contains? m k)
                                  (do  ; common parent keys must match specs
                                    (->> (pr-str attr-spec)
                                         (str "common parent attribute to match ")
                                         (u/expected! (get m k) #(= attr-spec %)))
                                    m)
                                  (assoc m k attr-spec)))
                              (reduce {} multi-parent-attrs)
                              vals)
         new-parent-attrs (->> all-parent-attrs
                               (remove (fn [[k & _]]
                                         (contains? all-common-keys k))))]
     (->> (concat new-parent-attrs child-attrs)
          (cons :map)
          vec
          validate-schema)))
  ([multi-parent-definitions]
   (ts-extends multi-parent-definitions
               nil nil)))
