(ns plumcp.core.apps.everything
  (:require
   [clojure.string :as str]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]
   [plumcp.core.dev.schema-malli :as sm]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.schema.json-rpc :as jr]
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


;; --- utility ---


(defn make-promise [] #?(:cljs (let [holder (atom nil)
                                     promise (js/Promise.
                                              (fn [return reject]
                                                (reset! holder return)))]
                                 [#(deref holder) promise])
                         :clj (let [p (promise)]
                                [(constantly p) p])))


(defn bear-promise [[get-ret _] v] #?(:cljs (-> (get-ret)
                                                (u/invoke v))
                                      :clj (-> (get-ret)
                                               (u/invoke v)
                                               deref)))


(defn upon-promise [[_ p] f] #?(:cljs (-> p
                                          (.then f))
                                :clj (do
                                       (while (not (realized? p))
                                         (Thread/onSpinWait))
                                       (f (deref p)))))


(defn after-millis
  "Execute fn `(f)` after specified milliseconds."
  [^long millis f]
  #?(:cljs (js/setTimeout f millis)
     :clj (do (Thread/sleep millis)
              (f))))


;; --- tools ---


(defn make-text-call-tool-result
  [text]
  (-> text
      eg/make-text-content
      vector
      eg/make-call-tool-result))


(defn ^{:mcp-name "echo"
        :mcp-type :tool} tool-echo
  "Echoes the message back"
  [{:keys [^{:doc "Message to echo"
             :type "string"} message]}]
  (make-text-call-tool-result message))


(defn ^{:mcp-name "add"
        :mcp-type :tool} tool-add
  "Add two numbers"
  [{:keys [^{:doc "A number" :type "number"} ^long a
           ^{:doc "A number" :type "number"} ^long b]}]
  (-> "The sum of %d and %d is %d"
      (format a b (+ a b))
      make-text-call-tool-result))


(defn ^{:mcp-name "longRunningOperation"
        :mcp-type :tool} tool-long-running-operation
  "Demonstrates a long running operation with progress updates"
  [{:keys [^{:doc "Duration of the operation in seconds"
             :type "number"} ^long duration
           ^{:doc "Number of steps in the operation"
             :type "number"} ^long steps]
    :as args}]
  (let [^double
        step-duration (/ duration steps)
        params-meta (rt/?request-params-meta args)
        progress-token (:progressToken params-meta)
        exec-steps (fn thisfn [^long step-index return]
                     (if (<= step-index steps)  ; index is 1-based
                       (after-millis
                        (* 1000 step-duration)
                        (fn []
                          (as-> progress-token $
                            (eg/make-progress-notification $ step-index {:total steps})
                            (rs/send-notification-to-client args $))
                          (thisfn (inc step-index) return)))
                       (return
                        (-> "Long running operation completed. Duration: %d seconds, Steps: %d."
                            (format duration steps)
                            make-text-call-tool-result))))]
    (uab/as-async [return reject]
      (exec-steps 1 return))))


(defn ^{:mcp-name "printEnv"
        :mcp-type :tool} tool-print-env
  "Prints all environment variables, helpful for debugging MCP server
   configuration."
  [{:keys []
    :as args}]
  (-> #?(:cljs (->> (js/Object.entries js/process.env)
                    js->clj
                    (into {}))
         :clj (System/getenv))
      u/json-write
      make-text-call-tool-result))


(defn make-sampling-request
  [context uri max-tokens]
  (let [sampling-message (eg/make-sampling-message
                          sd/role-user
                          (eg/make-text-content (format "Resource %s context: %s"
                                                        uri
                                                        context)))]
    (eg/make-create-message-request [sampling-message]
                                    max-tokens
                                    {:system-prompt "You are a helpful test server"
                                     :temperature 0.7
                                     :include-context "thisServer"})))


(defn build-sample-llm-response
  [result]
  (-> (str "LLM sampling result: "
           (get-in result [:result :content :text]))
      (eg/make-text-content)))


