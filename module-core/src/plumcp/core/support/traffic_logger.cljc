;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.support.traffic-logger
  "Traffic logger implementation and support."
  (:require
   [clojure.string :as str]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]))


(def nop-traffic-logger
  (reify p/ITrafficLogger
    (log-http-request [_ _])
    (log-http-response [_ _])
    (log-http-failure [_ _])
    (log-incoming-jsonrpc-request [_ _])
    (log-outgoing-jsonrpc-request [_ _])
    (log-incoming-jsonrpc-success [_ _ _])
    (log-outgoing-jsonrpc-success [_ _ _])
    (log-incoming-jsonrpc-failure [_ _ _])
    (log-outgoing-jsonrpc-failure [_ _ _])
    (log-incoming-jsonrpc-notification [_ _])
    (log-outgoing-jsonrpc-notification [_ _])
    (log-mcpcall-failure [_ _])
    (log-mcp-sse-message [_ _])))


(defn compact-log-http-request [prefix request]
  (let [method (:request-method request)]
    (-> "%s%s%s %s"
        (format prefix
                (if (#{:get :options :delete} method) ">>>" "-->")
                (-> method u/as-str str/upper-case)
                (:uri request))
        u/eprintln)))


(defn compact-log-http-response [prefix response]

  (-> "%s[%d] %s"
      (format prefix
              (:status response)
              (let [ct (get-in response [:headers "Content-Type"])]
                (case ct
                  nil                ""
                  "application/json" "JSON"
                  "text/event-stream" "SSE Stream"
                  "text/plain"        "Plaintext"
                  ct)))
      u/eprintln))


(defn compact-log-http-failure [prefix failure]
  (u/eprintln (str prefix failure)))


(defn compact-log-jsonrpc-request [prefix jsonrpc-message]
  (-> "%s  -->MCP:%s [%s] %s"
      (format prefix
              (cond
                (jr/jsonrpc-request? jsonrpc-message) "Request"
                (jr/jsonrpc-response? jsonrpc-message) "Response"
                (jr/jsonrpc-notification? jsonrpc-message) "Notification"
                :else "Unknown")
              (:id jsonrpc-message "#")
              (:method jsonrpc-message))
      u/eprintln))


(defn compact-log-jsonrpc-success [prefix id jsonrpc-result]
  (-> "%s  MCP [%s] %s"
      (format prefix (or id "#") jsonrpc-result)
      u/eprintln))


(defn compact-log-jsonrpc-failure [prefix id jsonrpc-error]
  (-> "%s  MCP [%s] ERROR %d"
      (format prefix
              (or id "#")
              (get-in jsonrpc-error [:error :code]))
      u/eprintln))


(defn compact-log-mcp-notification [prefix jsonrpc-notification]
  (-> (str prefix "  MCP Notification ")
      (str jsonrpc-notification)
      u/eprintln))


(defn compact-log-mcpcall-failure [prefix failure]
  (-> "%s    Call Failure: %s"
      (format prefix failure)
      u/eprintln))


(defn compact-log-mcp-sse-message [prefix message]
  (-> "%s    SSE Message: %s"
      (format prefix message)
      u/eprintln))


(defn logger-role
  [role]
  (case role
    :server "💻"
    :client "👤"
    (u/expected! role "to be :server or :client")))


(defn logger-direction
  [direction]
  (case direction
    :in  "📩 "
    :out "📤 "
    (u/expected! direction "to be :in or :out")))


(def role->dir:http-request  {:server :in  :client :out})
(def role->dir:http-response {:server :out :client :in})
(def role->dir:http-failure  {:server :out :client :in})
(def role->dir:incoming-jsonrpc-request {:server :in  :client :in})
(def role->dir:outgoing-jsonrpc-request {:server :out :client :out})
(def role->dir:jsonrpc-pending  {:server :out :client :in})
(def role->dir:incoming-jsonrpc-success {:server :in  :client :in})
(def role->dir:outgoing-jsonrpc-success {:server :out :client :out})
(def role->dir:incoming-jsonrpc-failure {:server :in  :client :in})
(def role->dir:outgoing-jsonrpc-failure {:server :out :client :out})
(def role->dir:incoming-jsonrpc-notification {:server :in  :client :in})
(def role->dir:outgoing-jsonrpc-notification {:server :out :client :out})
(def role->dir:mcpcall-failure  {:server :out :client :out})
(def role->dir:mcp-sse-message  {:server :out :client :in})


(defn make-prefix
  [role role->direction]
  (str (logger-role role)
       (logger-direction (get role->direction role))))


(defn make-compact-traffic-logger
  [role]
  (reify p/ITrafficLogger
    (log-http-request [_ request] (compact-log-http-request
                                   (make-prefix role
                                                role->dir:http-request)
                                   request))
    (log-http-response [_ response] (compact-log-http-response
                                     (make-prefix role
                                                  role->dir:http-response)
                                     response))
    (log-http-failure [_ failure] (compact-log-http-failure
                                   (make-prefix role
                                                role->dir:http-failure)
                                   failure))
    (log-incoming-jsonrpc-request
      [_ jsonrpc-msg] (compact-log-jsonrpc-request
                       (make-prefix role
                                    role->dir:incoming-jsonrpc-request)
                       jsonrpc-msg))
    (log-outgoing-jsonrpc-request
      [_ jsonrpc-msg] (compact-log-jsonrpc-request
                       (make-prefix role
                                    role->dir:outgoing-jsonrpc-request)
                       jsonrpc-msg))
    (log-incoming-jsonrpc-success
      [_ id jr-result] (compact-log-jsonrpc-success
                        (make-prefix role
                                     role->dir:incoming-jsonrpc-success)
                        id jr-result))
    (log-outgoing-jsonrpc-success
      [_ id jr-result] (compact-log-jsonrpc-success
                        (make-prefix role
                                     role->dir:outgoing-jsonrpc-success)
                        id jr-result))
    (log-incoming-jsonrpc-failure
      [_ id jr-error] (compact-log-jsonrpc-failure
                       (make-prefix role
                                    role->dir:incoming-jsonrpc-failure)
                       id jr-error))
    (log-outgoing-jsonrpc-failure
      [_ id jr-error] (compact-log-jsonrpc-failure
                       (make-prefix role
                                    role->dir:outgoing-jsonrpc-failure)
                       id jr-error))
    (log-incoming-jsonrpc-notification
      [_ notification] (compact-log-mcp-notification
                        (make-prefix role
                                     role->dir:incoming-jsonrpc-notification)
                        notification))
    (log-outgoing-jsonrpc-notification
      [_ notification] (compact-log-mcp-notification
                        (make-prefix role
                                     role->dir:outgoing-jsonrpc-notification)
                        notification))
    (log-mcpcall-failure [_ failure] (compact-log-mcpcall-failure
                                      (make-prefix role
                                                   role->dir:mcpcall-failure)
                                      failure))
    (log-mcp-sse-message [_ message] (compact-log-mcp-sse-message
                                      (make-prefix role
                                                   role->dir:mcp-sse-message)
                                      message))))


(def compact-server-traffic-logger (make-compact-traffic-logger :server))
(def compact-client-traffic-logger (make-compact-traffic-logger :client))
