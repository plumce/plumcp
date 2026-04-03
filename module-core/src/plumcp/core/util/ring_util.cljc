;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util.ring-util
  "Ring utility functions"
  (:require
   [clojure.string :as str]))


(defn content-type->media-type
  "Given HTTP Content-Type string value, parse and return the media-type
   only, ignoring any charset info.
   | Input                           | Output           |
   |---------------------------------|------------------|
   | application/json                | application/json |
   | application/json; charset=utf-8 | application/json |"
  [content-type]
  (some-> content-type
          (str/split #";")
          first
          str/trim))


(defn accept-media-types
  "Get all 'Accept' header media types (ignoring charset) as a set from
   the given Ring request."
  [request]
  (->> (some-> request
               (get [:headers "accept"])
               (str/split #",")) ;; all accepted content types
       (map content-type->media-type)
       (reduce conj #{})))


(defn content-media-type
  "Get content media-type from given request or response."
  ([request-or-response content-type-header-key]
   (-> request-or-response
       (get-in [:headers content-type-header-key])
       content-type->media-type))
  ([request-or-response]
   (let [find-content-type (fn [m]
                             (or (get m "content-type")
                                 (get m "Content-Type")))]
     (some-> (:headers request-or-response)
             find-content-type
             content-type->media-type))))
