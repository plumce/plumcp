;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.main.main-client
  "The main entrypoint for MCP client app for both JVM and Node.js."
  (:require
   #?(:cljs ["readline" :as readline])
   [clojure.string :as str]
   [plumcp.core.api.mcp-client :as mc]
   [plumcp.core.client.stdio-client-transport :as sct]
   [plumcp.core.main.client :as client]
   [plumcp.core.protocol :as p]
   [plumcp.core.test.test-util :as tu]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


(defn make-stdio-transport
  [command-tokens]
  (sct/run-command {:command-tokens command-tokens
                    :on-server-exit (partial u/eprintln "[Server Exit]")
                    :on-stdout-line println
                    :on-stderr-text u/eprintln}))


(defn make-http-transport
  [endpoint-uri]
  (client/make-http-transport endpoint-uri))


(defn process-user-input
  [rl token processor]
  (let [prompt (format "\n--------\n[USER %s] Enter %s below and press [Enter]: "
                       token
                       token)]
    #?(:clj (do
              (Thread/sleep 500)
              (u/eprintln prompt)
              (let [input (read-line)]
                (processor rl input)))
       :cljs (let [rl (or rl
                          (-> readline
                              (.createInterface #js{:input (.-stdin js/process)
                                                    :output (.-stderr js/process)})))]
               (-> rl
                   (.question prompt (fn [answer]
                                       (processor rl answer)
                                       ;(.close rl)
                                       )))))))


(defn exit-app
  "Exit app with error code."
  []
  (u/eprintln "Exiting app...")
  #?(:clj (System/exit 1)
     :cljs (.exit js/process 1)))


(defn command-ping [client]
  (tu/until-done [done! 10]
    (uab/may-await [result (mc/ping client)]
      (u/dprint "Ping result" result)
      (done!))))


(def commands
  {"info" (fn [client]
            (u/dprint "Client:" client))
   ;; ----
   "ping" command-ping
   "init" (fn [client]
            (tu/until-done [done! 10]
              (uab/may-await [result (mc/initialize-and-notify! client)]
                (u/dprint "Init result" result)
                (done!))))
   "prompts" (fn [client]
               (tu/until-done [done! 10]
                 (uab/may-await [prompts (mc/list-prompts client)]
                   (u/dprint "Prompts:" prompts)
                   (done!))))
   "resources" (fn [client]
                 (tu/until-done [done! 10]
                   (uab/may-await [resources (mc/list-resources client)]
                     (u/dprint "Resources:" resources)
                     (done!))))
   "templates" (fn [client]
                 (tu/until-done [done! 10]
                   (uab/may-await [templates (mc/list-resource-templates client)]
                     (u/dprint "Resource templates:" templates)
                     (done!))))
   "tools" (fn [client]
             (tu/until-done [done! 10]
               (uab/may-await [tools (mc/list-tools client)]
                 (u/dprint "Tools:" tools)
                 (done!))))
   "quit" (fn [client]
            (exit-app))})


(defn run-command
  [command client]
  (if-let [f (get commands command)]
    (f client)
    (do
      (u/eprintln "No such command:" command)
      (u/dprint "Valid commands:" (-> (keys commands)
                                      sort
                                      vec)))))


(defn run-cli
  [client client-transport]
  (process-user-input nil "command"
                      (fn thisfn [rl command]
                        (cond
                          (or (empty? command)
                              (empty? (str/trim command)))
                          (process-user-input rl "command" thisfn)
                          ;;
                          (= "quit" command)
                          (do
                            (u/eprintln "Quit command received!")
                            #?(:cljs (.close rl))
                            (try
                              (p/stop-client-transport! client-transport false)
                              (finally
                                (p/stop-client-transport! client-transport true)
                                (exit-app))))
                          ;;
                          :else
                          (do
                            (u/eprintln "Got command:" command)
                            (run-command command client)
                            (process-user-input rl "command" thisfn))))))


(defn #?(:clj -main
         :cljs main)
  "The main entrypoint fn for the client."
  [target & args]
  (let [transport (if (str/includes? target "://")
                    :http
                    :stdio)]
    (-> (format "Starting client with transport: %s, target: %s, args: %s"
                transport target
                args)
        (u/eprintln))
    (try
      (let [client-transport (case transport
                               :http (make-http-transport target)
                               :stdio (-> (cons target args)
                                          vec
                                          make-stdio-transport))
            client-context (-> {:client-transport client-transport}
                               (merge client/client-options)
                               (mc/make-client))]
        (command-ping client-context)  ; wait until transport initializes
        (run-cli client-context client-transport))
      (catch #?(:clj Exception :cljs js/Error) e
        (u/print-stack-trace e))
      (finally
        (u/eprintln "CLI over, I guess")))))
