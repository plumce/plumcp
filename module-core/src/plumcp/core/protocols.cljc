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
  (log-jsonrpc-pending [this])
  (log-incoming-jsonrpc-success [this id result])
  (log-outgoing-jsonrpc-success [this id result])
  (log-incoming-jsonrpc-failure [this id error])
  (log-outgoing-jsonrpc-failure [this id error])
  (log-mcp-notification [this notification])
  (log-mcpcall-failure [this error])
  (log-mcp-sse-message [this message]))
