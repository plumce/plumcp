;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.client.http-client-transport-auth
  "OAuth 2.1 integration support for MCP Streamable HTTP client transport.
   Supports only DCR-supporting external authorization servers for now."
  (:require
   #?(:clj [clojure.java.io :as io])
   #?(:cljs [plumcp.core.util-node :as un])
   #?(:cljs [plumcp.core.util-cljs :as us]
      :clj [plumcp.core.util-java :as uj])
   [clojure.string :as str]
   [plumcp.core.protocol :as p]
   [plumcp.core.support.http-server :as hs]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.http-auth :as uha]))


;; utility


(defn ^:async http-fetch-body-text!
  "Make HTTP call as per (Ring) request, returning the body text on HTTP
   200 or 201 response status. All other HTTP response statuses cause an
   exception to be thrown."
  [http-client request]
  (let [response (-> (p/http-call http-client request)
                     u/do-await)]
    (-> (:on-msg response)
        (u/invoke (if (#{200 201} (:status response))
                    identity
                    u/throw!))
        u/do-await)))


;; --- handle-failure: OAuth handshake ---

;; 1. get-protected-resource-metadata
;; 2. EITHER 2a OR 2b (if 2a succeeds then 2b not invoked)
;;   a. get-oauth-openid-configuration
;;   b. get-oauth-authorization-server
;; 3. register-client (DCR)
;; 4. start-callback-server
;; 5. open-browser-with-authorization-url
;; 6. receive authorization callback
;; 7. stop-callback-server
;; 8. get-access-token (+ refresh-token)
;;   a. Request had: token-request + code-verifier + resource


(defn prm-result->oic-request
  "Given protected-resource metadata, return a request map to fetch
   OpenID Configuration (for OpenID Connect Discovery 1.0)"
  [prm-result]
  {:uri (-> prm-result
            :authorization_servers
            uha/well-known-openid-configuration)
   :request-method :get})


(defn oic-result-str->register-client-request
  "Given a authorization-server metadata, return a request map to call
   the (open registration) Dynamic Client Registration (DCR) endpoint.
   See: https://datatracker.ietf.org/doc/html/rfc7591#section-3.1"
  [oic-result-str redirect-uris client-name]
  {:uri (get oic-result-str "registration_endpoint")
   :request-method :post
   :headers {"Content-Type" "application/json"}
   :body (-> oic-result-str
             (select-keys ["jwks_uri"])
             (assoc "redirect_uris" redirect-uris
                    "grant_types" ["authorization_code"]
                    "response_types" ["code"]
                    "token_endpoint_auth_method" "client_secret_basic"
                    "client_name" client-name)
             u/json-write)})


(defn prm-result->asm-request
  "Given protected-resource metadata, return a request map to fetch
   authorization-server metadata."
  [prm-result]
  {:uri (-> prm-result
            :authorization_servers
            uha/well-known-authorization-server)
   :request-method :get})


(defn asm-result-str->register-client-request
  "Given a authorization-server metadata, return a request map to call
   the (open registration) Dynamic Client Registration (DCR) endpoint.
   See: https://datatracker.ietf.org/doc/html/rfc7591#section-3.1"
  [asm-result-str redirect-uris client-name]
  {:uri (get asm-result-str "registration_endpoint")
   :request-method :post
   :headers {"Content-Type" "application/json"}
   :body (-> asm-result-str
             (select-keys ["jwks_uri"])
             (assoc "redirect_uris" redirect-uris
                    "grant_types" ["authorization_code"]
                    "response_types" ["code"]
                    "token_endpoint_auth_method" "client_secret_basic"
                    "client_name" client-name)
             u/json-write)})


(defn on-register-client
  "Given a response-body JSON string for Dynamic Client Registration (DCR)
   endpoint, return a map of client credentials.
   See: https://datatracker.ietf.org/doc/html/rfc7591#section-3.2.1"
  [json-str #_authorization-endpoint]
  #_{:uri authorization-endpoint}
  (u/json-parse-str json-str))


(defn ^:async after-make-authorization-url
  [{:keys [authorization-endpoint
           client-id
           callback-redirect-uri
           resource-uri]}
   f]
  (u/expected! authorization-endpoint string?
               "authorization-endpoint to be an API endpoint string")
  (u/expected! client-id string? "client-id to be a string")
  (u/expected! callback-redirect-uri string?
               "callback-redirect-uri to be a string")
  (u/expected! resource-uri string? "resource-uri to be a URL string")
  (let [base-url authorization-endpoint
        code-verifier (uha/make-code-verifier)
        state-csrf-token (u/uuid-v7)]
    (uha/with-code-verifier-challenge [code-challenge code-verifier]
      (let [url-params (array-map
                        "response_type"         "code"
                        "client_id"             client-id
                        "redirect_uri"          callback-redirect-uri
                        ; ScaleKit also accepts 'openid <access-scope>'
                        "scope"                 "openid"
                        "state"                 state-csrf-token
                        "code_challenge"        code-challenge
                        "code_challenge_method" "S256"  ; per MCP spec
                        "resource"              resource-uri)
            final-url (str base-url "?"
                           (u/url-encode url-params))]
        (f {:auth-url final-url
            :code-verifier code-verifier
            :state state-csrf-token})))))


(defn after-prep-authorization-url
  [context {:keys [mcp-server
                   callback-redirect-uri
                   ;; intermediate (from context)
                   authorization-endpoint
                   ;; optional
                   mcp-uri]
            :or {mcp-uri "/mcp"}}
   f]
  (-> {:authorization-endpoint authorization-endpoint
       :client-id (get-in context [:register-client-result
                                   "client_id"])
       :mcp-server mcp-server
       :callback-redirect-uri callback-redirect-uri
       :resource-uri (str mcp-server mcp-uri)}
      (after-make-authorization-url f)))


(defn get-resource-metadata
  "Given HTTP 401 response lowercase headers, return a Ring request to
   fetch Protected Resource Metadata (PRM) if available, nil otherwise."
  [headers-lower]
  (when-let [wwwa (get headers-lower "www-authenticate")]
    (let [rm-prefix "resource_metadata=\""]
      (when-let [rm-index (and (str/starts-with? wwwa "Bearer ")
                               (str/includes? wwwa "realm=\"OAuth\"")
                               (str/index-of wwwa rm-prefix))]
        (let [begin-index (+ ^long rm-index (count rm-prefix))
              until-index (str/index-of wwwa "\""
                                        begin-index)
              rm-endpoint (subs wwwa begin-index until-index)]
          {:uri rm-endpoint
           :request-method :get})))))


;; ----- Callback -----


(defn make-token-request
  [{:keys [token-endpoint
           authorization-code
           redirect-uri
           client-id
           code-verifier
           client-secret  ; omitted if nil
           ]}]
  (u/expected! token-endpoint string?
               ":token-endpoint to be a URL string")
  (u/expected! authorization-code string?
               ":authorization-code to be a string")
  (u/expected! redirect-uri string?
               ":redirect-uri to be a URI string")
  (u/expected! client-id string?
               ":client-id to be a string")
  (u/expected! code-verifier string?
               ":code-verifier to be a string")
  (when client-secret
    (u/expected! client-secret string?
                 ":client-secret to be a string"))
  (let [params (as-> ["grant_type" "authorization_code"
                      "code" authorization-code
                      "redirect_uri" redirect-uri
                      "client_id" client-id
                      "code_verifier" code-verifier] $
                 (concat $ (when (some? client-secret)
                             ["client_secret" client-secret]))
                 (apply array-map $))]
    {:uri token-endpoint
     :request-method :post
     :headers {"Content-Type" "application/x-www-form-urlencoded"}
     :body (-> params
               u/url-encode)}))


(defn make-callback-handler
  [on-success callback-uri state cleanup-atom]
  (fn callback-ring-handler [request]
    (let [method (:request-method request)
          uri (:uri request)
          cleanup (fn []
                    (u/background
                      {:delay-millis 20}  ; small cooling-off delay
                      (let [cleanup-tasks (deref cleanup-atom)
                            cleanup-count (count cleanup-tasks)]
                        (doseq [[index f] (->> (deref cleanup-atom)
                                               (interleave (iterate inc 1))
                                               (partition 2))]
                          (u/eprintln (format "[Cleanup %d/%d]"
                                              index cleanup-count))
                          (f)))))]
      (if (= :get method)
        (cond
          ;; callback URI
          (= callback-uri uri)
          (let [query-params (-> (:query-string request)
                                 (u/url-query-params)
                                 (update-keys keyword))]
            ;; match state
            (if (= state (:state query-params))
              (do
                ;; MCP spec 2025-06-18 doesn't suggest to verify
                ;; the code by client, so we pass it as received
                (on-success (:code query-params))
                {:status 202
                 :headers {}
                 :callback cleanup})
              {:status 400
               :headers {"Content-Type" "text/plain"}
               :body "CSRF Token (state) mismatch"
               :callback cleanup}))
          ;; favicon
          (= "/favicon.ico" uri)
          {:status 404
           :headers {"Content-Type" "text/plain"}
           :body "No favicon available"}
          ;; else
          :else
          {:status 400
           :headers {"Content-Type" "text/plain"}
           :body (str "Callback URI must be: "
                      callback-uri)})
        (-> {:status 405
             :headers {"Allow" "GET"
                       "Content-Type" "text/plain"}
             :callback cleanup}
            (u/assoc-some :body (when (not= :head method)
                                  "Only GET method is allowed")))))))


;; ----- main auth handler -----


(defn ^:async refresh-tokens
  "Given expired tokens, return refreshed tokens if refresh-attempt
   succeeded, nil otherwise."
  [tokens server client {:keys [http-client
                                mcp-server
                                on-error
                                token-cache]
                         :or {on-error (fn [error]
                                         (u/dprint "ERROR Refreshing tokens:"
                                                   error))}
                         :as auth-options}]
  (let [token-endpoint (:token_endpoint server)
        refresh-token  (:refresh_token tokens)
        refresh-params (array-map "grant_type" "refresh_token"
                                  "client_id" (:client_id client)
                                  "client_secret" (:client_secret client)
                                  "refresh_token" refresh-token)
        [http-body-data
         http-err-thrown] (u/catch!
                           (->> {:uri token-endpoint
                                 :request-method :post
                                 :headers {"Content-Type" "application/x-www-form-urlencoded"}
                                 :body (-> refresh-params
                                           u/url-encode)}
                                (http-fetch-body-text! http-client)
                                u/do-await
                                u/json-parse))]
    (if http-err-thrown
      (on-error http-err-thrown)
      (do
        (p/write-tokens! token-cache mcp-server http-body-data)
        http-body-data))))


(defn ^:async sub-make-auth-code-flow-params
  [{:keys [authorization-endpoint
           client-id
           callback-redirect-uri
           resource-uri]}]
  (u/expected! authorization-endpoint string?
               "authorization-endpoint to be an API endpoint string")
  (u/expected! client-id string? "client-id to be a string")
  (u/expected! callback-redirect-uri string?
               "callback-redirect-uri to be a string")
  (u/expected! resource-uri string? "resource-uri to be a URL string")
  (let [base-url authorization-endpoint
        code-verifier (uha/make-code-verifier)
        state-csrf-token (u/uuid-v7)]
    (uha/with-code-verifier-challenge [code-challenge code-verifier]
      (let [url-params (array-map
                        "response_type"         "code"
                        "client_id"             client-id
                        "redirect_uri"          callback-redirect-uri
                        ;; ScaleKit also accepts 'openid <access-scope>'
                        "scope"                 "openid"
                        "state"                 state-csrf-token
                        "code_challenge"        code-challenge
                        "code_challenge_method" "S256"  ; per MCP spec
                        "resource"              resource-uri)
            final-url (str base-url "?"
                           (u/url-encode url-params))]
        {:auth-url final-url
         :code-verifier code-verifier
         :state state-csrf-token}))))


(defn ^:async prm-result->asm-result-str
  "Given Protected Resource Metadata (PRM) result as data,
   1. Fetch Auhorization Server Metadata (ASM)
   2. Parse the ASM JSON result as data (with string keys)
   3. Return the parsed result"
  [prm-result {:keys [asm-request-middleware
                      http-client]
               :as auth-options}]
  (let [asm-request (-> prm-result
                        prm-result->asm-request
                        asm-request-middleware)]
    (-> http-client
        (http-fetch-body-text! asm-request)
        u/do-await
        u/json-parse-str)))


(defn ^:async prm-result->oic-result-str
  "Given Protected Resource Metadata (PRM) result as data,
   1. Fetch OpenID Configuration (OIC)
   2. Parse the OIC JSON result as data (with string keys)
   3. Return the parsed result"
  [prm-result {:keys [oic-request-middleware
                      http-client]
               :as auth-options}]
  (let [oic-request (-> prm-result
                        prm-result->oic-request
                        oic-request-middleware)]
    (-> http-client
        (http-fetch-body-text! oic-request)
        u/do-await
        u/json-parse-str)))


(defn ^:async prereg-cimd:prm-result->auth-code-flow-params
  "Common authorization flow for Pre-registered Client and CIMD."
  [prm-result asm-result-str {:keys [;; common
                                     token-cache
                                     mcp-server
                                     mcp-uri
                                     callback-redirect-uri
                                     ;; prereg specific
                                     client-id
                                     client-secret]
                              :as auth-options}]
  (let [asm-result-str (or asm-result-str
                           (-> prm-result
                               (prm-result->asm-result-str auth-options)
                               u/do-await))]
    (p/write-server! token-cache
                     mcp-server asm-result-str)
    (-> (u/keyword-map callback-redirect-uri
                       client-id)
        (assoc :authorization-endpoint (get asm-result-str
                                            "authorization_endpoint")
               :resource-uri (str mcp-server mcp-uri))
        sub-make-auth-code-flow-params
        u/do-await
        (assoc :token-endpoint (get asm-result-str
                                    "token_endpoint")
               :client-id client-id
               :client-secret client-secret)
        (select-keys [:client-id
                      :client-secret
                      :auth-url
                      :code-verifier
                      :state
                      :token-endpoint]))))


(defn asm-result-str->dcr-request
  "Given Authorization Server Metadata (ASM) data with string keys,
   prepare Dynamic Client Registration (DCR) request details."
  [asm-result-str {:keys [token-cache
                          mcp-server
                          redirect-uris
                          client-name]
                   :as auth-options}]
  (p/write-server! token-cache
                   mcp-server asm-result-str)
  {:authorization-endpoint (get asm-result-str
                                "authorization_endpoint")
   :token-endpoint (get asm-result-str "token_endpoint")
   :register-client-request (asm-result-str->register-client-request
                             asm-result-str
                             redirect-uris
                             client-name)})


(defn oic-result-str->dcr-request
  "Given OpenID Configuration (OIC) data with string keys, prepare
   Dynamic Client Registration (DCR) request details."
  [oic-result-str {:keys [token-cache
                          mcp-server
                          redirect-uris
                          client-name]
                   :as auth-options}]
  (p/write-server! token-cache
                   mcp-server oic-result-str)
  {:authorization-endpoint (get oic-result-str
                                "authorization_endpoint")
   :token-endpoint (get oic-result-str "token_endpoint")
   :register-client-request (oic-result-str->register-client-request
                             oic-result-str
                             redirect-uris
                             client-name)})


(defn ^:async dcr-request->auth-code-flow-params
  "Authorization flow for 'Dynamic Client Registration (DCR)'.
   Make DCR call, returning the following:
   {:client-id ...
    :client-secret ...
    :auth-url ...
    :code-verifier ...
    :state ...
    :token-endpoint ...}"
  [{:keys [authorization-endpoint
           token-endpoint
           register-client-request]
    :as dcr-request} {:keys [dcr-request-middleware
                             http-client
                             token-cache
                             mcp-server
                             mcp-uri
                             callback-redirect-uri]
                      :as auth-options}]
  (let [;; --- dynamically register client
        register-client-result (-> http-client
                                   (http-fetch-body-text!
                                    (-> register-client-request
                                        dcr-request-middleware))
                                   u/do-await
                                   u/json-parse-str
                                   (u/dotee #(p/write-client! token-cache
                                                              mcp-server %)))
        ;; --- make authorization URL for opening later in a browser
        {:as auth-code-flow-params
         :keys [client-id
                client-secret
                auth-url
                code-verifier
                state]} (-> (u/keyword-map authorization-endpoint
                                           callback-redirect-uri)
                            (assoc :client-id (get register-client-result
                                                   "client_id")
                                   :resource-uri (str mcp-server mcp-uri))
                            sub-make-auth-code-flow-params
                            u/do-await
                            (assoc :client-id (get register-client-result
                                                   "client_id")
                                   :client-secret (get register-client-result
                                                       "client_secret")))]
    ;; return a keyword map of the following
    (u/keyword-map client-id
                   client-secret
                   auth-url
                   code-verifier
                   state
                   token-endpoint)))


(defn ^:async prm-result->auth-code-flow-params
  "Given Protected Resource Metadata (PRM), arrive at the Authorization
   code flow params and return the following attributes:
   {:client-id ...
    :client-secret ...  ; only populated for DCR, else passed as is
    :auth-url ...
    :code-verifier ...
    :state ...
    :token-endpoint ...}
   Client registration is handled in the following order of priority:
   1. Preregistered Client:
      Both `client-id` and `client-secret` attributes are required.
   2. Client ID Metadata Documents (CMID):
      Client is assumed to be 'public' and metadata document is assumed
      to include `token_endpoint_auth_method=none` (JSON) implying that
      'client-secret' attribute won't be communicated in token request.
   3. Dynamic Client Registration (DCR):
      `client-secret` value is taken from client-registration response."
  [prm-result {:keys [client-id
                      client-secret]
               :as auth-options}]
  (let [!asm-result-str (volatile! nil)
        !oic-result-str (volatile! nil)]
    (cond
      ;;
      ;; Preregistration
      ;;
      (and (some? client-id)
           (some? client-secret))
      (-> prm-result
          (prereg-cimd:prm-result->auth-code-flow-params nil auth-options)
          u/do-await)
      ;;
      ;; Client ID Metadata Documents (CMID)
      ;;
      (and (string? client-id)
           (-> (str/lower-case client-id)
               (str/starts-with? "https://"))
           (try
             (-> prm-result
                 (prm-result->asm-result-str auth-options)
                 u/do-await
                 (u/dotee #(vreset! !asm-result-str %))
                 (get "client_id_metadata_document_supported")
                 true?)
             (catch #?(:cljs :default :clj Exception) _
               false)))
      (-> prm-result
          (prereg-cimd:prm-result->auth-code-flow-params @!asm-result-str
                                                         auth-options)
          u/do-await)
      ;;
      ;; Dynamic Client Registration (DCR) - Fallback
      ;; based on Authorization Server Metadata (ASM)
      ;;
      (try
        (-> prm-result
            (prm-result->asm-result-str auth-options)
            u/do-await
            (u/dotee #(vreset! !asm-result-str %))
            (get "registration_endpoint")
            string?)
        (catch #?(:cljs :default :clj Exception) _
          false))
      (-> (deref !asm-result-str)
          (asm-result-str->dcr-request auth-options)
          (dcr-request->auth-code-flow-params auth-options)
          u/do-await)
      ;;
      ;; Dynamic Client Registration (DCR) - Fallback
      ;; based on OpenID Configuration (OIC)
      ;;
      (try
        (-> prm-result
            (prm-result->oic-result-str auth-options)
            u/do-await
            (u/dotee #(vreset! !oic-result-str %))
            (get "registration_endpoint")
            string?)
        (catch #?(:cljs :default :clj Exception) _
          false))
      (-> (deref !oic-result-str)
          (oic-result-str->dcr-request auth-options)
          (dcr-request->auth-code-flow-params auth-options)
          u/do-await)
      ;;
      ;; Client registration is unavailable
      ;;
      :else
      (u/throw! "OAuth client registration is unavailable"))))


(defn ^:async handle-authz-flow
  "Handle the first part of auth-flow until starting authorization
   code-flow. Includes following steps for Dynamic Client Registration:
   Discovery-phase:               1. Protected Resource Metadata
                                  2a. OpenID Connect Discovery, or
                                  2b. Authorization Server Metadata
   Authorization phase (DCR):     3. Dynamic Client Registration
   Start Authorization code-flow: 4. Redirect to authorization-endpoint

   The step 4 is implemented by the (fn on-200 [json-data]) arg, which
   is supposed to:
   (a) ensure a running (or launched) web server to handle redirects
   (b) open the browser"
  [headers-lower {:keys [;; common args
                         http-client
                         on-error
                         ;; args for ASM
                         redirect-uris
                         client-name
                         ;;
                         mcp-server
                         callback-redirect-uri
                         callback-start-server
                         open-browser-auth-url
                         ;;
                         token-cache
                         ;; optional
                         mcp-uri
                         prm-request-middleware
                         oic-request-middleware
                         asm-request-middleware
                         dcr-request-middleware]
                  :or {mcp-uri "/mcp"
                       prm-request-middleware identity
                       oic-request-middleware identity
                       asm-request-middleware identity
                       dcr-request-middleware identity
                       on-error (fn [error]
                                  (u/dprint "ERROR Handling authorization flow:"
                                            error))}
                  :as options}]
  (if-let [prm-request (get-resource-metadata headers-lower)]
    (try
      (let [auth-options (-> {:mcp-uri "/mcp"
                              :oic-request-middleware identity
                              :asm-request-middleware identity
                              :dcr-request-middleware identity}
                             (merge options))
            ;; setup resource tracking for cleanup
            cleanup-atom (atom [])
            cleanup-stop (fn [stoppable description]
                           (u/expected! stoppable #(satisfies? p/IStoppable %)
                                        "p/IStoppable instance")
                           (u/eprintln "Adding stoppable" description)
                           (swap! cleanup-atom conj
                                  (fn []
                                    (u/eprintln "Stopping" description)
                                    (p/stop! stoppable))))
            ;; --- get Protected Resource metadata
            prm-result (->> prm-request
                            prm-request-middleware
                            (http-fetch-body-text! http-client)
                            u/do-await
                            u/json-parse)
            {:keys [client-id
                    client-secret
                    auth-url
                    code-verifier
                    state
                    token-endpoint]
             :as auth-code-flow-params} (-> prm-result
                                            (prm-result->auth-code-flow-params
                                             auth-options)
                                            u/do-await)
            ;; --- callback uri to start server at
            [_ callback-uri] (u/split-web-url callback-redirect-uri)]
        ;;
        ;; --- start callback server/endpoint
        ;;
        (uab/as-async [p-resolve p-reject]
          {}
          (-> (^:async fn [code]
                (let [token-request (-> (u/keyword-map token-endpoint
                                                       code-verifier
                                                       client-id
                                                       client-secret)
                                        (assoc
                                         :authorization-code code
                                         :redirect-uri callback-redirect-uri)
                                        make-token-request)
                      token-result (-> (http-fetch-body-text! http-client
                                                              token-request)
                                       u/do-await
                                       u/json-parse)]
                  (p/write-tokens! token-cache
                                   mcp-server token-result)
                  (p-resolve token-result)
                  token-result))
              (make-callback-handler callback-uri state
                                     cleanup-atom)
              callback-start-server
              (u/dotee cleanup-stop "Callback server"))
          ;;
          ;; --- open the authorization code-flow URL in browser
          ;;
          (u/eprintln "auth-url" auth-url)
          (-> auth-url
              open-browser-auth-url
              (u/dotee cleanup-stop
                       "Callback browser")))
        ;;
        )
      (catch #?(:cljs :default :clj Exception) ex
        (u/print-stack-trace ex)
        (on-error (ex-message ex))))
    (on-error "Cannot get resource-metadata from headers.")))


(defn ^:async get-tokens
  "Return auth tokens if found, refreshed or obtained successfully, nil
   otherwise."
  [headers-lower {:keys [mcp-server
                         token-expired?
                         token-cache]
                  :or {token-expired? (constantly true)}
                  :as auth-options}]
  (if-some [tokens (p/read-tokens token-cache mcp-server)]
    (if (token-expired? tokens)
      (refresh-tokens tokens
                      (p/read-server token-cache mcp-server)
                      (p/read-client token-cache mcp-server)
                      auth-options)
      tokens)
    (handle-authz-flow headers-lower auth-options)))


;; ----- token cache -----


(def content-type-tokens "tokens")
(def content-type-server "server")
(def content-type-client "client")


(defn ->filename
  "Turn given mcp-server/content-type args into a unique JSON filename."
  [mcp-server content-type]
  (-> mcp-server  ; MCP-server (base) URI
      (str/replace "://" "_")
      (str/replace ":" "_")
      (str/replace "/" "_")
      (str "_" content-type ".json")))


(def in-memory-token-cache
  "In-memory token cache for MCP client auth."
  (let [cache (atom {})
        delete! (fn [k] (swap! cache dissoc k))
        read-json (fn [k] (some-> (get (deref cache) k)
                                  u/json-parse))
        save-json (fn [k data] (->> (u/json-write data)
                                    (swap! cache assoc k)))]
    (reify p/ITokenCache
      (clear-cache! [_ mcp-server] (->> [content-type-tokens
                                         content-type-server
                                         content-type-client]
                                        (map #(->filename mcp-server %))
                                        (run! delete!)))
      (read-tokens [_ mcp-server] (->> content-type-tokens
                                       (->filename mcp-server)
                                       read-json))
      (read-server [_ mcp-server] (->> content-type-server
                                       (->filename mcp-server)
                                       read-json))
      (read-client [_ mcp-server] (->> content-type-client
                                       (->filename mcp-server)
                                       read-json))
      (write-tokens! [_ mcp-server
                      data] (-> mcp-server
                                (->filename content-type-tokens)
                                (save-json data)))
      (write-server! [_ mcp-server
                      data] (-> mcp-server
                                (->filename content-type-server)
                                (save-json data)))
      (write-client! [_ mcp-server
                      data] (-> mcp-server
                                (->filename content-type-client)
                                (save-json data))))))


(def local-token-cache
  "Local-storage (filesystem on CLJ/Node.js or localStorage in browser)
   token cache for MCP client auth."
  (let [bad-env-err (str "Unsupported CLJS env "
                         "(neither Node.js nor web browser)"
                         " - override :token-cache in :auth-options")
        read-json-file (fn read-json-file
                         [mcp-server content-type file-reader]
                         (let [json-filename (->filename mcp-server
                                                         content-type)]
                           (when #?(:cljs (cond
                                            us/env-node-js?
                                            (un/file-exists? json-filename)
                                            us/env-browser?
                                            (some? (.getItem js/localStorage
                                                             json-filename))
                                            :else
                                            (u/throw! bad-env-err))
                                    :clj (uj/file-exists? json-filename))
                             (-> (file-reader json-filename)
                                 u/json-parse))))
        write-json-file (fn write-json-file
                          [mcp-server content-type data file-writer]
                          (let [json-filename (->filename mcp-server
                                                          content-type)
                                json-str (u/json-write data)]
                            (file-writer json-filename json-str)))
        file-reader #?(:cljs (fn [filename]
                               (cond
                                 us/env-node-js?
                                 (un/slurp-file filename)
                                 us/env-browser?
                                 (.getItem js/localStorage filename)
                                 :else
                                 (u/throw! bad-env-err)))
                       :clj slurp)
        file-writer #?(:cljs (fn [filename content]
                               (cond
                                 us/env-node-js?
                                 (un/spit filename content)
                                 us/env-browser?
                                 (.setItem js/localStorage
                                           filename content)
                                 :else
                                 (u/throw! bad-env-err)))
                       :clj spit)
        file-remover #?(:cljs (fn [filename]
                                (cond
                                  us/env-node-js?
                                  (un/delete-file filename)
                                  us/env-browser?
                                  (.removeItem js/localStorage filename)
                                  :else
                                  (u/throw! bad-env-err)))
                        :clj #(io/delete-file % true))]
    (reify p/ITokenCache
      (clear-cache! [_ mcp-server] (->> [content-type-tokens
                                         content-type-server
                                         content-type-client]
                                        (map #(->filename mcp-server %))
                                        (run! file-remover)))
      (read-tokens [_ mcp-server] (read-json-file mcp-server
                                                  content-type-tokens
                                                  file-reader))
      (read-server [_ mcp-server] (read-json-file mcp-server
                                                  content-type-server
                                                  file-reader))
      (read-client [_ mcp-server] (read-json-file mcp-server
                                                  content-type-client
                                                  file-reader))
      (write-tokens! [_ mcp-server
                      data] (write-json-file mcp-server
                                             content-type-tokens
                                             data file-writer))
      (write-server! [_ mcp-server
                      data] (write-json-file mcp-server
                                             content-type-server
                                             data file-writer))
      (write-client! [_ mcp-server
                      data] (write-json-file mcp-server
                                             content-type-client
                                             data file-writer)))))


