;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.support.http-server-node
  "Ring compatible HTTP server using Node.js built-in http module."
  (:require
   ["http" :as http]
   [clojure.string :as str]
   [plumcp.core.protocol :as p]
   [plumcp.core.util :as u :refer [format]]
   [plumcp.core.util.async-bridge :as uab]))


(defn readable->text
  "Turn Readable into a Promise that realizes into a text.
   Ref: https://stackoverflow.com/a/49428486"
  [^Readable stream]
  (let [fullstr (volatile! "")]
    (-> (fn [resolve reject]
          (.on stream "data" (fn [chunk]
                               (vswap! fullstr str chunk)))
          (.on stream "error" (fn [err] (reject err)))
          (.on stream "end" (fn []
                              (resolve (deref fullstr)))))
        (js/Promise.))))


(defn make-ring-request
  [http-request port protocol]
  (let [url (.-url http-request)
        [uri query-string] (str/split (if (empty? url) "/" url) #"\?" 2)
        method (-> (.-method http-request)
                   (str/lower-case)
                   keyword)
        ;; Header names lower-cased, duplicate values joined with ", "
        ;; Ref: https://nodejs.org/api/http.html#messageheaders
        headers (js->clj (.-headers http-request))]
    (-> {:uri uri
         :query-string (u/url-decode query-string)
         :request-method method
         :headers headers
         :protocol protocol
         :scheme :http
         :server-name "localhost"
         :server-port port}
        (u/assoc-some :on-msg (when (#{:post :put} method)
                                (fn on-msg [on-message]
                                  (-> (readable->text http-request)
                                      (.then on-message))))))))


(defn write-iterator
  "Write (async or sync) iterator out as HTTP response body."
  [iterator ^http.ServerResponse http-response after-end]
  (let [end-response (fn []
                       (.end http-response)
                       (after-end))]
    (uab/do-iterator [each iterator]
      {:on-done end-response}
      (.cork http-response)   ; start buffering (required for flushing)
      (.write http-response each)
      (.uncork http-response) ; flush response buffer
      )))


(defn respond
  [ring-response ^http.ServerResponse http-response]
  (.writeHead http-response
              (:status ring-response 200)
              (clj->js (:headers ring-response {})))
  (let [body (:body ring-response)
        after-end (fn []
                    (when-let [callback (:callback ring-response)]
                      (callback)))]
    (cond
      (nil? body)      (do
                         (.end http-response)
                         (after-end))
      (string? body)   (do
                         (.end http-response body)  ; implies write+end
                         (after-end))
      (uab/iterator?
       body)           (write-iterator body http-response after-end)
      (instance? js/Buffer
                 body) (do
                         (.end http-response body)
                         (after-end))
      (instance? js/ArrayBuffer
                 body) (let [buffer (js/Buffer.from body)]
                         (.end http-response buffer)
                         (after-end))
      :else
      (do
        (u/eprintln "Unexpected body" body)
        (u/throw! "Unexpected body" {:body body})))))


(defn make-request-handler
  [port ring-handler error-handler]
  (fn [^http.IncomingMessage http-request
       ^http.ServerResponse http-response]
    (uab/may-await
      [ring-response (try
                       (-> http-request
                           (make-ring-request port
                                              (.-httpVersion http-request))
                           ring-handler)
                       (catch js/Error e
                         (error-handler e)))]
      (try
        (respond ring-response http-response)
        (catch js/Error e
          (error-handler e))))))


(defn run-http-server
  [ring-handler & {:keys [port
                          error-handler]
                   :or {port 3000
                        error-handler u/print-stack-trace}}]
  (let [^http.Server
        server (-> http
                   (.createServer (make-request-handler port ring-handler
                                                        error-handler))
                   (.listen port))]
    (-> server
        (.on "error" (fn [error]
                       (if (= (.-code error) "EADDRINUSE")
                         (u/throw! (format "Port %d already in use" port)
                                   {:port port})
                         (throw error)))))
    (reify p/IStoppable
      (stop! [_] (.close server)))))
