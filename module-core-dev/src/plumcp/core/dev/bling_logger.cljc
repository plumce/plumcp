;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.dev.bling-logger
  "Verbose traffic logger using the Bling library."
  (:require
   [bling.core :refer [bling]]
   [clojure.pprint :as pp]
   [clojure.string :as string]
   [plumcp.core.protocol :as p]
   [plumcp.core.support.traffic-logger :as stl]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]
   [plumcp.core.deps.runtime :as rt]))


(defn pretty-string
  ([prefix header note data]
   (let [pretty (u/pprint-str data)]
     (if (seq header)
       (format "%s%s %s\n%s\n%s"
               prefix
               header
               note
               (-> (count prefix)
                   (+ (count header))
                   (u/repeat-str "-"))
               pretty)
       pretty)))
  ([role header data]
   (pretty-string role header "" data))
  ([role data]
   (pretty-string role "" data)))


(defn impl-log-http-request [prefix request]
  (let [method (:request-method request)
        colour (case method
                 :post :blue
                 :get :purple
                 :white)]
    (u/eprintln (bling [colour
                        (pretty-string prefix "Ring request:"
                                       (str (-> (:request-method request)
                                                u/as-str
                                                string/upper-case)
                                            " "
                                            (:uri request))
                                       (rt/dissoc-runtime request))]))))


(defn ring-response-text
  [response]
  (when (and (map? response)
             (= (get-in response [:headers "Content-Type"])
                "text/plain"))
    (get response :body)))


(defn prettify-ring-response
  "Prettify the Ring response, while avoiding to realize (potentially
   infinite) ISeq body type."
  [response response-text prefix]
  (->> (fn [body] (cond
                    (seq? body) '<ISeq>
                    (uab/iterator? body) '<Iterator>
                    (string? body) (if response-text '<See-below> body)
                    (map? body) body
                    :else (type body)))
       (update response :body) ; don't realize (maybe infinite) lazy seqs
       (pretty-string prefix "Ring response:")))


(defn impl-log-http-response [prefix response]
  (u/eprintln (let [response-text (ring-response-text response)]
                (bling [:yellow (-> response
                                    (dissoc :on-sse :on-msg)
                                    (prettify-ring-response response-text
                                                            prefix))]
                       [:bold.yellow (and response-text
                                          (str "\n" response-text))]))
              "\n-------------------- End of request --------------------"))


(defn impl-log-http-failure [prefix failure]
  (u/eprintln (bling [:bold.yellow (pretty-string prefix "Ring failure:"
                                                  (if (map? failure)
                                                    (-> failure
                                                        (dissoc :on-sse
                                                                :on-msg))
                                                    failure))])
              "\n-------------------- End of request --------------------"))


(defn impl-log-jsonrpc-request [prefix request]
  (let [request-id (:id request)]
    (u/eprintln (bling [(if request-id :bold.blue :bold.error)
                        (pretty-string prefix (str "JSON-RPC request:"
                                                   (if request-id
                                                     (str "ID=" request-id)
                                                     "(ID missing)"))
                                       (rt/dissoc-runtime request))]))))


(defn impl-log-jsonrpc-success [prefix id response]
  (u/eprintln (bling [(if id :green :bold.error)
                      (pretty-string prefix (str "JSON-RPC success:"
                                                 (if id (str "ID=" id)
                                                     "(ID missing)"))
                                     response)])))


(defn impl-log-jsonrpc-failure [prefix id error]
  (u/eprintln (bling [(if id :red :bold.error)
                      (pretty-string prefix (str "JSON-RPC failure:"
                                                 (if id (str "ID=" id)
                                                     "(ID missing)"))
                                     error)])))


(defn impl-log-mcp-notification [prefix notification]
  (u/eprintln (bling [:gray (pretty-string prefix "MCP notification:"
                                           notification)])))


(defn impl-log-mcpcall-failure [prefix error]
  (u/eprintln (bling [:magenta (pretty-string prefix "MCP-Call failure:"
                                              error)])))


(defn impl-log-mcp-sse-message [prefix message]
  (u/eprintln (bling [:olive (with-redefs [pp/pprint println]
                               (pretty-string prefix "MCP-SSE Message:"
                                              message))])))


(defn make-logger
  [role]
  (reify
    p/ITrafficLogger
    (log-http-request [_
                       request] (as-> stl/role->dir:http-request $
                                  (stl/make-prefix role $)
                                  (impl-log-http-request $ request)))
    (log-http-response [_
                        response] (as-> stl/role->dir:http-response $
                                    (stl/make-prefix role $)
                                    (impl-log-http-response $ response)))
    (log-http-failure [_
                       failure] (as-> stl/role->dir:http-failure $
                                  (stl/make-prefix role $)
                                  (impl-log-http-failure $ failure)))
    (log-incoming-jsonrpc-request
      [_ request] (as-> stl/role->dir:incoming-jsonrpc-request $
                    (stl/make-prefix role $)
                    (impl-log-jsonrpc-request $ request)))
    (log-outgoing-jsonrpc-request
      [_ request] (as-> stl/role->dir:outgoing-jsonrpc-request $
                    (stl/make-prefix role $)
                    (impl-log-jsonrpc-request $ request)))
    (log-incoming-jsonrpc-success
      [_ id result] (as-> stl/role->dir:incoming-jsonrpc-success $
                      (stl/make-prefix role $)
                      (impl-log-jsonrpc-success $ id result)))
    (log-outgoing-jsonrpc-success
      [_ id result] (as-> stl/role->dir:outgoing-jsonrpc-success $
                      (stl/make-prefix role $)
                      (impl-log-jsonrpc-success $ id result)))
    (log-incoming-jsonrpc-failure
      [_ id error] (as-> stl/role->dir:incoming-jsonrpc-failure $
                     (stl/make-prefix role $)
                     (impl-log-jsonrpc-failure $ id error)))
    (log-outgoing-jsonrpc-failure
      [_ id error] (as-> stl/role->dir:outgoing-jsonrpc-failure $
                     (stl/make-prefix role $)
                     (impl-log-jsonrpc-failure $ id error)))
    (log-mcp-notification [_
                           notification] (as-> stl/role->dir:mcp-notification $
                                           (stl/make-prefix role $)
                                           (impl-log-mcp-notification $ notification)))
    (log-mcpcall-failure [_
                          error] (as-> stl/role->dir:mcpcall-failure $
                                   (stl/make-prefix role $)
                                   (impl-log-mcpcall-failure $ error)))
    (log-mcp-sse-message [_
                          message] (as-> stl/role->dir:mcp-sse-message $
                                     (stl/make-prefix role $)
                                     (impl-log-mcp-sse-message $ message)))))


(def server-logger (make-logger :server))
(def client-logger (make-logger :client))
