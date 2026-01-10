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