(defn ^{:mcp-name "sample-llm-callback"
        :mcp-type :callback} sample-llm-callback
  [{:as sampling-result}]
  (let [request-context (-> sampling-result
                            rt/?request-context)
        promise-deliver (fn [v]
                          (-> (:promise-pair request-context)
                              (bear-promise v)))
        request-id (-> request-context
                       rt/?request-id)]
    (-> (str "LLM sampling result: "
             (get-in sampling-result
                     [:content :text]))
        make-text-call-tool-result
        jr/jsonrpc-success
        (jr/add-jsonrpc-id request-id)
        (promise-deliver))))


(defn ^{:mcp-name "sampleLLM"
        :mcp-type :tool} tool-sample-llm
  "Samples from an LLM using MCP's sampling feature"
  [{:keys [^{:doc "The prompt to send to the LLM"
             :type "string"} prompt
           ^{:doc "Maximum number of tokens to generate"
             :type "number"
             :name "maxTokens"
             :required? false} max-tokens]
    :or {max-tokens 100}
    :as args}]
  (let [request (make-sampling-request prompt "sampleLLM" max-tokens)
        waiting (make-promise)
        context (-> {:promise-pair waiting ; not JSON-serializable, needs sticky session
                     :callback-name "sample-llm-callback"}
                    (rt/?request-id (rt/?request-id args)))]
    (rs/send-request-to-client args request context)
    ;; as of MCP 2025-06-18 only synchronous tool-calls are allowed
    ;; so we await execution to get over
    (upon-promise waiting
                  (fn [v]
                    (:result v)))))


