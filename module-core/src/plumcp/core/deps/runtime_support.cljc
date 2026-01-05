(ns plumcp.core.deps.runtime-support
  "Second/higher order runtime utility functions."
  (:require
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.protocols :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]))


;; --- Traffic Logger ---


(defn log-mcpcall-failure [context error]
  (-> (rt/?traffic-logger context)
      (p/log-mcpcall-failure error)))


;; --- MCP Logger ---


(defn log
  "Log given message or data into the session at specified level."
  [context log-level log-msg-or-data]
  (let [session (rt/?session context)
        mcp-logger (rt/?mcp-logger context)]
    (when (p/can-log-level? session log-level)
      ;; minor chance of a race condition here (when another thread
      ;; resets log-level) - accptable for efficiency
      (->> (if (some? mcp-logger)
             {:logger mcp-logger}
             {})
           (eg/make-logging-message-notification log-level
                                                 log-msg-or-data)
           (p/send-message-to-client session context)))))


(defmacro with-logger
  "Associate logger (for log events) in the lexical scope."
  [[context logger] & body]
  (assert (symbol? context))
  `(let [~context (du/?mcp-logger ~context (u/as-str ~logger))]
     ~@body))


(defn log-7-debug     [ctx entry] (log ctx sd/log-level-7-debug entry))
(defn log-6-info      [ctx entry] (log ctx sd/log-level-6-info entry))
(defn log-5-notice    [ctx entry] (log ctx sd/log-level-5-notice entry))
(defn log-4-warning   [ctx entry] (log ctx sd/log-level-4-warning entry))
(defn log-3-error     [ctx entry] (log ctx sd/log-level-3-error entry))
(defn log-2-critical  [ctx entry] (log ctx sd/log-level-2-critical entry))
(defn log-1-alert     [ctx entry] (log ctx sd/log-level-1-alert entry))
(defn log-0-emergency [ctx entry] (log ctx sd/log-level-0-emergency entry))


;; --- Session ---


;; Initialization timestamp


(defn get-initialized-timestamp
  [context]
  (-> (rt/?session context)
      p/get-init-ts))


(defn set-initialized-timestamp
  [context]
  (-> (rt/?session context)
      p/set-init-ts))


;; Subscriptions


(defn add-subscription
  [context uri]
  (-> (rt/?session context)
      (p/enable-subscription uri)))


(defn remove-subscription
  [context uri]
  (-> (rt/?session context)
      (p/remove-subscription uri)))


;; Logging


(defn set-log-level
  "Set current log level."
  [context log-level]
  (-> (rt/?session context)
      (p/set-log-level log-level)))


;; Progress tracking


(defn update-peer-progress
  "Update the progress reported."
  [context progress-token progress]
  (-> (rt/?session context)
      (p/update-progress progress-token progress)))


;; Task cancellation


(defn cancel-requested?
  "Return true if cancellation requested for request ID, false otherwise
   - use current request ID if request ID is unspecified."
  [session request-id]
  (p/cancel-requested? session request-id))


(defn request-cancellation
  "Request cancellation of given task request ID."
  [context request-id]
  (-> (rt/?session context)
      (p/request-cancellation request-id)))


;; --- Notification listeners ---


(defn get-notification-listener
  [context method-name]
  (-> (rt/?notification-listeners context)
      (get method-name)))