;; ----- options -----


#?(:cljs (defn node-run-server
           [handler options]
           (if us/env-node-js?
             (hs/run-http-server handler options)
             (u/throw! "You must override :callback-start-server"))))


(defn browse-url
  "Open browser with specified URL and return p/IStoppable instance to
   close the browser process."
  [url]
  (u/eprintln "\nOpening URL in browser:" url)
  #?(:cljs (cond
             us/env-node-js? (let [^ChildProcess subproc (un/browse-url url)]
                               (reify p/IStoppable
                                 (stop! [_] (un/kill-process-tree subproc))))
             us/env-browser? (let [window-proxy (.open js/window url)]
                               (reify p/IStoppable
                                 (stop! [_] (when (some? window-proxy)
                                              (.close window-proxy)))))
             us/env-sworker? (let [wc-prom (.openWindow js/clients url)]
                               (reify p/IStoppable
                                 (stop! [_] (u/do-async
                                              (let [wc (-> wc-prom
                                                           u/do-await)]
                                                (-> "Cannot close window"
                                                    u/eprintln)))))))
     :clj (let [^Process subproc (uj/browse-url url)]
            (reify p/IStoppable
              (stop! [_] (uj/kill-process-tree subproc))))))


(defn make-client-auth-options
  "Create auth-options map to create the MCP Ring handler with OAuth
   enabled. KW-args are described below:
   :http-client             IHttpClient (protocol) instance
   :on-error                (fn [error])
   :redirect-uris           vector of redirect URIs
   :info                    Client-Info, used for client-name
   :client-id               Preregistered Client ID (omitted for DCR)
   :client-secret           Preregistered Client secret (omitted for DCR/CIMD)
   :client-name             Client name string (optional if :info present)
   :mcp-server              Base URL string for the MCP server
   :callback-redirect-uri   Redirectto this URI after Auth success
   :callback-start-server   (fn [handler])->Stoppable
   :open-browser-auth-url   (fn [url])->void
   "
  [auth-options]
  (let [{:keys [http-client
                on-error
                redirect-uris
                client-id     ; for preregistered client, ClientID Metadata Doc
                client-secret ; for preregistered client only
                client-name
                mcp-server
                callback-redirect-uri
                callback-start-server
                open-browser-auth-url
                token-cache]
         :or {client-name (when (contains? auth-options :info)
                            (-> (get auth-options :info)
                                :name))
              callback-start-server #?(:clj #(hs/run-http-server % {:port 6277})
                                       :cljs #(node-run-server % {:port 6277}))
              open-browser-auth-url browse-url
              token-cache in-memory-token-cache}} auth-options]
    (u/expected! http-client #(satisfies? p/IHttpClient %)
                 ":http-client to be an IHttpClient instance")
    (u/expected! on-error fn?
                 ":on-error to be a function (fn [error])")
    (u/expected! redirect-uris (every-pred seqable? seq
                                           #(every? string? %))
                 ":redirect-uris to be one-or-more redirect URI string")
    (u/expected! client-name (every-pred string? seq)
                 ":client-name to be a non-empty string")
    (u/expected! mcp-server (every-pred string? seq)
                 ":mcp-server to be the MCP-server URL")
    (u/expected! callback-redirect-uri (every-pred string? seq)
                 ":callback-redirect-uri to be a URI string")
    (u/expected! callback-start-server fn?
                 ":callback-start-server to be a function")
    (u/expected! open-browser-auth-url fn?
                 ":open-browser-auth-url to be a function")
    (u/expected! token-cache #(satisfies? p/ITokenCache %)
                 ":token-cache to be an ITokenCache instance")
    {:auth-enabled?  true
     :http-client    http-client
     :redirect-uris  redirect-uris
     :client-id      client-id
     :client-secret  client-secret
     :client-name    client-name
     :mcp-server     mcp-server
     :callback-redirect-uri callback-redirect-uri
     :callback-start-server callback-start-server
     :open-browser-auth-url open-browser-auth-url
     :token-cache token-cache}))