(def mcp-tiny-image
  "iVBORw0KGgoAAAANSUhEUgAAABQAAAAUCAYAAACNiR0NAAAKsGlDQ1BJQ0MgUHJvZmlsZQAASImVlwdUU+kSgOfe9JDQEiIgJfQmSCeAlBBaAAXpYCMkAUKJMRBU7MriClZURLCs6KqIgo0idizYFsWC3QVZBNR1sWDDlXeBQ9jdd9575805c+a7c+efmf+e/z9nLgCdKZDJMlF1gCxpjjwyyI8dn5DIJvUABRiY0kBdIMyWcSMiwgCTUft3+dgGyJC9YzuU69/f/1fREImzhQBIBMbJomxhFsbHMe0TyuQ5ALg9mN9kbo5siK9gzJRjDWL8ZIhTR7hviJOHGY8fjomO5GGsDUCmCQTyVACaKeZn5wpTsTw0f4ztpSKJFGPsGbyzsmaLMMbqgiUWI8N4KD8n+S95Uv+WM1mZUyBIVfLIXoaF7C/JlmUK5v+fn+N/S1amYrSGOaa0NHlwJGaxvpAHGbNDlSxNnhI+yhLRcPwwpymCY0ZZmM1LHGWRwD9UuTZzStgop0gC+co8OfzoURZnB0SNsnx2pLJWipzHHWWBfKyuIiNG6U8T85X589Ki40Y5VxI7ZZSzM6JCx2J4Sr9cEansXywN8hurG6jce1b2X/Yr4SvX5qRFByv3LhjrXyzljuXMjlf2JhL7B4zFxCjjZTl+ylqyzAhlvDgzSOnPzo1Srs3BDuTY2gjlN0wXhESMMoRBELAhBjIhB+QggECQgBTEOeJ5Q2cUeLNl8+WS1LQcNhe7ZWI2Xyq0m8B2tHd0Bhi6syNH4j1r+C4irGtjvhWVAF4nBgcHT475Qm4BHEkCoNaO+SxnAKh3A1w5JVTIc0d8Q9cJCEAFNWCCDhiACViCLTiCK3iCLwRACIRDNCTATBBCGmRhnc+FhbAMCqAI1sNmKIOdsBv2wyE4CvVwCs7DZbgOt+AePIZ26IJX0AcfYQBBEBJCRxiIDmKImCE2iCPCQbyRACQMiUQSkCQkFZEiCmQhsgIpQoqRMmQXUokcQU4g55GrSCvyEOlAepF3yFcUh9JQJqqPmqMTUQ7KRUPRaHQGmorOQfPQfHQtWopWoAfROvQ8eh29h7ajr9B+HOBUcCycEc4Wx8HxcOG4RFwKTo5bjCvEleAqcNW4Rlwz7g6uHfca9wVPxDPwbLwt3hMfjI/BC/Fz8Ivxq/Fl+P34OvxF/B18B74P/51AJ+gRbAgeBD4hnpBKmEsoIJQQ9hJqCZcI9whdhI9EIpFFtCC6EYOJCcR04gLiauJ2Yg3xHLGV2EnsJ5FIOiQbkhcpnCQg5ZAKSFtJB0lnSbdJXaTPZBWyIdmRHEhOJEvJy8kl5APkM+Tb5G7yAEWdYkbxoIRTRJT5lHWUPZRGyk1KF2WAqkG1oHpRo6np1GXUUmo19RL1CfW9ioqKsYq7ylQVicpSlVKVwypXVDpUvtA0adY0Hm06TUFbS9tHO0d7SHtPp9PN6b70RHoOfS29kn6B/oz+WZWhaqfKVxWpLlEtV61Tva36Ro2iZqbGVZuplqdWonZM7abaa3WKurk6T12gvli9XP2E+n31fg2GhoNGuEaWxmqNAxpXNXo0SZrmmgGaIs18zd2aFzQ7GTiGCYPHEDJWMPYwLjG6mESmBZPPTGcWMQ8xW5h9WppazlqxWvO0yrVOa7WzcCxzFp+VyVrHOspqY30dpz+OO048btW46nG3x33SHq/tqy3WLtSu0b6n/VWHrROgk6GzQade56kuXtdad6ruXN0dupd0X49njvccLxxfOP7o+Ed6qJ61XqTeAr3dejf0+vUN9IP0Zfpb9S/ovzZgGfgapBtsMjhj0GvIMPQ2lBhuMjxr+JKtxeayM9ml7IvsPiM9o2AjhdEuoxajAWML4xjj5cY1xk9NqCYckxSTTSZNJn2mhqaTTReaVpk+MqOYcczSzLaYNZt9MrcwjzNfaV5v3mOhbcG3yLOosnhiSbf0sZxjWWF514poxbHKsNpudcsatXaxTrMut75pg9q42khsttu0TiBMcJ8gnVAx4b4tzZZrm2tbZdthx7ILs1tuV2/3ZqLpxMSJGyY2T/xu72Kfab/H/rGDpkOIw3KHRod3jtaOQsdyx7tOdKdApyVODU5vnW2cxc47nB+4MFwmu6x0aXL509XNVe5a7drrZuqW5LbN7T6HyYngrOZccSe4+7kvcT/l/sXD1SPH46jHH562nhmeBzx7JllMEk/aM6nTy9hL4LXLq92b7Z3k/ZN3u4+Rj8Cnwue5r4mvyHevbzfXipvOPch942fvJ/er9fvE8+At4p3zx/kH+Rf6twRoBsQElAU8CzQOTA2sCuwLcglaEHQumBAcGrwh+D5fny/kV/L7QtxCFoVcDKWFRoWWhT4Psw6ThzVORieHTN44+ckUsynSKfXhEM4P3xj+NMIiYk7EyanEqRFTy6e+iHSIXBjZHMWImhV1IOpjtF/0uujHMZYxipimWLXY6bGVsZ/i/OOK49rjJ8Yvir+eoJsgSWhIJCXGJu5N7J8WMG3ztK7pLtMLprfNsJgxb8bVmbozM2eenqU2SzDrWBIhKS7pQNI3QbigQtCfzE/eltwn5Am3CF+JfEWbRL1iL3GxuDvFK6U4pSfVK3Vjam+aT1pJ2msJT1ImeZsenL4z/VNGeMa+jMHMuMyaLHJWUtYJqaY0Q3pxtsHsebNbZTayAln7HI85m+f0yUPle7OR7BnZDTlMbDi6obBU/KDoyPXOLc/9PDd27rF5GvOk827Mt56/an53XmDezwvwC4QLmhYaLVy2sGMRd9Guxcji5MVNS0yW5C/pWhq0dP8y6rKMZb8st19evPzDirgVjfn6+UvzO38I+qGqQLVAXnB/pefKnT/if5T82LLKadXWVd8LRYXXiuyLSoq+rRauvrbGYU3pmsG1KWtb1rmu27GeuF66vm2Dz4b9xRrFecWdGydvrNvE3lS46cPmWZuvljiX7NxC3aLY0l4aVtqw1XTr+q3fytLK7pX7ldds09u2atun7aLtt3f47qjeqb+zaOfXnyQ/PdgVtKuuwryiZDdxd+7uF3ti9zT/zPm5cq/u3qK9f+6T7mvfH7n/YqVbZeUBvQPrqtAqRVXvwekHbx3yP9RQbVu9q4ZVU3QYDisOvzySdKTtaOjRpmOcY9XHzY5vq2XUFtYhdfPr+urT6tsbEhpaT4ScaGr0bKw9aXdy3ymjU+WntU6vO0M9k39m8Gze2f5zsnOvz6ee72ya1fT4QvyFuxenXmy5FHrpyuXAyxeauc1nr3hdOXXV4+qJa5xr9dddr9fdcLlR+4vLL7Utri11N91uNtzyv9XYOqn1zG2f2+fv+N+5fJd/9/q9Kfda22LaHtyffr/9gehBz8PMh28f5T4aeLz0CeFJ4VP1pyXP9J5V/Gr1a027a/vpDv+OG8+jnj/uFHa++i37t29d+S/oL0q6Dbsrexx7TvUG9t56Oe1l1yvZq4HXBb9r/L7tjeWb43/4/nGjL76v66387eC71e913u/74PyhqT+i/9nHrI8Dnwo/63ze/4Xzpflr3NfugbnfSN9K/7T6s/F76Pcng1mDgzKBXDA8CuAwRVNSAN7tA6AnADCwGYI6bWSmHhZk5D9gmOA/8cjcPSyuANWYGRqNeOcADmNqvhRAzRdgaCyK9gXUyUmpo/Pv8Kw+JAbYv8K0HECi2x6tebQU/iEjc/xf+v6nBWXWv9l/AV0EC6JTIblRAAAAeGVYSWZNTQAqAAAACAAFARIAAwAAAAEAAQAAARoABQAAAAEAAABKARsABQAAAAEAAABSASgAAwAAAAEAAgAAh2kABAAAAAEAAABaAAAAAAAAAJAAAAABAAAAkAAAAAEAAqACAAQAAAABAAAAFKADAAQAAAABAAAAFAAAAAAXNii1AAAACXBIWXMAABYlAAAWJQFJUiTwAAAB82lUWHRYTUw6Y29tLmFkb2JlLnhtcAAAAAAAPHg6eG1wbWV0YSB4bWxuczp4PSJhZG9iZTpuczptZXRhLyIgeDp4bXB0az0iWE1QIENvcmUgNi4wLjAiPgogICA8cmRmOlJERiB4bWxuczpyZGY9Imh0dHA6Ly93d3cudzMub3JnLzE5OTkvMDIvMjItcmRmLXN5bnRheC1ucyMiPgogICAgICA8cmRmOkRlc2NyaXB0aW9uIHJkZjphYm91dD0iIgogICAgICAgICAgICB4bWxuczp0aWZmPSJodHRwOi8vbnMuYWRvYmUuY29tL3RpZmYvMS4wLyI+CiAgICAgICAgIDx0aWZmOllSZXNvbHV0aW9uPjE0NDwvdGlmZjpZUmVzb2x1dGlvbj4KICAgICAgICAgPHRpZmY6T3JpZW50YXRpb24+MTwvdGlmZjpPcmllbnRhdGlvbj4KICAgICAgICAgPHRpZmY6WFJlc29sdXRpb24+MTQ0PC90aWZmOlhSZXNvbHV0aW9uPgogICAgICAgICA8dGlmZjpSZXNvbHV0aW9uVW5pdD4yPC90aWZmOlJlc29sdXRpb25Vbml0PgogICAgICA8L3JkZjpEZXNjcmlwdGlvbj4KICAgPC9yZGY6UkRGPgo8L3g6eG1wbWV0YT4KReh49gAAAjRJREFUOBGFlD2vMUEUx2clvoNCcW8hCqFAo1dKhEQpvsF9KrWEBh/ALbQ0KkInBI3SWyGPCCJEQliXgsTLefaca/bBWjvJzs6cOf/fnDkzOQJIjWm06/XKBEGgD8c6nU5VIWgBtQDPZPWtJE8O63a7LBgMMo/Hw0ql0jPjcY4RvmqXy4XMjUYDUwLtdhtmsxnYbDbI5/O0djqdFFKmsEiGZ9jP9gem0yn0ej2Yz+fg9XpfycimAD7DttstQTDKfr8Po9GIIg6Hw1Cr1RTgB+A72GAwgMPhQLBMJgNSXsFqtUI2myUo18pA6QJogefsPrLBX4QdCVatViklw+EQRFGEj88P2O12pEUGATmsXq+TaLPZ0AXgMRF2vMEqlQoJTSYTpNNpApvNZliv1/+BHDaZTAi2Wq1A3Ig0xmMej7+RcZjdbodUKkWAaDQK+GHjHPnImB88JrZIJAKFQgH2+z2BOczhcMiwRCIBgUAA+NN5BP6mj2DYff35gk6nA61WCzBn2JxO5wPM7/fLz4vD0E+OECfn8xl/0Gw2KbLxeAyLxQIsFgt8p75pDSO7h/HbpUWpewCike9WLpfB7XaDy+WCYrFI/slk8i0MnRRAUt46hPMI4vE4+Hw+ec7t9/44VgWigEeby+UgFArJWjUYOqhWG6x50rpcSfR6PVUfNOgEVRlTX0HhrZBKz4MZjUYWi8VoA+lc9H/VaRZYjBKrtXR8tlwumcFgeMWRbZpA9ORQWfVm8A/FsrLaxebd5wAAAABJRU5ErkJggg==")


