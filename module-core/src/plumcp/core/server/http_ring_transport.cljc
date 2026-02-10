;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.http-ring-transport
  "Server implementation for Streamable HTTP transport."
  (:require
   [clojure.pprint :as pp]
   [clojure.string :as str]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.http-ring-stream :as hrs]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.stream :as um]))


;; --- Constants ---


(def ^:const hdr-allow
  "Allow (CSV, HTTP methods to be allowed)"
  "Allow")

(def ^:const hdr-ac-ac
  "Access-Control-Allow-Credentials"
  "Access-Control-Allow-Credentials")

(def ^:const hdr-ac-ah
  "Access-Control-Allow-Headers"
  "Access-Control-Allow-Headers")

(def ^:const hdr-ac-am
  "Access-Control-Allow-Methods"
  "Access-Control-Allow-Methods")

(def ^:const hdr-ac-ao
  "Access-Control-Allow-Origin"
  "Access-Control-Allow-Origin")


(def ^:const ct-text-plain "text/plain"        "text/plain")
(def ^:const ct-text-sse   "text/event-stream" "text/event-stream")
(def ^:const ct-app-json   "application/json"  "application/json")


(def ^:const hkv-text-plain
  "Header map {'Content-Type' 'text/plain'}"
  {"Content-Type" ct-text-plain})


;; --- Utility ---


(defn header-contains?
  [request header-name expected-header-value]
  (if-let [header-value (get-in request [:headers header-name])]
    (->> (str/split header-value #"[,;]")
         (some #(= expected-header-value (str/trim %)))
         boolean)
    false))


(defn plain-response
  "Return a Ring response with given status and text/plain body."
  ([status body]
   {:status status
    :headers hkv-text-plain
    :body body})
  ([status headers body]
   (-> (plain-response status body)
       (update :headers merge headers))))


;; --- JSON-RPC middleware ---


(defn jsonrpc-message?
  "Return true if argument is a JSON-RPC message (request, notification
   or response), false otherwise."
  [msg]
  (or (jr/jsonrpc-request? msg)
      (jr/jsonrpc-notification? msg)
      (jr/jsonrpc-response? msg)))


(defn jsonrpc-response->http-status
  "Ref: https://www.jsonrpc.org/historical/json-rpc-over-http.html (3.6.2)"
  [jsonrpc-response]
  (if (map? jsonrpc-response)
    (cond
      (jr/jsonrpc-error? jsonrpc-response)
      (let [error-code (get-in jsonrpc-response [:error :code])]
        (get {; standard JSON-RPC error codes
              sd/error-code-parse-error 500
              sd/error-code-invalid-request 400
              sd/error-code-method-not-found 404
              sd/error-code-invalid-params 500
              sd/error-code-internal-error 500
              ;; MCP-specific codes
              sd/error-code-request-timed-out 408}
             error-code
             500))
      (jr/jsonrpc-result? jsonrpc-response)
      200)
    (u/throw! "Invalid JSON-RPC response"
              {:jsonrpc-response jsonrpc-response})))


(defn jsonrpc-response->ring-response
  [jsonrpc-response]
  (let [body (jr/select-jsonrpc-keys jsonrpc-response)
        status (jsonrpc-response->http-status jsonrpc-response)]
    {:status status
     :body body}))


(defn respond-sse-or-jsonrpc
  "Execute the JSON-RPC task and wait until either (whichever comes first)
   max-1-response or elapsed `lone-timeout`, polling every `lone-polling`.
   Return a regular Ring JSON-RPC response for exactly max-1-response, or
   a Ring MCP SSE response otherwise.
   We do the async call/wait so that we can send a Ring JSON-RPC response
   (instead of unnecessary Ring MCP SSE response) to the simple JSON-RPC
   tasks that do not send notifications to clients."
  [request jsonrpc-task
   lone-timeout-millis lone-poll-millis
   make-sse-response]
  (let [[_ stream-atom] (rt/?response-stream request)
        start-millis (u/now-millis)]
    (u/background
      {:delay-millis 0}  ; run in background without delay
      (try
        (uab/may-await [jsonrpc-response (jsonrpc-task)]
          (->> (jr/select-jsonrpc-keys jsonrpc-response)
               (rs/send-message-to-client request))
          (um/end-stream! stream-atom))
        (catch #?(:cljs js/Error :clj Exception) e
          (->> (jr/jsonrpc-failure sd/error-code-internal-error
                                   (ex-message e) (ex-data e))
               (rs/send-message-to-client request))
          (um/end-stream! stream-atom))))
    (letfn [(decide-response [return poll-millis]  ; return delivers promise
              (if (um/stream-ended? stream-atom)
                ;; stream has ended but not consumed from yet, so
                ;; now we can definitely decide the response type
                (case (um/stream-nadded stream-atom)
                  ;; task didn't respond (either deliberate, or a bug)
                  0 (-> {:status 202}
                        return)
                  ;; this is max-1-response condition
                  1 (-> (um/stream-peek stream-atom)
                        :message
                        jsonrpc-response->ring-response
                        return)
                  ;; let it stream
                  (->> lone-poll-millis
                       (hrs/extract-server-messages-sse-events stream-atom)
                       (make-sse-response)
                       return))
                ;; stream not ended yet, so message count will help
                ;; us decide the next step
                (if (or
                     ;; messages added without stream ending
                     (pos? (um/stream-nadded stream-atom))
                     ;; lone-timeout (waiting for max-1-response)
                     (> (u/now-millis start-millis) ^long lone-timeout-millis))
                  ;; let it stream
                  (->> lone-poll-millis
                       (hrs/extract-server-messages-sse-events stream-atom)
                       (make-sse-response)
                       return)
                  ;; no message emitted yet, wait until lone-timeout
                  (let [poll-millis (if (> ^long poll-millis
                                           ^long lone-poll-millis)
                                      ^long lone-poll-millis
                                      ^long poll-millis)
                        ;; exponentially increasing poll-time
                        next-poll-millis (* 2 poll-millis)]
                    #?(:cljs (js/setTimeout
                              #(decide-response return next-poll-millis)
                              poll-millis)
                       :clj (do
                              (Thread/sleep poll-millis)
                              (recur return next-poll-millis)))))))]
      (uab/as-async [return reject]
        (decide-response return 1)))))


