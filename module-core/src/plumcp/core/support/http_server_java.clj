(ns plumcp.core.support.http-server-java
  "Ring compatible HTTP server using OpenJDK internal HTTP server."
  (:require
   [clojure.string :as str]
   [plumcp.core.protocols :as p]
   [plumcp.core.util :as u]
   [plumcp.core.util-java :as uj])
  (:import
   [clojure.lang ISeq]
   [com.sun.net.httpserver HttpExchange HttpHandler HttpServer]
   [java.io
    ByteArrayInputStream
    File
    FileInputStream
    InputStream
    OutputStream]
   [java.net InetSocketAddress URI]
   [java.nio.charset StandardCharsets]))


(defn make-ring-request
  [^HttpExchange exchange port]
  (let [^URI uri-obj (.getRequestURI exchange)
        uri (.getPath uri-obj)
        query-string (str (.getQuery uri-obj))
        method (-> (.getRequestMethod exchange)
                   (str/lower-case)
                   keyword)
        headers (-> (.getRequestHeaders exchange)
                    (update-keys str/lower-case)
                    (update-vals #(str/join "," %)))
        protocol (.getProtocol exchange)]
    (-> {:uri uri
         :query-string query-string
         :request-method method
         :headers headers
         :protocol protocol
         :scheme :http
         :server-name "localhost"
         :server-port port}
        (u/assoc-some :body (.getRequestBody exchange)
                      :on-msg (when (#{:post :put} method)
                                (fn on-msg [on-message]
                                  (-> (.getRequestBody exchange)
                                      slurp
                                      on-message)))))))


(defn send-http-response!
  [ring-response ^HttpExchange exchange]
  ;; set headers
  (let [hdrs (.getResponseHeaders exchange)]
    (doseq [[k v] (seq (:headers ring-response))]
      (when (and (u/non-empty-string? k)
                 (or (u/non-empty-string? v)
                     (and (coll? v)
                          (every? u/non-empty-string? v))))
        (.put hdrs k (if (string? v) [v] (u/as-vec v))))))
  ;; set body
  (with-open [^OutputStream out (.getResponseBody exchange)]
    (let [^long status (:status ring-response 500)
          body (:body ring-response)
          send-status! (fn [^long bodylen]
                         (.sendResponseHeaders exchange status bodylen))
          send-bytes!  (fn [^bytes bs]
                         (let [^ByteArrayInputStream
                               in (ByteArrayInputStream. bs)]
                           (.transferTo ^ByteArrayInputStream in out)))]
      (cond
        ;; String
        (string? body)
        (let [^bytes bs (.getBytes body StandardCharsets/UTF_8)]
          (send-status! (alength bs))
          (send-bytes! bs))
        ;; ISeq (stream)
        (instance? ISeq body)
        (do
          (send-status! 0)
          (doseq [each body]
            (when (some? each)
              (let [^bytes bs (-> (str each)
                                  (.getBytes StandardCharsets/UTF_8))]
                (send-bytes! bs)
                (.flush out)))))
        ;; nil
        (nil? body)
        (send-status! 0)
        ;; InputStream, send it in a tight loop
        (instance? InputStream body)
        (do
          (send-status! 0)
          (.transferTo ^InputStream body out))
        ;; byte array
        (bytes? body)
        (do
          (send-status! (alength body))
          (send-bytes! body))
        ;; file
        (instance? File body)
        (do
          (send-status! (.length ^File body))
          (.transferTo (FileInputStream. body) out))
        :else
        (do
          (u/eprintln "Unexpected body" body)
          (u/throw! "Unexpected body" {:body body})))
      (.flush out)))
  ;; after the response is sent
  (when-let [callback (:callback ring-response)]
    (callback)))


(defn run-http-server
  [ring-handler & {:keys [port
                          executor
                          error-handler]
                   :or {port 3000
                        executor uj/virtual-executor
                        error-handler u/print-stack-trace}}]
  (let [^HttpServer
        server (HttpServer/create (InetSocketAddress. port) 0)]
    (doto server
      (.createContext "/"
                      (reify HttpHandler
                        (handle [_ exchange]
                          (try
                            (let [ring-request (make-ring-request exchange
                                                                  port)
                                  ring-response (ring-handler ring-request)]
                              (send-http-response! ring-response exchange))
                            (catch Throwable e
                              (error-handler e))))))
      (.setExecutor executor)
      (.start))
    (reify p/IStoppable
      (stop! [_] (.stop server 0)))))