(defn ^{:mcp-name "getTinyImage"
        :mcp-type :tool} tool-get-tiny-image
  "Returns the MCP_TINY_IMAGE"
  [{:keys []}]
  (-> [(eg/make-text-content "This is a tiny image")
       (eg/make-image-content mcp-tiny-image "image/png")
       (eg/make-text-content "The image above is the MCP tiny image.")]
      (eg/make-call-tool-result)))


(defn ^{:mcp-name "annotatedMessage"
        :mcp-type :tool} tool-annotated-message
  "Demonstrates how annotations can be used to provide metadata about content"
  [^{:malli [:map
             [:message-type [:enum "error" "success" "debug"]]
             [:include-image? {:optional true} :boolean]]}
   {:keys [^{:doc "Type of message to demonstrate different annotation patterns"
             :type "string" ;"either of 'error', 'success', 'debug'"
             }
           message-type
           ^{:doc "Whether to include an example image"
             :type "boolean"
             :name "include-image"}
           include-image?]
    :as args}]
  (if-let [item (case message-type
                  "error" (let [ann (eg/make-annotations
                                     {:priority 1.0  ; errors are highest priority
                                      :audience-roles [sd/role-user
                                                       sd/role-assistant]  ; both need to know about errors
                                      })]
                            (eg/make-text-content
                             "Error: Operation failed"
                             {:annotations ann}))
                  "success" (let [ann (eg/make-annotations
                                       {:priority 0.7  ; success messages are important but not critical
                                        :audience-roles [sd/role-user]  ; success mainly for user consumption
                                        })]
                              (eg/make-text-content
                               "Operation completed successfully"
                               {:annotations ann}))
                  "debug" (let [ann (eg/make-annotations
                                     {:priority 0.3  ; debug info is low priority
                                      :audience-roles [sd/role-assistant]  ; technical details for assistant
                                      })]
                            (eg/make-text-content
                             "Debug: Cache hit ratio 0.95, latency 150ms"
                             {:annotations ann}))
                  nil)]
    (if include-image?
      [item
       (let [ann (eg/make-annotations {:priority 0.5
                                       :audience-roles [sd/role-user]  ; images primarily for user visualization
                                       })]
         (eg/make-image-content mcp-tiny-image "image/png"
                                {:annotations ann}))]
      [item])
    (jr/jsonrpc-failure
     sd/error-code-invalid-params
     (str "Invalid enum value. Expected 'error', 'success' or 'debug', but received "
          message-type))))


