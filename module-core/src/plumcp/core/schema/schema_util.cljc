(ns plumcp.core.schema.schema-util
  "Utility functions for constructing schema components."
  (:require
   [clojure.string :as str]
   [plumcp.core.util :as u]))


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
        vec))
  ([props-map]
   (->> (map->kv-seq props-map)
        (apply ts-object))))


(defn ts-extends
  "Create Malli schema as in `ts-object` extending one or more TypeScript
   objects."
  [multi-parent-definitions
   child-prop-key child-prop-spec & more]
  ;; multi-parent-definition should be a vector of zero or more definitions,
  ;; multi-parent-definition should not be directly a definition
  (assert (not= :map (first multi-parent-definitions)))
  (let [child-attrs (apply ts-attrs child-prop-key child-prop-spec more)
        multi-parent-attrs (mapcat rest multi-parent-definitions)
        multi-parent-keys (->> multi-parent-attrs
                               (map first)
                               set)
        common-keys (->> child-attrs
                         (map first)
                         (filter multi-parent-keys)
                         set)
        new-parent-attrs (->> multi-parent-attrs
                              (remove (fn [[k & _]]
                                        (contains? common-keys k))))]
    (->> (concat new-parent-attrs child-attrs)
         (cons :map)
         vec)))
