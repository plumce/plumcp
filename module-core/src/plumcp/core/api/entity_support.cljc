;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.api.entity-support
  "Convenience fns for making schema entities."
  (:require
   [plumcp.core.api.entity-gen :as eg]))


;; --- Initialization ---


(defn make-info
  "Make (server or client) implementation info."
  ([name version]
   (eg/make-implementation name version))
  ([name version title]
   (eg/make-implementation name version {:title title})))


;; --- Prompts ---


(defn make-text-prompt-message
  [role-string text]
  (eg/make-prompt-message role-string
                          (eg/make-text-content text {})))


(defn make-image-prompt-message
  [role-string image mime-type]
  (eg/make-prompt-message role-string
                          (eg/make-image-content image mime-type {})))


(defn make-audio-prompt-message
  [role-string audio mime-type]
  (eg/make-prompt-message role-string
                          (eg/make-audio-content audio mime-type {})))


;; --- Resources ---


(defn make-text-resource-result
  "Make single text based read-resource result."
  ([uri text mime-type]
   (-> [(eg/make-text-resource-contents uri text {:mime-type mime-type})]
       (eg/make-read-resource-result {})))
  ([uri text]
   (make-text-resource-result uri text nil)))


(defn make-blob-resource-result
  "Make single BLOB based read-resource result."
  ([uri blob mime-type]
   (-> [(eg/make-blob-resource-contents uri blob {:mime-type mime-type})]
       (eg/make-read-resource-result {})))
  ([uri blob]
   (make-blob-resource-result uri blob nil)))


;; --- Tools ---


(defn make-text-tool-result
  "Make single text call-tool result."
  [text]
  (-> text
      eg/make-text-content
      vector
      eg/make-call-tool-result))


(defn make-image-tool-result
  "Make single image call-tool result."
  [image mime-type]
  (-> image
      (eg/make-image-content mime-type)
      vector
      eg/make-call-tool-result))


(defn make-audio-tool-result
  "Make single audio call-tool result."
  [audio mime-type]
  (-> audio
      (eg/make-audio-content mime-type)
      vector
      eg/make-call-tool-result))


(defn make-resource-link-tool-result
  "Make single resource-link call-tool result."
  [resource-uri resource-name]
  (-> resource-uri
      (eg/make-resource-link resource-name)
      vector
      eg/make-call-tool-result))


(defn make-text-resource-tool-result
  "Make single text resource call-tool result."
  [uri text]
  (-> (eg/make-text-resource-contents uri text)
      eg/make-embedded-resource
      vector
      eg/make-call-tool-result))


(defn make-blob-resource-tool-result
  "Make single blob resource call-tool result."
  [uri blob]
  (-> (eg/make-blob-resource-contents uri blob)
      eg/make-embedded-resource
      vector
      eg/make-call-tool-result))
