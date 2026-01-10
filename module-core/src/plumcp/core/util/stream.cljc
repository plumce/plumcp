(ns plumcp.core.util.stream
  "Stream producing and consuming utility."
  (:require
   [plumcp.core.util :as u]))


(defn make-stream-atom
  "Create a stream buffer/queue for producer/consumer use."
  ([coll]
   (atom {:id (u/uuid-v7)  ; stream identifier (immutable)
          :buffer (vec coll)
          :nadded (count coll)  ; total items added
          :ended? false}))
  ([]
   (make-stream-atom [])))


(defn append-to-stream!
  "Append given item to the stream."
  [stream-atom elem]
  (swap! stream-atom (fn [stream-val]
                       (if (:ended? stream-val)
                         (u/throw! "Stream already ended")
                         (-> stream-val
                             (update :buffer conj elem)
                             (update :nadded inc))))))


(defn stream-nadded
  "Return the number of items added so far to the stream."
  ^long
  [stream-atom]
  (:nadded (deref stream-atom)))


(defn end-stream!
  "End the stream, preventing future addition of any new items."
  [stream-atom]
  (swap! stream-atom assoc :ended? true))


(defn stream-ended?
  "Return true if stream is ended (i.e. no more items may be added),
   false otherwise."
  [stream-atom]
  (:ended? (deref stream-atom)))


(defn stream-peek
  [stream-atom]
  (-> (deref stream-atom)
      :buffer
      first))


(defn stream-items
  "Given a stream atom with the following structure:
   `(atom {:buffer [...]
           :ended? true/false
           ...})`
   extract all buffer elements until the :ended? value is true and
   return a result as follows:
   CLJS: Async iterator of all elements
   CLJ: Lazy seq of all elements"
  [stream-atom ^long idle-millis]
  (let [extract! (fn [] (-> stream-atom
                            (swap-vals! (fn [stream]
                                          (if (seq (:buffer stream))
                                            (assoc stream :buffer [])
                                            stream)))
                            first))]
    #?(:cljs (let [outbox (atom []) ; values ready to return
                   return (fn thisfn [resolve]
                            (swap!
                             outbox
                             (fn [out]
                               (if (seq out)
                                 (do
                                   (resolve #js{:value (first out)
                                                :done false})
                                   (subvec out 1))
                                 (let [{:keys [buffer
                                               ended?]} (extract!)]
                                   (if (seq buffer)
                                     (do
                                       (resolve #js{:value (first buffer)
                                                    :done false})
                                       (subvec buffer 1))
                                     (do
                                       (if ended?
                                         (resolve #js{:value nil
                                                      :done true})
                                         (js/setTimeout #(thisfn resolve)
                                                        idle-millis))
                                       out)))))))]
               #js{:next (fn [] (js/Promise. (fn [resolve reject]
                                               (return resolve))))})
       :clj (let [{:keys [buffer
                          ended?]} (extract!)]
              (if ended?
                buffer
                (concat (if (seq buffer)
                          buffer
                          (do (Thread/sleep idle-millis)
                              []))
                        (lazy-seq (stream-items stream-atom idle-millis))))))))
