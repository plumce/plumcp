(ns plumcp.core.main.main-http-server
  "The main entrypoint for Streamin-HTTP transport based MCP server for
   both JVM and Node.js."
  (:require
   [plumcp.core.api.mcp-server :as ms]
   [plumcp.core.main.server :as server]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


;; (defn auth-auth0
;;   "Sample auth options for Auth0
;;    Ref: https://aembit.io/blog/configuring-an-mcp-server-with-auth0-as-the-authorization-server"
;;   []
;;   (let [server-runtime (:runtime server/server-options)]
;;     (->> {:jwt->claims au/validate-jwt
;;           :authorization-servers ["https://dev-jxrwwoyejipmivtp.us.auth0.com/"]
;;           :mcp-server "http://localhost:3000"}
;;          (hra/make-ring-auth-options server-runtime))))


;; (defn auth-scalekit
;;   "Sample auth options for ScaleKit
;;    See: https://www.scalekit.com/blog/implement-oauth-for-mcp-servers
;;         and ScaleKit docs"
;;   []
;;   (let [server-runtime (:runtime server/server-options)]
;;     (->> {:jwt->claims au/validate-jwt
;;           :authorization-servers ["https://self.scalekit.dev/resources/res_90791782157125635"]
;;           :mcp-server "http://localhost:3000"}
;;          (hra/make-ring-auth-options server-runtime))))


;; (defn auth-workos
;;   "Sample auth options for WorkOS
;;    See: WorkOS docs"
;;   []
;;   (let [server-runtime (:runtime server/server-options)]
;;     (->> {:jwt->claims au/validate-jwt
;;           :authorization-servers ["https://soothing-postcard-01-staging.authkit.app"]
;;           :mcp-server "http://localhost:3000"}
;;          (hra/make-ring-auth-options server-runtime))))


(defn #?(:clj -main
         :cljs main) [& _]
  (u/eprintln "Starting Streamable-HTTP server")
  (try (uab/may-await
         [_ (-> {:auth-options nil #_(auth-auth0) #_(auth-scalekit) #_(auth-workos)
                 :transport :http}
                (merge server/server-options)
                ms/run-mcp-server)]
         (u/eprintln "Streamable-HTTP server started"))
       (catch #?(:clj Exception
                 :cljs js/Error) e
         (u/print-stack-trace e))))