(defn wrap-mcp-response
  "Wrap given Ring handler with a JSON-RPC handler, such that it emits
   MCP response as follows:
   | Ring request type     | Ring response type                        |
   |-----------------------|-------------------------------------------|
   | JSON-RPC Request      | SSE Response body for multi-msg response  |
   |                       | JSON-RPC Response body for max-1-response |
   | JSON-RPC Response     | HTTP 202 without a body                   |
   | JSON-RPC Notification | HTTP 202 without a body                   |
   | HTTP GET (get stream) | SSE Events                                |

   JSON-RPC handler is invoked with JSON-RPC request/response/notification.
   SSE Events are emitted only when a session is already initialized.

   Options:
   :lone-timeout-millis (long int) Timeout before auto-respond as SSE
   :lone-polling-millis (long int) Polling duration for lone-timeout"
  [handler jsonrpc-handler
   {:keys [lone-timeout-millis
           lone-polling-millis]
    :or {lone-timeout-millis #?(:cljs js/Number.MAX_SAFE_INTEGER
                                :clj Long/MAX_VALUE)
         lone-polling-millis 10}}]
  (fn mcp-sse-or-jsonrpc-handler [request]
    (if (contains? #{:get :post} (:request-method request))
      (let [sse-eligible? (and (header-contains? request "accept"
                                                 ct-text-sse)
                               (rt/has-session? request))
            method (:request-method request)
            request-body (:body request)
            get-jsonrpc-message (fn [] (-> request-body
                                           (rt/copy-runtime request)))
            sse-logger #(rs/log-mcp-sse-message request %)
            wrap-sse-log (fn [sse-events]
                           (let [mapper (if (uab/iterator? sse-events)
                                          uab/map-iterator
                                          map)]
                             (mapper (u/tee sse-logger) sse-events)))
            sse-response (fn [sse-events]
                           {:status 200
                            :headers {"Content-Type" ct-text-sse
                                      "Cache-Control" "no-cache"
                                      "Connection" "keep-alive"
                                      "Transfer-Encoding" "chunked"}
                            :body (->> sse-events
                                       wrap-sse-log
                                       hrs/sse-events-seq)})]
        (cond
          ;;
          ;; POST request
          ;;
          (= :post method)
          (if (header-contains? request "content-type" ct-app-json)
            (cond
              ;;
              ;; JSON-RPC request
              ;;
              (jr/jsonrpc-request? request-body)
              (if sse-eligible?
                ;; SSE or JSON-RPC response
                (let [stream-id (str "POST_request_" (:id request-body)
                                     "_" (:method request-body))
                      request (-> request
                                  (rt/?response-stream [stream-id
                                                        (um/make-stream-atom)]))
                      ;; updated request, so update jsonrpc-message too
                      jsonrpc-message (-> (:body request)
                                          (rt/copy-runtime request))
                      jsonrpc-task #(jsonrpc-handler jsonrpc-message)]
                  (respond-sse-or-jsonrpc request jsonrpc-task
                                          lone-timeout-millis
                                          lone-polling-millis
                                          sse-response))
                ;; JSON-RPC response
                (uab/may-await [r (jsonrpc-handler (get-jsonrpc-message))]
                  (jsonrpc-response->ring-response r)))
              ;;
              ;; JSON-RPC response (for an earlier server-request)
              ;;
              (jr/jsonrpc-response? request-body)
              (uab/may-await [_ (jsonrpc-handler (get-jsonrpc-message))]
                {:status 202})
              ;;
              ;; JSON-RPC notification
              ;;
              (jr/jsonrpc-notification? request-body)
              (uab/may-await [_ (jsonrpc-handler (get-jsonrpc-message))]
                {:status 202})
              ;;
              ;; invalid JSON-RPC entity
              ;;
              :else
              (->> "Expected JSON-RPC request/response/notification"
                   (plain-response 400)))
            (->> "Expected Content-Type: application/json (JSON-RPC msg)"
                 (plain-response 415
                                 {"Accept-Post" "application/json"})))
          ;;
          ;; GET request
          ;;
          (= :get method)
          (if sse-eligible?
            (let [session (rt/?session request)]
              (if-let [last-event-id (->> [:headers
                                           sd/mcp-last-event-id-header-lower]
                                          (get-in request))]
                ;; this is a resume-stream request
                (->> last-event-id
                     (hrs/extract-resumable-stream-sse-events session)
                     (sse-response))
                ;; this is a vanilla GET (default stream) request
                (->> lone-polling-millis
                     (hrs/extract-default-server-messages-sse-events session)
                     (sse-response))))
            (->> (str "GET request is meant to fetch SSE stream. "
                      "Request `Accept` header must have `" ct-text-sse
                      "` and a session must be already initialized.")
                 (plain-response 400)))
          ;;
          ;; other methods
          ;;
          :else
          (->> (str "Only GET and POST methods (POST body content-type"
                    " 'application/json') are allowed")
               (plain-response 405))))
      (handler request))))


;; --- JSON body codec ---


(defn wrap-json-request
  [handler]
  (fn json-request-middleware [request]
    (if (header-contains? request "content-type" ct-app-json)
      (let [[maybe-text body-ex] (u/catch! (-> (:on-msg request)
                                               (u/invoke identity)))]
        (if (some? body-ex)
          (plain-response 500 "Error reading request body.")
          (uab/may-await [body-text maybe-text]
            ;; FIXME: Handle Read-error in the js/Promise
            (let [[body-data json-ex] (u/catch! (-> body-text
                                                    u/json-parse))]
              (if (some? json-ex)
                (plain-response 400 "Malformed JSON in request body.")
                (handler (assoc request :body body-data)))))))
      (handler request))))


(defn wrap-json-response
  [handler]
  (fn json-response-middleware [request]
    (uab/may-await [response (handler request)]
      (if (or (get-in response [:headers "Content-Type"])
              (nil? (:body response)))
        response
        (-> response
            (update :body u/json-write)
            (assoc-in [:headers "Content-Type"] ct-app-json))))))


;; ----- OAuth -----


(defn wrap-oauth
  "Wrap given Ring handler with OAuth validation and flow-handshake.
   Option KW-args:
   :auth-enabled?       - boolean (default: false), apply this middleware
                          only if true
   :protected-resource? - (fn [request])->boolean determines if request
                          pertains to an OAuth-protected resource,
                          default implementation always returns true
   :token->claims       - (fn [token])->claims-map returns claims map if
                          token is valid, nil otherwise
   :claims->error       - (fn [claims request])->error-detail or nil,
                          default implementation returns nil (success)
   :resource-metadata   - resource-metadata URL (required if auth enabled)"
  [handler {:keys [auth-enabled?
                   protected-resource?
                   token->claims
                   claims->error
                   resource-metadata]
            :or {auth-enabled? false
                 protected-resource? (constantly true)
                 claims->error (constantly nil)}}]
  (when auth-enabled?
    (u/expected! token->claims fn? "token->claims to be a (fn [token])")
    (u/expected! resource-metadata string? "a valid resource-metadata"))
  (if auth-enabled?
    (let [errh {"WWW-Authenticate"
                (-> "Bearer realm=\"OAuth\", resource_metadata=\"%s\""
                    (format resource-metadata))}
          auth-error (fn [status error detail]
                       {:status status
                        :headers errh
                        :body {:error error
                               :error-description detail}})]
      (fn oauth-gatekeeper [request]
        (if (protected-resource? request)
          (let [auth-header (get-in request [:headers
                                             "authorization"])]
            (cond
              ;; Bearer token in request?
              (when auth-header
                (str/starts-with? auth-header "Bearer "))
              (if-let [sora-claims (try
                                     (token->claims (subs auth-header 7))
                                     (catch #?(:cljs js/Error
                                               :clj Exception) e
                                       (u/dprint "Error validating JWT"
                                                 e)
                                       nil))]
                (uab/may-await [claims sora-claims]
                  (if-let [error-detail (claims->error claims request)]
                    {:status 403
                     :headers errh
                     :body {:error "forbidden"
                            :error-description error-detail}}
                    (handler request)))
                (auth-error 401
                            "unauthorized"
                            "Invalid authorization token"))
              ;; else
              :else
              (auth-error 401
                          "unauthorized"
                          "Missing or invalid authorization header")))
          (handler request))))
    handler))


