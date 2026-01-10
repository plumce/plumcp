(ns plumcp.core.server.http-ring-stream
  "Server-sent Events (SSE) streamable support for Ring server transport"
  (:require
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.protocols :as p]
   [plumcp.core.server.http-ring-stream-util :as hrsu]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.stream :as um]))


(defn push-msg-receiver
  "Push-message receiver for Ring (server) transport."
  [context message]
  (let [session (rt/?session context)
        [stream-id
         stream-atom] (if (rt/has-response-stream? context)
                        (rt/?response-stream context)
                        (p/get-default-stream session))
        event-id (hrsu/stream-id->event-id stream-id)
        wrapped-event {:event-id event-id
                       :message message}]
    (um/append-to-stream! stream-atom wrapped-event)
    (p/append-to-stream session event-id wrapped-event)))


(defn wrapped-event->sse-event
  [wrapped-event]
  (u/make-sse-event-string (:event-id wrapped-event)
                           (:message wrapped-event)))


(defn prepare-stream
  [stream-iterator]
  (uab/map-iterator (fn [wrapped-event]
                      (wrapped-event->sse-event wrapped-event))
                    stream-iterator))


(defn extract-server-messages-sse-events
  "Extract all server messages repeatedly until the request is handled,
   returning a list of SSE string-messages."
  [stream-atom poll-millis]
  (-> (um/stream-items stream-atom poll-millis)
      prepare-stream))


(defn extract-default-server-messages-sse-events
  "Extract server messages from the default stream."
  [session poll-millis]
  (let [[_ default-stream-atom] (p/get-default-stream session)]
    (->> (um/stream-items default-stream-atom poll-millis)
         prepare-stream
         ;; unblock the (potentially empty) stream
         (uab/cons-iterator ""))))


(defn extract-resumable-stream-sse-events
  [session last-event-id]
  (let [stream-id (hrsu/event-id->stream-id last-event-id)
        take-event (volatile! false)]
    ;; fetch stored stream
    (->> (p/make-stream-seq session stream-id)
         (uab/map-iterator (fn [wrapped-event]
                             (if (deref take-event)
                               (wrapped-event->sse-event wrapped-event)
                               (do
                                 (when (= last-event-id
                                          (:event-id wrapped-event))
                                   (vreset! take-event true))
                                 "")))))))


(defn sse-events-seq
  "Structure SSE events as an `ISeq` (Ring body) instance."
  [sse-events]
  (cond
    (seq? sse-events) sse-events
    (uab/iterator?
     sse-events)      sse-events
    (nil? sse-events) ()  ; empty list is an `ISeq`
    (= [] sse-events) ()  ; `list*` turns empty vector into `nil`
    (vector? sse-events) (list* sse-events)
    :else (u/expected! sse-events
                       "sse-events to be a sequence/list or vector")))