(def all-resources
  (let [uri-prefix "test://static/resource/"]
    (->> (range 100)
         (mapv (fn [^long i]
                 (let [inc-i (inc i)]
                   (-> (if (even? i)
                         (eg/make-text-resource-contents
                          (str uri-prefix inc-i)
                          (format "Resource %d: This is a plaintext resource"
                                  inc-i)
                          {:mime-type "text/plain"})
                         (eg/make-blob-resource-contents
                          (str uri-prefix inc-i)
                          (-> (format "Resource %d: This is a base64 blob"
                                      inc-i)
                              (u/str->byte-array)
                              (u/as-base64-str "blob"))
                          {:mime-type "application/octet-stream"}))
                       (assoc :name (str "Resource " inc-i)))))))))


(defn ^{:mcp-name "getResourceReference"
        :mcp-type :tool} tool-get-resource-reference
  "Returns a resource reference that can be used by MCP clients"
  [^{:malli [:map [:resource-id [:int {:min 1 :max 100}]]]}
   {:keys [^{:doc "ID of the resource to reference (1-100)"
             :type "number"}
           ^long resource-id]}]
  (let [resource-index (dec resource-id)]
    (when-not (<= 0 resource-index 99)
      (u/throw! (format "Resource with ID %s does not exist"
                        resource-id)))
    (let [resource (get all-resources resource-index)]
      (-> [(eg/make-text-content (str "Returning resource reference for Resource "
                                      resource-id))
           (eg/make-embedded-resource resource)
           (eg/make-text-content (str "You can access this resource using the URI: "
                                      (:uri resource)))]))))


