;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.http-ring-auth
  "Auth/OAuth helper for Streamable HTTP Ring server transport."
  (:require
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.server.http-ring-transport :as hrt]
   [plumcp.core.util :as u :refer [#?(:cljs slurp)]]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.util.http-auth :as uha]
   [plumcp.core.util.key-lookup :as kl]))


(defn handler-for:static-json
  [json-string]
  (fn [_]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body json-string}))


(defn handler-for:dynamic-json
  [body-str-fn]
  (fn [_]
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body (body-str-fn)}))


(defn handler-for:oauth-protected-resource
  "Return a Ring handler `(fn [request])->response` that returns the
   `Protected Resource Metadata` JSON.
   See: https://datatracker.ietf.org/doc/html/rfc9728"
  [{:keys [runtime
           authorization-servers
           mcp-server
           mcp-uri
           ;; optional
           mcp-server-name
           mcp-docs-uri
           scopes-supported
           jwks-uri]
    :or {scopes-supported []}}]
  (u/expected! runtime some? "runtime to be present")
  (u/expected! authorization-servers u/non-empty-vector?
               "authorization-servers to be a non-empty vector of URLs")
  (u/expected! mcp-server u/non-empty-string?
               "mcp-server to be a base URL, e.g. 'http://localhost:3000'")
  (u/expected! mcp-uri u/non-empty-string?
               "mcp-uri to be a URI string, e.g. '/mcp'")
  (let [mcp-server (u/stripr mcp-server "/")]
    (-> {"resource" (str mcp-server
                         mcp-uri)
         "authorization_servers" authorization-servers
         "bearer_methods_supported" ["header"]
         "scopes_supported" scopes-supported}
        (u/assoc-some "resource_name" mcp-server-name
                      "resource_documentation" (when mcp-docs-uri
                                                 (str mcp-server
                                                      mcp-docs-uri))
                      "jwks_uri" jwks-uri)
        u/json-write
        handler-for:static-json)))


(defn handler-for:oauth-authorization-server
  "Return a Ring handler `(fn [request])->response` that returns the
   `Authorization Server Metadata` JSON. This is a proxy-handler for
   actual 'Authorization Server Metadata' endpoint.
   See: https://datatracker.ietf.org/doc/html/rfc8414"
  [auth-uri fetch-from-uri auth-cache-millis]
  (let [auth-fn (-> #(fetch-from-uri auth-uri)
                    (u/fcached auth-cache-millis))]
    (handler-for:dynamic-json auth-fn)))


(defn make-token->claims
  "Given JWKs source (URI-string or no-arg fn) and a jwt-validator fn
   return a token validating function"
  [fetch-jwks-string jwt->claims]
  (fn [jwt]
    (uab/may-await [jwks (fetch-jwks-string)]
      (jwt->claims jwks jwt))))


(defn ^{:see [hrt/wrap-oauth
              hrt/wrap-route-match]} make-ring-auth-options
  "Create auth-options map to create the MCP Ring handler with OAuth
   enabled. Returns js/Promise in CLJS. KW-arg are described below:
   For wrap-oauth middleware:
   --------------------
   :jwt->claims         (fn [jwks-json-str jwt])->claims-map-or-nil to
                        validate and unpack JWT as claims, auto-detected
                        if the 'auth' module is in classpath
   --Optional--
   :jwks-uri            URI to fetch fetch JWKS as a JSON-string from
   :fetch-from-uri      (fn [uri])->body-text to fetch from JWKS URI
   :jwks-cache-millis   (default 1h) JWKS cache duration
   :protected-resource? (fn [request])->bool to find protected resources,
                        default: always returns true
   :claims->error       (fn [claims request])->error-msg-or-nil to check
                        authorization, default: always returns nil
   :resource-metadata   Resource metadata URL string, default: derived
                        from :mcp-server option
   (Well-known) Handler KW-args:
   ----------------------
   :authorization-servers Vector of authorization server URLs
   :mcp-server            Base URL for the MCP server
   --Optional--
   :auth-cache-millis     (default 1h) Authorization info cache duration
   :mcp-uri               URI string for the MCP server, default '/mcp'
   :mcp-server-name       Name for the MCP server
   :mcp-docs-uri          URI string for the MCP server docs
   :scopes-supported      Scopes upported for the resources
   See:
   ----
   plumcp.core.server.http-ring-transport/wrap-oauth
   plumcp.core.server.http-ring-transport/wrap-route-match"
  [runtime
   {:keys [;; --- wrap-oauth middleware ---
           jwt->claims
           ;; optional
           jwks-uri
           fetch-from-uri
           protected-resource?
           jwks-cache-millis
           claims->error
           resource-metadata
           ;; --- wrap-route-match (well-known routes) ---
           authorization-servers
           mcp-server
           ;; optional
           auth-cache-millis
           mcp-uri
           mcp-server-name
           mcp-docs-uri
           scopes-supported
           ; jwks-uri ; optional here too, but declared already above
           ]
    :or {fetch-from-uri slurp
         jwks-cache-millis (* 60 60 1000)
         auth-cache-millis (* 60 60 1000)
         mcp-uri "/mcp"
         mcp-server-name (-> (kl/?get runtime rt/?server-info)
                             :name)}
    :as auth-options}]
  (u/expected! jwt->claims fn? "jwt->claims to be a (fn [jwks-str jwt-str])")
  (u/expected! authorization-servers u/non-empty-vector?
               "authorization-servers to be a non-empty vector of URLs")
  (u/expected! mcp-server u/non-empty-string?
               "mcp-server to be a base URL string")
  (let [resource-metadata (or resource-metadata
                              (str mcp-server
                                   sd/uri-oauth-protected-resource))
        auth-uri (-> authorization-servers
                     uha/well-known-authorization-server)]
    (uab/may-await [jwks-uri (or jwks-uri
                                 (uab/may-await
                                   [json-string (fetch-from-uri auth-uri)]
                                   (-> json-string
                                       u/json-parse
                                       :jwks_uri)))]
      (->> "jwks-uri to be specified or auto-discovered as a JWKS URL string"
           (u/expected! jwks-uri u/non-empty-string?))
      (let [hpr-inst (-> {:runtime               runtime
                          :authorization-servers authorization-servers
                          :mcp-server            mcp-server
                          :mcp-uri               mcp-uri}
                         (u/assoc-some :mcp-server-name mcp-server-name
                                       :mcp-docs-uri mcp-docs-uri
                                       :scopes-supported scopes-supported
                                       :jwks-uri jwks-uri)
                         (handler-for:oauth-protected-resource))
            has-inst (handler-for:oauth-authorization-server auth-uri
                                                             fetch-from-uri
                                                             auth-cache-millis)]
        (-> {;; --- for wrap-oauth middleware ---
             :auth-enabled?     true
             :token->claims     (-> #(fetch-from-uri jwks-uri)
                                    (u/fcached jwks-cache-millis)
                                    (make-token->claims jwt->claims))
             :resource-metadata resource-metadata}
            (u/assoc-some :protected-resource? protected-resource?
                          :claims->error claims->error)
            ;; --- for wrap-routes middleware ---
            (assoc :well-known-routes {;; protected resource metadata
                                       sd/uri-oauth-protected-resource
                                       hpr-inst
                                       ;; protected resource metadata at /mcp
                                       (-> sd/uri-oauth-protected-resource
                                           (str mcp-uri))
                                       hpr-inst
                                       ;; authorization server
                                       sd/uri-oauth-authorization-server
                                       has-inst}))))))