;; ----- MCP -----


(defn wrap-delete-handler
  [ring-handler]
  (fn delete-method-handler [request]
    (if (= :delete (:request-method request))
      {:status 202}
      (ring-handler request))))


(defn ring-session-request [request]
  (if-let [session-id (get-in request
                              [:headers
                               sd/mcp-session-id-header-lower])]
    (if (= :delete (:request-method request))
      (do
        (rs/remove-server-session request session-id)
        request)
      (let [session (rs/get-server-session request session-id)]
        (rt/?session request session)))
    (if (= sd/method-initialize
           (get-in request [:body :method]))  ; method is 'initialize'
      ;; attach a removable session
      (let [session-id (u/uuid-v4)
            streams (rs/make-server-streams request)]
        (rs/set-server-session request
                               session-id
                               streams
                               hrs/push-msg-receiver)
        (let [session (rs/get-server-session request session-id)]
          (-> request
              (rt/?session session)
              (assoc-in [:headers
                         sd/mcp-session-id-header-lower] session-id))))
      ;; let it process without a session
      request)))


(defn ring-session-response [request response]
  (cond
    ;; request already has session
    (rt/has-session? request)
    (if (= sd/method-initialize
           (get-in request [:body :method]))  ; method is 'initialize'
      (let [session-id (get-in request
                               [:headers
                                sd/mcp-session-id-header-lower])]
        (if (jr/jsonrpc-result? (get response :body))
          ;; response is a success, so add session ID to response header
          (assoc-in response
                    [:headers sd/mcp-session-id-header]
                    session-id)
          ;; response is NOT a success, so destroy session
          (do
            (rs/remove-server-session request session-id)
            (-> response
                (update :headers dissoc sd/mcp-session-id-header)))))
      response)
    ;; otherwise
    :else response))


