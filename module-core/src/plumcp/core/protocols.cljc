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
