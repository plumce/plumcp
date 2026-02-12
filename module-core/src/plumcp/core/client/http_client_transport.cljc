;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.http-client-transport
  "MCP client Streamable HTTP transport implementation"
  (:require
   [clojure.string :as str]
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.client.http-client-transport-auth :as hcta]
   [plumcp.core.protocol :as p]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


(defn make-streamable-http-transport
  "Make Streamable-HTTP transport as a p/IClientTransport instance."
  [http-client & {:keys [on-receive-message
                         start-get-stream?
                         auth-options
                         get-auth-tokens
                         on-other-response]
                  :or {on-receive-message #(-> "[Streamable HTTP Message]"
                                               (u/eprintln %))
                       start-get-stream? true
                       auth-options      {:auth-enabled? false}
                       get-auth-tokens   hcta/get-tokens
                       on-other-response #(-> "[Streamable HTTP Response]"
                                              (u/eprintln %))}}]
  (let [auth-enabled? (boolean (:auth-enabled? auth-options))
        tokens->hdrs  (fn [tokens]
                        {"Authorization" (str "Bearer "
                                              (:access_token tokens))})
        get-auth-hdrs (fn []
                        (if auth-enabled?
                          (if-let [tokens (p/read-tokens (:token-cache auth-options)
                                                         (:mcp-server auth-options))]
                            (tokens->hdrs tokens)
                            {})
                          {}))
        accept-header "text/event-stream, application/json"
        get-request  {:request-method :get
                      :headers {"Accept" accept-header}}
        post-request {:request-method :post
                      :headers {"Accept" accept-header
                                "Content-Type" "application/json"}}
        msg-receiver (volatile! on-receive-message)
        wrap-message (fn [jsonrpc-message headers-lower]
                       (if-let [session-id (->> sd/mcp-session-id-header-lower
                                                (get headers-lower))]
                         (assoc jsonrpc-message
                                cs/key-mcp-session-id session-id)
                         jsonrpc-message))
        wrap-reqhdrs (fn [session-context base-request]
                       (-> base-request
                           (update :headers merge
                                   (if-let [session-id (cs/key-mcp-session-id
                                                        session-context)]
                                     {sd/mcp-session-id-header session-id}
                                     {}))))
        wrap-headers (fn [headers request]
                       (update request :headers merge headers))
        wrap-reqbody (fn [jsonrpc-message post-request]
                       (->> cs/key-mcp-session-id
                            (dissoc jsonrpc-message)
                            u/json-write  ; encode message as JSON body
                            (assoc post-request :body)))
        receive-msg (fn [message-str headers-lower]
                      (as-> (u/json-parse message-str) $  ; decode JSON msg
                        (wrap-message $ headers-lower)
                        (u/invoke (deref msg-receiver) $)))
        get-thread  (volatile! nil)
        set-thread! (fn [] #?(:clj (vreset! get-thread
                                            (Thread/currentThread))))
        int-thread! (fn [] (when-let [thread (deref get-thread)]
                             (.interrupt ^java.lang.Thread thread)))
        on-response (fn [retry-401 response-or-promise]
                      (uab/let-await [response response-or-promise]
                        (let [status (:status response)
                              headers (:headers response)
                              headers-lower (update-keys headers
                                                         str/lower-case)]
                          (cond
                            ;;
                            ;; SSE body
                            ;;
                            (and (= 200 status)
                                 (= (get headers-lower "content-type")
                                    "text/event-stream"))
                            (-> (:on-sse response)
                                (u/invoke #(receive-msg % headers-lower)))
                            ;;
                            ;; JSON body
                            ;;
                            (and (= 200 status)
                                 (= (get headers-lower "content-type")
                                    "application/json"))
                            (-> (:on-msg response)
                                (u/invoke #(receive-msg % headers-lower)))
                            ;;
                            ;; Auth error
                            ;;
                            (and auth-enabled?
                                 (= 401 status)
                                 (string? (get headers-lower
                                               "www-authenticate")))
                            (if-let [sora-tokens (get-auth-tokens headers-lower
                                                                  auth-options)]
                              (uab/may-await [tokens sora-tokens]
                                (u/dprint "Retrying-401 with" tokens)
                                (retry-401 (tokens->hdrs tokens)))
                              (on-other-response response))
                            ;;
                            ;; Error, perhaps
                            ;;
                            :else
                            (on-other-response response)))))
        post-message (fn thisfn [message extra-headers]
                       (->> post-request
                            (wrap-reqhdrs message)
                            (wrap-headers extra-headers)
                            (wrap-reqbody message)
                            (p/http-call http-client)
                            (on-response (partial thisfn message))))
        fetch-stream (fn thisfn [success extra-headers]
                       (try
                         (set-thread!)
                         (->> get-request
                              (wrap-reqhdrs success)
                              (wrap-headers extra-headers)
                              (p/http-call http-client)
                              (on-response (partial thisfn success)))
                         #?(:clj (catch java.io.IOException _
                                   (u/eprintln "Got IOException")))
                         (finally
                           (u/eprintln "Exiting GET-stream"))))]
    (reify
      p/IClientTransport
      (client-transport-info [_] (merge (p/client-info http-client) {:id :http}))
      (start-client-transport [_ on-message] (vreset! msg-receiver
                                                      on-message))
      (stop-client-transport! [_ _force?] (do
                                            (u/eprintln "Stopping transport:---")
                                            (u/eprintln "Interrupting GET thread")
                                            (int-thread!)
                                            (u/eprintln "Calling HTTP DELETE")
                                            (->> {:request-method :delete
                                                  :headers (get-auth-hdrs)}
                                                 (p/http-call http-client))
                                            (u/eprintln "Closing HTTP Client")
                                            (p/stop! http-client)))
      (send-message-to-server [_ message] (post-message message
                                                        (get-auth-hdrs)))
      (upon-handshake-success [_ success] (when start-get-stream?
                                            (u/background
                                              (fetch-stream
                                               success
                                               (get-auth-hdrs))))))))
