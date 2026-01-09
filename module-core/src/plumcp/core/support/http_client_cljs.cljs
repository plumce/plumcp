(ns plumcp.core.support.http-client-cljs
  "Ring-style HTTP Client for MCP HTTP Transport using JavaScript fetch"
  (:require
   [clojure.string :as str]
   [plumcp.core.protocols :as p]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u]
   [plumcp.core.util-cljs :as us]))


(defn make-client-context
  [{:keys [traffic-logger]
    :or {traffic-logger stl/nop-traffic-logger}}]
  {:logger traffic-logger})


(defn make-request-opts
  "Make request opts (JS object) from given Ring request."
  [{:keys [uri
           request-method
           headers
           body
           ;; --
           redirect
           mode
           cache
           credentials
           referrer-policy
           signal]
    :or {redirect "follow"
         mode "cors"
         cache "default"
         credentials "same-origin"
         referrer-policy "client"}
    :as _ring-request}]
  (-> {:method (str/upper-case (case request-method
                                 :get "GET"
                                 :post "POST"
                                 :delete "DELETE"))}
      (u/assoc-some :headers (when headers (clj->js headers))
                    :body (when (= :post request-method)
                            body)
                    :redirect redirect
                    :mode mode
                    :cache cache
                    :credentials credentials
                    :referrer-policy referrer-policy
                    :signal signal)
      clj->js))


(defn on-stream-body
  "Read body as a stream, calling `(on-event-str event-lines)` for each
   event.
   Ref: https://developer.mozilla.org/en-US/docs/Web/API/Fetch_API/Using_Fetch"
  [body on-event-str]
  (let [stream (.pipeThrough body (js/TextDecoderStream.))
        reader (.getReader stream)
        leftover (atom "")
        read-chunk (fn [chunk read-next]
                     (loop [buffer (str @leftover chunk)]
                       (let [boundary (str/index-of buffer "\n\n")]
                         (if (nil? boundary)
                           (reset! leftover buffer)
                           (let [event-str (subs buffer 0 boundary)
                                 buffer (subs buffer (+ boundary 2))]
                             (on-event-str event-str)
                             (recur buffer)))))
                     (read-next))
        read-stream (fn [read-next]
                      (-> (.read reader)  ; returns js/Promise
                          (.then (fn [result]
                                   (let [done? (us/pget result :done)
                                         chunk (us/pget result :value)]
                                     (when-not done?
                                       (read-chunk chunk
                                                   read-next)))))))]
    (read-stream read-stream)))


(defn make-call [{:keys [logger]} ring-request]
  (p/log-http-request logger ring-request)
  (let [uri (:uri ring-request)
        opts (make-request-opts ring-request)]
    (-> (js/fetch uri opts) ; returns JS/promise
        (.then (fn [response]
                 (let [status (us/pget response :status) ; response.status
                       body (us/pget response :body) ; response.body
                       headers (-> response.headers
                                   js/Object.fromEntries
                                   js->clj)
                       headers-lower (-> headers
                                         (update-keys str/lower-case))
                       on-event-str (fn [on-message event-str]
                                      (-> (str/split-lines event-str)
                                          u/parse-sse-event-lines
                                          on-message))]
                   (-> {:status status
                        :headers headers
                        :headers-lower headers-lower
                        :body body
                        :on-sse (fn on-sse [on-event]
                                  (on-stream-body body (partial on-event-str
                                                                on-event)))
                        :on-msg (fn on-msg [on-message]
                                  (-> (.text response) ; returns JS/promise
                                      (.then on-message)))}
                       (u/dotee #(if (<= 200 status 299)
                                   (p/log-http-response logger %)
                                   (p/log-http-failure logger %))))))))))
