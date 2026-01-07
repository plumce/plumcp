(ns plumcp.core.server.stdio-server
  "STDIO server for Java and Node.js platforms."
  (:require
   #?(:cljs ["readline" :as readline]) ; Node.js
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.impl.server-handler :as sh]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(def default-session-id (u/uuid-v4))


(defn cli-session-request [request msg-pusher]
  (let [session (or (rs/get-server-session request default-session-id)
                    (rs/set-server-session request default-session-id
                                           msg-pusher))]
    (rt/?session request session)))


(defn wrap-json-processing
  [handler]
  (fn json-processing-handler [json-string]
    (uab/may-await [response (-> (u/json-parse json-string)
                                 (handler))]
      (when (some? response)
        (u/json-write response)))))


(defn make-stdio-processor
  "Given MCP request handler fn `(fn [jsonrpc-request]) -> jsonrpc-response`,
   create STDIO handler fn `(fn []) -> jsonrpc-response` that reads a single
   JSON-RPC request from STDIN and writes it out to STDOUT."
  [handler stdin-processor {:keys [stdout-writer
                                   request-logger
                                   response-logger]
                            :or {stdout-writer println
                                 request-logger (fn [request]
                                                  (u/eprintln "\n-->" request
                                                              "<--\n"))
                                 response-logger (fn [response]
                                                   (u/eprintln "\n~~<" response
                                                               ">~~\n"))}}]
  (fn response-processor []
    (stdin-processor (fn [json-string]
                       (when (seq json-string)
                         (request-logger json-string)
                         (uab/may-await [response (handler json-string)]
                           (some-> response  ; nil for notification/response
                                   (doto response-logger)
                                   (stdout-writer))))))))


(defn process-stdin
  "Repeatedly read newline-separated 'lines' of text from process STDIN
   and process each line using `process-line` fn argument."
  [process-line]
  #?(:cljs (-> readline
               (.createInterface #js{:input (.-stdin js/process)
                                     :crlfDelay js/Infinity})
               (.on "line" (fn [line]
                             (process-line line))))
     :clj (while true
            (let [line (read-line)]
              (process-line line)))))


(defn make-stdio-handler
  "Given handler fn `(fn [jsonrpc-message]) -> jsonrpc-message` make a
   handler fn suitable for running MCP-server using STDIO trsnsport."
  [server-runtime mcp-jsonrpc-handler
   & {:keys [stdin-processor stdout-writer]
      :or {stdin-processor process-stdin
           stdout-writer println}
      :as options}]
  (let [msg-pusher (fn [context message]
                     (some-> message  ; notification/response -> nil response
                             u/json-write
                             stdout-writer))
        session-request-fn (fn [request]
                             (cli-session-request request
                                                  msg-pusher))]
    (-> mcp-jsonrpc-handler
        (sh/wrap-mcp-session session-request-fn)
        (sh/wrap-trim-response)
        (rt/wrap-runtime server-runtime)
        (wrap-json-processing)
        (make-stdio-processor stdin-processor options))))
