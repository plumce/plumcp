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
