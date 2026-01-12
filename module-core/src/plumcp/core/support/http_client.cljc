(ns plumcp.core.support.http-client
  "Ring-style HTTP Client for MCP HTTP Transport"
  (:require
   #?(:cljs [plumcp.core.support.http-client-cljs :as hcp]
      :clj [plumcp.core.support.http-client-java :as hcp])
   [plumcp.core.protocols :as p]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.support.support-util :as su]
   [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.support.http-client])))


(defn make-http-client
  "Make HTTP client for the purpose of Streamable-HTTP transport."
  [default-uri & {:keys [request-middleware
                         traffic-logger
                         response-middleware]
                  :or {request-middleware identity
                       traffic-logger stl/nop-traffic-logger
                       response-middleware identity}
                  :as connection-options}]
  (let [client-context (-> {:traffic-logger traffic-logger}
                           (merge connection-options)
                           hcp/make-client-context)
        make-call (partial hcp/make-call
                           client-context)]
    (reify
      p/IHttpClient
      (client-info [_] {:default-uri default-uri})
      (http-call [_ request] (-> request
                                 (u/assoc-missing :uri default-uri)
                                 request-middleware
                                 make-call
                                 response-middleware))
      p/IStoppable
      (stop! [_] #?(:cljs nil
                    :clj (hcp/stop-client! (:client client-context)))))))


(defn stop-http-client
  [http-client]
  (p/stop! http-client))


(defmacro with-http-client
  "Evaluate body of code in context of `sym` bound to the HTTP-client
   created with `(make-http-client url options)`. Stop HTTP-client in
   the end. Effective only in CLJ as CLJS needs no cleanup."
  [[sym [url options]] & body]
  (assert (symbol? sym) "binding 'sym' must be a symbol")
  `(su/with-running [~sym (make-http-client ~url ~options)]
     ~@body))


(defn http-get
  [http-client request]
  (p/http-call http-client (-> request
                               (assoc :request-method :get))))


(defn http-post
  [http-client request]
  (p/http-call http-client (-> request
                               (assoc :request-method :post))))
