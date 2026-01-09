(ns plumcp.core.main.server
  (:require
   [plumcp.core.apps.everything :as ev]
   [plumcp.core.apps.greet :as greet]
   [plumcp.core.apps.weather :as we]
   [plumcp.core.dev.api :as dev]
   [plumcp.core.server.server-support :as ss]))


(def everything-primitives
  (do
    #_{:clj-kondo/ignore [:unused-value]}
    ;; either
    {:callbacks ev/callbacks
     :prompts ev/prompts
     :resources ev/resources
     :resource-templates ev/resource-templates
     :tools ev/tools}
    ;; or
    (ev/make-mcp-primitives dev/server-options)))


(def greet-primitives
  (do
    #_{:clj-kondo/ignore [:unused-value]}
    ;; either
    {:prompts greet/prompts
     :resources greet/resources
     :resource-templates greet/resource-templates
     :tools greet/tools}
    ;; or
    greet/mcp-primitives))


(def weather-primitives
  (do
    #_{:clj-kondo/ignore [:unused-value]}
    ;; either
    {:prompts []
     :resources []
     :resource-templates []
     :tools we/tools}
    ;; or
    we/mcp-primitives))


(def server-options (-> {:primitives
                         #_greet-primitives
                         #_weather-primitives
                         everything-primitives}
                        (merge dev/server-options)
                        ss/make-server-options))
