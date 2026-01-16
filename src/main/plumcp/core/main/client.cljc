;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


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
