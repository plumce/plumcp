(ns plumcp.core.support.http-client-java
  "Ring-style HTTP Client for MCP HTTP Transport using Java API"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [plumcp.core.protocols :as p]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u])
  (:import
   [java.io Closeable InputStream]
   [java.net
    Authenticator
    InetSocketAddress
    ProxySelector
    URI]
   [java.net.http
    HttpClient
    HttpClient$Builder
    HttpClient$Redirect
    HttpClient$Version
    HttpHeaders
    HttpRequest
    HttpRequest$BodyPublisher
    HttpRequest$BodyPublishers
    HttpRequest$Builder
    HttpResponse
    HttpResponse$BodyHandler
    HttpResponse$BodyHandlers]
   [java.time Duration]
   [java.util Map]
   [java.util.concurrent Executor Executors ExecutorService]))


;; --- utility ---


(def supported-http-versions
  {"1.1" HttpClient$Version/HTTP_1_1
   "2"   HttpClient$Version/HTTP_2
   "2.0" HttpClient$Version/HTTP_2})


(def supported-http-redirect
  {:always HttpClient$Redirect/ALWAYS
   :never  HttpClient$Redirect/NEVER
   :normal HttpClient$Redirect/NORMAL})


(defmacro instance-or
  [klass value & other]
  `(let [v# ~value]
     (if (instance? ~klass v#)
       v#
       (do ~@other))))


(defn make-client-context
  [& {:keys [authenticator
             http-version
             http-proxy
             http-redirect
             traffic-logger
             timeout-millis]
      :or {authenticator (Authenticator/getDefault)
           traffic-logger stl/nop-traffic-logger
           http-proxy    (ProxySelector/getDefault)}}]
  (let [http-version  (instance-or
                       HttpClient$Version http-version
                       (get supported-http-versions (str http-version)))
        http-proxy    (instance-or
                       ProxySelector http-proxy
                       (ProxySelector/of (InetSocketAddress. http-proxy)))
        http-redirect (instance-or
                       HttpClient$Redirect http-redirect
                       (get supported-http-redirect http-redirect))
        vt-executor   (Executors/newVirtualThreadPerTaskExecutor)
        ;; ---
        f-builder       (fn [^HttpClient$Builder
                             cb] {:client ^HttpClient (.build cb)
                                  :logger traffic-logger})
        f-executor      (fn [^HttpClient$Builder
                             cb] (.executor cb vt-executor))
        f-authenticator (fn [^HttpClient$Builder
                             cb] (.authenticator cb authenticator))
        f-http-version  (fn [^HttpClient$Builder
                             cb] (.version cb http-version))
        f-http-proxy    (fn [^HttpClient$Builder
                             cb] (.proxy cb http-proxy))
        f-conn-timeout  (fn [^HttpClient$Builder
                             cb] (->> (Duration/ofMillis timeout-millis)
                                      (.connectTimeout cb)))
        f-http-redirect (fn [^HttpClient$Builder
                             cb] (.followRedirects cb http-redirect))]
    (cond-> (HttpClient/newBuilder)
      true           (f-executor)
      authenticator  (f-authenticator)
      http-version   (f-http-version)
      timeout-millis (f-conn-timeout)
      http-redirect  (f-http-redirect)
      http-proxy     (f-http-proxy)
      :and-finally   (f-builder))))


(defn stop-client!
  [^HttpClient client]
  (let [^java.util.Optional op (.executor client)
        ^Executor e (.get op)]
    (.shutdownNow ^ExecutorService e)  ; is an ExecutorService instance
    (.close ^HttpClient client)
    (System/gc)))


;; --- implementation ---


(defn make-request
  "Make HTTP request from a Ring-request."
  ^HttpRequest
  [{:keys [uri
           request-method
           headers
           ^String body
           ;; options
           timeout-millis]
    :as _ring-request}]
  (u/expected! uri string? "URI string is required")
  (u/expected-enum! request-method #{:get :post :delete})
  (when headers
    (u/expected! headers map? "headers to be a map of String/String"))
  (when (= :post request-method)
    (u/expected! body some? "Body must be supplied for POST request"))
  (let [f-uri (fn [^HttpRequest$Builder rb] (.uri rb (URI/create uri)))
        ^HttpRequest$BodyPublisher
        bp-body   #(HttpRequest$BodyPublishers/ofString body)
        f-method  (fn [^HttpRequest$Builder rb] (case request-method
                                                  :get (.GET rb)
                                                  :post (.POST rb
                                                               (bp-body))
                                                  :delete (.DELETE rb)))
        f-headers (fn [^HttpRequest$Builder rb] (->> (seq headers)
                                                     flatten
                                                     (into-array String)
                                                     (.headers rb)))
        f-timeout (fn [^HttpRequest$Builder rb] (->> timeout-millis
                                                     Duration/ofMillis
                                                     (.timeout rb)))
        f-build   (fn [^HttpRequest$Builder rb] (.build rb))
        ^HttpRequest
        request (cond-> (HttpRequest/newBuilder)
                  uri            (f-uri)
                  request-method (f-method)
                  (seq headers)  (f-headers)
                  timeout-millis (f-timeout)
                  :finally (f-build))]
    request))


(defn body->string [^InputStream input-stream]
  (-> input-stream
      io/reader
      slurp))


(defn body->string-lines [^InputStream input-stream]
  (-> input-stream
      io/reader
      line-seq))


(defn make-ring-response
  [^HttpResponse response]
  (let [status (.statusCode response)
        headers (-> ^HttpHeaders (.headers response)
                    ^Map (.map)
                    (update-vals (fn [header-vals]
                                   (if (= 1 (count header-vals))
                                     (first header-vals)
                                     header-vals))))
        headers-lower (-> headers
                          (update-keys str/lower-case))
        body (.body response)]
    {:status status
     :headers headers
     :headers-lower headers-lower
     :body body
     :on-sse (fn on-sse [on-event]
               (with-open [^Closeable _ body]
                 (->> (body->string-lines body)
                      u/chunkify-string-lines
                      (map u/parse-sse-event-lines)
                      (map on-event)
                      dorun)))
     :on-msg (fn on-msg [on-message]
               (-> (body->string body)
                   on-message))}))


(def ^HttpResponse$BodyHandler body-handler-input-stream
  (HttpResponse$BodyHandlers/ofInputStream))


(defn make-call [{:keys [^HttpClient client
                         logger]} ring-request]
  (p/log-http-request logger ring-request)
  (let [^HttpRequest  request  (make-request ring-request)
        ^HttpResponse response (.send client request
                                      body-handler-input-stream)]
    (-> (make-ring-response response)
        (u/dotee #(if (<= 200 (:status %) 299)
                    (p/log-http-response logger %)
                    (p/log-http-failure logger %))))))