(defn ^{:mcp-name "start-elicitation-callback"
        :mcp-type :callback
        :see sd/ElicitResult} start-elicitation-callback
  [{:as elicitation-result}]
  (let [request-context (-> elicitation-result
                            rt/?request-context)
        promise-deliver (fn [v]
                          (-> (:promise-pair request-context)
                              (bear-promise v)))
        eli-action (:action elicitation-result)]
    (-> (cond
          ;;
          (and (= "accept" eli-action)
               (:content elicitation-result))
          [(eg/make-text-content "✅ User provided their favorite things!")
           (let [{:keys [color number pets]} (:content elicitation-result)]
             (eg/make-text-content
              (format "Their favorites are:\n- Color: %s\n- Number: %s\n- Pets: %s"
                      (or color "not specified")
                      (or number "not specified")
                      (or pets "not specified"))))]
          ;;
          (= "decline" eli-action)
          [(eg/make-text-content "❌ User declined to provide their favorite things.")]
          ;;
          (= "cancel" eli-action)
          [(eg/make-text-content "⚠️ User cancelled the elicitation dialog.")])
        (conj (eg/make-text-content (str "\nRaw result: "
                                         (-> elicitation-result
                                             rt/dissoc-runtime
                                             u/pprint-str))))
        (eg/make-call-tool-result)
        (promise-deliver))))


(defn ^{:mcp-name "startElicitation"
        :mcp-type :tool} tool-start-elicitation
  "Demonstrates the Elicitation feature by asking the user to provide
   information about their favorite color, number, and pets."
  [{:as kwargs}]
  (let [elicitation-request (eg/make-elicit-request
                             "What are your favorite things?"
                             {:color {:type "string"
                                      :description "Favorite color"}
                              :number {:type "integer"
                                       :description "Favorite number",
                                       :minimum 1 :maximum 100}
                              :pets {:type "string"
                                     :enum ["cats" "dogs" "birds" "fish"
                                            "reptiles"]
                                     :description "Favorite pets"}})
        promise-pair (make-promise)
        request-context {:promise-pair promise-pair
                         :callback-name "start-elicitation-callback"}]
    (rs/send-request-to-client kwargs elicitation-request request-context)
    (upon-promise promise-pair identity)))


(defn ^{:mcp-name "getResourceLinks"
        :mcp-type :tool} tool-get-resource-links
  "Returns multiple resource links that reference different types of
   resources."
  [^{:malli [:map
             [:count {:optional true} [:int {:min 1 :max 10}]]]}
   {^{:doc "Number of resource links to return (1-10)"
      :type "number"
      :name "count"
      :default 3
      :minimum 1
      :maximum 10}
    rl-count :count
    :or {rl-count 3}
    :as kwargs}]
  (->> (range rl-count)
       (reduce (fn [all ^long i]
                 (let [resource (get all-resources i)]
                   (->> (eg/make-resource-link
                         (:uri resource)
                         (:name resource)
                         {:description (format "Resource %d: %s"
                                               (inc i)
                                               (if (= "text/plain" (:mimeType resource))
                                                 "plaintext resource"
                                                 "binary blob resource"))})
                        (conj all))))
               [(eg/make-text-content
                 (format "Here are %d resource links to resources available in this server (see full output in tool response if your client does not support resource_link yet):"
                         rl-count))])))


