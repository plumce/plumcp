;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.http-ring-stream-util
  "SSE Stream-ID (server internal) <=> SSE Event-ID conversion utility"
  (:require
   [plumcp.core.util :as u]))


(defn stream-id->event-id
  "Augment given stream ID with a generated ID to form an event-ID."
  [stream-id]
  (str (u/uuid-v7) "_" (u/as-str stream-id)))


(defn event-id->stream-id
  "Return the embedded stream-ID from given SSE event-ID."
  [event-id]
  ; UUID-v7 is 36 chars long followed by delimiter '_'
  (subs event-id 37))
