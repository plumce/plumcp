;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.impl.server-handler
  "Utility functions for (fn handler [request])->response handlers."
  (:require
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.util.async-bridge :as uab]))


(defn wrap-exception-catching [handler]
  (fn exception-catching-handler
    [request]
    (try
      (let [response (handler request)]
        #?(:cljs (if (uab/awaitable? response)
                   (doto response
                     (.catch #(rs/log-http-failure request %)))
                   response)
           :clj response))
      (catch #?(:cljs js/Error
                :clj Exception) e
        (rs/log-http-failure request e)
        (throw e)))))


(defn wrap-traffic-logger [handler]
  (fn traffic-logging-ring-handler
    [request]
    (rs/log-http-request request)
    (uab/may-await [response (handler request)]
      (rs/log-http-response request response)
      response)))


(defn wrap-trim-response [handler]
  (fn jsonrpc-response-trimmer [request]
    (uab/may-await [response (handler request)]
      (when (some? response)  ; nil handler response for notification/response
        (jr/jsonrpc-trim-response response)))))


(defn wrap-mcp-session
  ([handler session-request-fn]
   (fn session-attaching-handler [request]
     (-> request
         session-request-fn
         handler)))
  ([handler session-request-fn session-response-fn]
   (fn session-attaching-handler-custom-response [request]
     (let [updated-request (-> request
                               session-request-fn)]
       (uab/may-await [response (handler updated-request)]
         (session-response-fn updated-request response))))))


(defn wrap-dependency
  "Wrap given handler to have request map dependency using the specified
   request-updater."
  [handler request-updater dependency]
  (fn dependency-attaching-handler [request]
    (-> request
        (request-updater dependency)
        (handler))))