(def tools
  (->> [#'tool-echo
        #'tool-add
        #'tool-long-running-operation
        #'tool-print-env
        #'tool-sample-llm
        #'tool-get-tiny-image
        #'tool-annotated-message
        #'tool-get-resource-reference
        #'tool-start-elicitation
        #'tool-get-resource-links]
       (mapv #(vs/make-tool-from-var % {:var-handler sm/wrap-var-kwargs-schema-check}))))


(def callbacks
  (->> [#'sample-llm-callback
        #'start-elicitation-callback]
       (map vs/make-callback-from-var)
       (reduce merge)))


;; --- resources ---


(def resources
  (->> all-resources
       (mapv (fn [r]
               (assoc r :handler
                      (fn [{:keys [uri]}]
                        (let [unknown-resource (jr/jsonrpc-failure
                                                sd/error-code-invalid-params
                                                (str "Unknown resource: "
                                                     uri))]
                          (if (str/starts-with? uri "test://static/resource/")
                            (let [index (-> (str/split uri #"/")
                                            last
                                            parse-long)]
                              (if (<= 0 index 99)
                                (eg/make-read-resource-result [(get all-resources index)])
                                unknown-resource))
                            unknown-resource))))))))


(def resource-templates
  (->> []))


;; --- prompts ---


(defn ^{:mcp-name "simple_prompt"
        :mcp-type :prompt} prompt-simple
  "A prompt without arguments"
  [{}]
  (eg/make-get-prompt-result
   [(eg/make-prompt-message
     sd/role-user
     (eg/make-text-content
      "This is a simple prompt without arguments."))]))


(defn ^{:mcp-name "complex_prompts"
        :mcp-type :prompt} prompt-complex
  "A prompt with arguments"
  [{:keys [^{:doc "Temperature setting"
             :type "string"}
           temperature
           ^{:doc "Output style"
             :required false}
           style]}]
  (eg/make-get-prompt-result
   [(eg/make-prompt-message
     sd/role-user
     (eg/make-text-content
      (format "This is a complex prompt with arguments: temperature=%s, style=%s"
              temperature
              style)))
    ;--
    (eg/make-prompt-message
     sd/role-assistant
     (eg/make-text-content
      "I understand. You've provided a complex prompt with temperature and style arguments. How would you like me to proceed?"))
    ;--
    (eg/make-prompt-message
     sd/role-user
     (eg/make-image-content
      mcp-tiny-image
      "image/png"))]))


(defn ^{:mcp-name "resource_prompt"
        :mcp-type :prompt} prompt-resource
  "A prompt that includes an embedded resource reference"
  [{^{:doc "Resource ID to include (1-100)"
      :name "resourceId"}
    resource-id-str :resourceId}]
  (let [^long resource-id (parse-long resource-id-str)
        resource (get all-resources (dec resource-id))]
    (if (<= 1 resource-id 100)
      (eg/make-get-prompt-result
       [(eg/make-prompt-message
         sd/role-user
         (eg/make-text-content
          (format "This prompt includes Resource %d. Please analyze the following resource:"
                  resource-id)))
        ;--
        (eg/make-prompt-message
         sd/role-user
         (eg/make-embedded-resource resource))])
      (jr/jsonrpc-failure sd/error-code-invalid-params
                          (format "Invalid resourceId: %s. Must be a number between 1 and 100."
                                  resource-id-str)))))


(def prompts (->> [#'prompt-simple
                   #'prompt-complex
                   #'prompt-resource]
                  (mapv vs/make-prompt-from-var)))


(defn make-mcp-primitives [server-options]
  (-> (u/find-vars)
      (vs/vars->server-primitives server-options)
      ;; assoc manually-made resources
      (assoc :resources resources)))
