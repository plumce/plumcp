(ns plumcp.core.main.client
  (:require
   [plumcp.core.client.client-support :as cs]
   [plumcp.core.client.http-client-transport :as hct]
   [plumcp.core.client.http-client-transport-auth :as hcta]
   [plumcp.core.dev.api :as dev]
   [plumcp.core.support.http-client :as hc]
   [plumcp.core.support.http-server :as hs]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u]))


(defn make-http-transport
  [endpoint-uri & {:keys [traffic-logger
                          auth-options]
                   :or {traffic-logger stl/compact-client-traffic-logger
                        auth-options {}}
                   :as options}]
  (let [http-client (->> {:traffic-logger traffic-logger}
                         (hc/make-http-client endpoint-uri))
        auth-options (-> {:http-client http-client
                          :on-error    u/eprintln
                          :redirect-uris ["http://localhost:6277/"]
                          :client-name "PluMCP Test client"
                          :mcp-server "http://localhost:3000"
                          :callback-redirect-uri "http://localhost:6277/"
                          :callback-start-server #(hs/run-http-server % {:port 6277})
                          ;:token-cache mt-http-auth/local-token-cache
                          ;;
                          }
                         (merge auth-options)
                         hcta/make-client-auth-options)]
    (->> {:auth-options auth-options}
         (hct/make-streamable-http-transport http-client))))


(def client-options (-> {}
                        (merge dev/client-options)
                        (cs/make-client-options)))
