(ns plumcp.core.deps.session-mem
  "In-memory session implementation."
  (:require
   [plumcp.core.protocols :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]
   [plumcp.core.util.stream :as us]))


(def ^:const k-cancellation-reqs :cancellation-reqs)
(def ^:const k-initialize-ts     :initialize-ts)
(def ^:const k-log-level         :log-level)
(def ^:const k-log-level-index   :log-level-index)
(def ^:const k-requests-pending  :requests-pending)
(def ^:const k-subscriptions     :subscriptions)
(def ^:const k-progress-tracking :progress-tracking)


(defn level-index
  "Given a log-level string, return the integer index."
  [level]
  (u/expected-enum! level sd/log-level-indices)
  (get sd/log-level-indices level))


(def default-session-init
  "Default initial value of the session state."
  {k-cancellation-reqs #{}
   k-initialize-ts     nil
   k-log-level         sd/log-level-6-info
   k-log-level-index   (level-index sd/log-level-6-info)
   k-requests-pending  {}  ; map {req-id context}
   k-subscriptions     #{} ; set of uri
   k-progress-tracking {}  ; both peer and self
   })


(defn extract!
  "Extract elements from the state-atom at the given key using specified
   updater `(fn [old-val stash-fn]) -> new-val`. The updater should call
   `(stash-fn extracted-val)` before returning the updated value."
  [state-atom k updater-fn]
  (let [stash (volatile! nil)]
    (swap! state-atom
           (fn [old] (update old k
                             updater-fn
                             (fn [extracted]
                               (vreset! stash
                                        extracted)))))
    @stash))


(defn update!
  "Update state-atom value at the given key using an updater fn `f`."
  [state-atom k f & args]
  (apply swap! state-atom
         update k
         f args))


(defn make-in-memory-server-session
  "Make an in-memory server session.
   Arguments:
   (fn push-msg-receiver [context message])"
  [_session-id server-streams push-msg-receiver]
  (let [state (atom default-session-init)
        s-get        (fn [path-key]   (get @state path-key))
        s-get-in     (fn [path-vec]   (get-in @state path-vec))
        s-extract!   (fn [k f]        (extract! state k f))
        s-update-at! (fn [k f & args] (apply update! state k f args))
        s-update!    (fn [f & args]   (apply swap! state f args))
        s-conj!      (fn [k v]        (update! state k conj v))
        s-disj!      (fn [k sub-k]    (update! state k disj sub-k))
        s-canlog?    (fn [^long indx] (<= indx
                                          (-> @state
                                              (get k-log-level-index)
                                              long)))]
    (reify
      p/IServerSession
      ;;
      ;; cancellation
      ;;
      (cancel-requested? [_ req-id] (s-get-in [k-cancellation-reqs
                                               req-id]))
      (remove-cancellation [_ req-id] (s-disj! k-cancellation-reqs
                                               req-id))
      (request-cancellation [_ req-id] (s-conj! k-cancellation-reqs
                                                req-id))
      ;;
      ;; initialization
      ;;
      (get-init-ts [_] (s-get k-initialize-ts))
      (set-init-ts [_] (s-update-at! k-initialize-ts
                                     #(or % (u/now-millis))))
      ;;
      ;; messages to the client
      ;;
      (send-message-to-client [_ context
                               message] (push-msg-receiver context
                                                           message))
      ;;
      ;; log level
      ;;
      (get-log-level [_] (s-get k-log-level))
      (set-log-level [_ level] (let [index (level-index level)]
                                 (s-update! assoc
                                            k-log-level level
                                            k-log-level-index index)))
      (can-log-level? [_ level] (let [index (level-index level)]
                                  (s-canlog? index)))
      ;;
      ;; progress tracking
      ;;
      (get-progress [_ progress-token] (s-get-in [k-progress-tracking
                                                  progress-token]))
      (update-progress [_ progress-token f] (-> k-progress-tracking
                                                (s-update-at! update
                                                              progress-token
                                                              f)))
      (remove-progress [_ progress-token] (-> k-progress-tracking
                                              (s-update-at! dissoc
                                                            progress-token)))
      ;;
      ;; server requests
      ;;
      (extract-pending-request [_ req-id]
        (s-extract! k-requests-pending
                    (fn [req-map stash-fn]
                      (if (contains? req-map req-id)
                        (do
                          (stash-fn (get req-map req-id))
                          (dissoc req-map req-id))
                        req-map))))
      (clear-pending-requests [_ req-ids] (apply s-update-at!
                                                 k-requests-pending
                                                 dissoc req-ids))
      (append-pending-requests [_ req-map] (s-update-at! k-requests-pending
                                                         merge req-map))
      ;;
      ;; subscription
      ;;
      (remove-subscription [_ uri] (s-disj! k-subscriptions uri))
      (enable-subscription [_ uri] (s-conj! k-subscriptions uri))
      ;;--
      p/IServerStreams
      (append-to-stream [_ stream-id
                         message] (p/append-to-stream server-streams
                                                      stream-id message))
      (make-stream-seq [_ stream-id] (p/make-stream-seq server-streams
                                                        stream-id))
      (get-default-stream [_] (p/get-default-stream server-streams)))))


(def ^:const k-response-streams  :response-streams)


(def default-streams-init
  {k-response-streams  {}  ; stream-ID => [message-events...]
   })


(defn make-in-memory-server-streams
  []
  (let [default-stream-id   "push-stream"
        default-stream-atom (us/make-stream-atom)
        state (atom default-streams-init)]
    (reify p/IServerStreams
      ;;
      ;; stream handling
      ;;
      (append-to-stream [_ stream-id
                         message] (->> (fn [m]
                                         (if (contains? m stream-id)
                                           (update m stream-id
                                                   conj message)
                                           (assoc m stream-id
                                                  [message])))
                                       (swap! state
                                              update k-response-streams)))
      (make-stream-seq [_ stream-id] (-> (deref state)
                                         (get-in [k-response-streams
                                                  stream-id])))
      (get-default-stream [_] [default-stream-id default-stream-atom])
      ;;
      )))


(defn make-in-memory-server-session-store
  []
  (let [store-atom (atom {})]
    (reify p/IServerSessionStore
      (get-server-session [_ session-id] (get @store-atom session-id))
      (make-server-streams [_] (make-in-memory-server-streams))
      (init-server-session
        [_ session-id server-streams msg-receiver]
        (-> store-atom
            (swap! (fn [store]
                     (if (contains? store session-id)
                       store
                       (assoc store
                              session-id (make-in-memory-server-session
                                          session-id server-streams
                                          msg-receiver)))))
            (get session-id)))
      (remove-server-session [_ session-id] (swap! store-atom
                                                   dissoc session-id))
      (update-server-sessions [_ updater] (swap! store-atom
                                                 update-vals updater)))))