(defn wrap-dns-rebind-check
  [ring-handler {:keys [allowed-origins
                        allowed-hosts]}]
  (let [allowed-origin? (if (and (seq allowed-origins)
                                 (every? #(not= "*" %) allowed-origins))
                          (set allowed-origins)
                          (constantly true))
        allowed-host? (if (seq allowed-hosts)
                        (set allowed-hosts)
                        (constantly true))]
    (fn dns-rebind-safety-handler
      [request]
      (let [origin (get-in request [:headers "origin"])
            host (get-in request [:headers "host"])]
        (cond
          ;; Origin header check
          (not (allowed-origin?
                origin))
          (->> (format "Origin '%s' is not allowed" origin)
               (plain-response 403))
          ;; Host header check
          (not (allowed-host?
                host))
          (->> (format "Host '%s' is not allowed" host)
               (plain-response 403))
          ;; Fallthrough case
          :else
          (ring-handler request))))))


(defn wrap-cors
  "Enable CORS on the Ring handler for given methods vector (e.g. :get,
   :post etc.) - automatically adds :options."
  ([handler methods]
   (let [methods-str (->> methods
                          (map u/as-str)
                          (map str/upper-case)
                          (str/join ", ")
                          (str "OPTIONS, "))
         methods-set (set methods)]
     (fn cors-handler [request]
       (let [req-hdrs (:headers request)
             req-meth (:request-method request)
             acrhdr (get req-hdrs "access-control-request-headers")
             origin (get req-hdrs "origin")]
         (cond
           ;; OPTIONS
           (= :options req-meth)
           {:status 204
            :headers (-> {hdr-allow methods-str
                          hdr-ac-ac "true"
                          "Vary" "Origin"}
                         ;; CORS specific
                         (u/assoc-some hdr-ac-am methods-str
                                       hdr-ac-ah acrhdr
                                       hdr-ac-ao origin))}
           ;; Other matching methods
           (contains? methods-set req-meth)
           (uab/may-await [response (handler request)]
             (update response :headers
                     (fn [old-headers]
                       (-> (or old-headers {})
                           (u/assoc-some hdr-ac-ao origin)))))
           ;; Non-matching methods
           :else
           {:status 405
            :headers (-> hkv-text-plain
                         (u/assoc-some "Allow" methods-str
                                       hdr-ac-am methods-str
                                       hdr-ac-ao origin))
            :body (str "Only " methods-str " methods allowed on '"
                       (:uri request) "'")})))))
  ([handler]
   (wrap-cors handler [:get])))


(defn wrap-route-match
  [handler uris
   {:keys [methods
           get-uri-routes  ; is a map
           on-method-mismatch
           on-uri-mismatch]
    :or {methods [] ; implies all
         get-uri-routes {}
         on-method-mismatch (fn [request]
                              (->> (:request-method request)
                                   (format "Request method %s is invalid")
                                   (plain-response 405)))
         on-uri-mismatch (fn [_]
                           (plain-response 400 "Bad request - URI mismatch"))}}]
  (let [uri-set (set uris)
        method-pred (if (or (empty? methods)
                            (= :all methods))
                      (constantly true)
                      (let [methods-set (set methods)]
                        (fn [method] (contains? methods-set method))))
        get-uri-routes (-> get-uri-routes
                           (update-vals wrap-cors))
        handler (-> handler
                    (wrap-cors [:get :post :delete]))]
    (fn route-checking-handler
      [request]
      (let [uri (:uri request)
            rqm (:request-method request)
            call (fn [handler]
                   (rs/log-http-request request)
                   (-> (handler request)
                       (u/dotee #(rs/log-http-response request %))))]
        (if (contains? uri-set uri)
          (if (method-pred rqm)
            (handler request)
            (on-method-mismatch request))
          (if (contains? get-uri-routes uri)
            (call (get get-uri-routes uri))
            (on-uri-mismatch request)))))))


(defn fallback-mcp-handler
  [expected-uri-set request]
  (let [match? (fn match?
                 ([test] (if test
                           "✔" #_"✅"
                           "✖" #_"❌"))
                 ([expected actual] (match? (= expected actual))))
        table (with-out-str
                (pp/print-table
                 [:label :expected :actual :match?]
                 [{:label "URI"
                   :expected expected-uri-set
                   :actual (:uri request)
                   :match? (match? (expected-uri-set (:uri request)))}
                  {:label "Method"
                   :expected "GET/POST"
                   :actual (:request-method request)
                   :match? (match? (#{:get :post}
                                    (:request-method request)))}
                  {:label "Header: 'Content-Type' (POST)"
                   :expected ct-app-json
                   :actual (get-in request [:headers "content-type"])
                   :match? (match? (case (:request-method request)
                                     :post (= (get-in request
                                                      [:headers
                                                       "content-type"])
                                              ct-app-json)
                                     :get true
                                     true))}
                  {:label "Header: 'Accept'"
                   :expected (format "'%s' and/or '%s'"
                                     ct-app-json
                                     ct-text-sse)
                   :actual (get-in request [:headers "accept"])
                   :match? (let [a (get-in request [:headers "accept"])]
                             (match? (or
                                      (str/includes? a ct-text-sse)
                                      (str/includes? a ct-app-json))))}]))]
    (plain-response 400 (str "Invalid MCP/JSON-RPC request\n"
                             table))))
