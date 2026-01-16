(ns plumcp.core.protocols
  "All protocols in one place")


;; ----- Capability -----


(defprotocol IMcpCapability
  (get-capability-declaration [this] "Get declaration map for the capability"))


(defprotocol IMcpSampling
  (get-sampling-response [this sampling-request] "Get sampling response"))


(defprotocol IMcpElicitation
  (get-elicitation-response [this elicit-request] "Get elicitation response"))


;; Logging capability is implicitly enabled (via session)


(defprotocol IMcpCompletion
  (completion-complete [this
                        param-ref
                        param-arg] "Return vector of completion string"))


(defprotocol IMcpListedCapability
  (obtain-list [this method-name] "Obtain list for this capability/method"))


(defprotocol IMcpInvokableCapability
  (find-handler [this args]
    "Find handler based on args, returning {:handler ...} on success,
     nil otherwise."))


;; --- Traffic logger ---


(defprotocol ITrafficLogger
  (log-http-request [this request])
  (log-http-response [this response])
  (log-http-failure [this failure])
  (log-incoming-jsonrpc-request [this request])
  (log-outgoing-jsonrpc-request [this request])
  (log-incoming-jsonrpc-success [this id result])
  (log-outgoing-jsonrpc-success [this id result])
  (log-incoming-jsonrpc-failure [this id error])
  (log-outgoing-jsonrpc-failure [this id error])
  (log-mcp-notification [this notification])
  (log-mcpcall-failure [this error])
  (log-mcp-sse-message [this message]))


;; --- Server ---


(defprotocol IServerSessionStore
  (get-server-session [this session-id] "Return IServerSession or nil")
  (make-server-streams [this] "Make a new server-streams instance")
  (init-server-session [this session-id
                        server-streams
                        msg-receiver] "Initialize idempotent session by ID")
  (remove-server-session [this session-id] "Remove session by ID")
  (update-server-sessions [this updater] "Update all sessions w/ updater"))


(defprotocol IServerSession
  ;;
  ;; Cancellation
  ;;
  (cancel-requested? [this request-id] "True if cancellation requested")
  (remove-cancellation [this request-id] "Remove cancellation request")
  (request-cancellation [this request-id] "Add cancellation request tracker")
  ;;
  ;; Initialization
  ;;
  (get-init-ts [this] "Get init timestamp if initialized, else nil")
  (set-init-ts [this] "Set init timestamp if un-initialized")
  ;;
  ;; Messages to the client
  ;;
  (send-message-to-client [this context message] "Send a JSONRPC message")
  ;;
  ;; Log level
  ;;
  (get-log-level [this] "Get the current log level")
  (set-log-level [this level] "Set the current log level")
  (can-log-level? [this level] "Return true if can log at specified level")
  ;;
  ;; Progress tracking
  ;;
  (get-progress [this progress-token] "Get progress status")
  (update-progress [this progress-token f] "Update progress")
  (remove-progress [this progress-token] "Remove progress tracking")
  ;;
  ;; Server requests
  ;;
  (extract-pending-request [this req-id] "Remove/return pending request")
  (clear-pending-requests [this req-ids] "Clear pending server-requests")
  (append-pending-requests [this req-map] "Append pending server-requests")
  ;;
  ;; Subscription
  ;;
  (remove-subscription [this uri] "Remove subscription on given URI")
  (enable-subscription [this uri] "Enable subscription on given URI")
  ;;
  )


(defprotocol IServerStreams
  ;;
  ;; Stream handling
  ;;
  (append-to-stream [this stream-id message] "Append message to stream")
  (make-stream-seq [this stream-id] "Get lazy sequence of stream events")
  (get-default-stream [this] "Get [default-stream-id default-stream-atom]")
  ;;
  )


(def nop-server-streams
  (reify IServerStreams
    (append-to-stream [_ _ _])
    (make-stream-seq [_ _])
    (get-default-stream [_])))


;; ----- Server and client -----


(defprotocol IStoppable
  (stop! [this] "Stop the stoppable"))


;; --- MCP Client ---


(defprotocol IHttpClient
  (client-info [this] "Return a map {} of client information")
  (http-call [this ring-request]
    "Send HTTP/Ring request, get HTTP/Ring response with two extra keys
     `{:on-sse (fn [on-message]) :on-msg (fn [on-message])}`. Impl may
     employ JSON-encode/decode or authentication as appropriate."))


(defprotocol IClientTransport
  (client-transport-info [_] "Return {:id :stdio/:http/:zero ..more..}")
  (start-client-transport [_ on-message] "Start the transport - idempotent")
  (stop-client-transport! [_ force?]  "Stop/disconnect transport - idempotent")
  (send-message-to-server [_ message] "Send given message to the server")
  (upon-handshake-success [_ success] "Triggered upon handshake success"))


(defprotocol ITokenCache
  (clear-cache! [_ mcp-server])
  (read-tokens  [_ mcp-server])
  (read-server  [_ mcp-server])
  (read-client  [_ mcp-server])
  (write-tokens! [_ mcp-server data])
  (write-server! [_ mcp-server data])
  (write-client! [_ mcp-server data]))
