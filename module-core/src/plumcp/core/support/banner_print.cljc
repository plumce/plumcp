;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.support.banner-print
  "Banner printing utility"
  (:require
   #?(:cljs [plumcp.core.util-node :as un])
   [clojure.string :as str]
   [plumcp.core.constant :as const]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]))


(comment
  ;; BOX reference
  ;; -------------

  ;; https://en.wikipedia.org/wiki/Box-drawing_characters
  ;;
  (def box-1-top "┌─┬┐")
  (def box-1-hol "│ ││")
  (def box-1-mid "├─┼┤")
  (def box-1-bot "└─┴┘")

  (def box-2-top "╔═╦╗")
  (def box-2-hol "║ ║║")
  (def box-2-mid "╠═╬╣")
  (def box-2-bot "╚═╩╝")

  (def box-3-top "╒═╤╕")
  (def box-3-hol "│ ││")
  (def box-3-mid "╞═╪╡")
  (def box-3-bot "╘═╧╛")

  (def box-4-top "╓─╥╖")
  (def box-4-hol "║ ║║")
  (def box-4-mid "╟─╫╢")
  (def box-4-bot "╙─╨╜"))


(defn boxed-banner-string
  [banner-string]
  (let [lines (str/split-lines banner-string)
        space 2  ; on left and right
        width (->> lines
                   (map count)
                   ;; find max width of all lines
                   (apply max))
        padit (fn [each-line]
                (str (u/repeat-str space " ")
                     each-line
                     (u/repeat-str (- ^long width (count each-line)) " ")
                     (u/repeat-str space " ")))
        boxit (fn [each-line]
                (str "│" each-line "│"))
        lines (->> lines
                   (map padit)
                   (map boxit))
        head (fn [n] (str "┌" (u/repeat-str n "─") "┐"))
        mark (fn [n] (str "├" (u/repeat-str n "─") "┤"))
        foot (fn [n] (str "└" (u/repeat-str n "─") "┘"))]
    (->> (concat [(head (+ ^long space ^long width ^long space))
                  (first lines)
                  (mark (+ ^long space ^long width ^long space))]
                 (rest lines)
                 [(foot (+ ^long space ^long width ^long space))])
         (str/join \newline))))


(def transport-names {:http "Streamable HTTP"
                      :stdio "STDIO"})


(defn make-banner-string
  [{:keys [role
           transport-info
           stdio-command  ; STDIO client only
           http-url       ; HTTP client or server
           port           ; HTTP server only
           uri            ; HTTP server only
           ]
    :or {port 3000
         uri "/mcp"}}]
  (let [transport-id (:id transport-info)
        details (case [transport-id role]
                  [:stdio :server] (str "started: "
                                        (or (when *command-line-args*
                                              (vec *command-line-args*))
                                            #?(:cljs (-> (.-argv js/process)
                                                         vec
                                                         (update 0 un/exec-path)
                                                         (update 1 un/rel-path))
                                               :clj (->> "sun.java.command"
                                                         System/getProperty
                                                         (conj ["java"])))))
                  [:stdio :client] (str "connected to "
                                        (vec (or stdio-command
                                                 (get transport-info
                                                      :command-tokens))))
                  [:http :server] (str "started at "
                                       (or http-url
                                           (format "http://127.0.0.1:%d%s"
                                                   port uri)))
                  [:http :client] (str "connected to "
                                       (or http-url
                                           (get transport-info :default-uri)))
                  [:zero :server] "started"
                  [:zero :client] "connected")]
    (-> "PluMCP %s
Transport: %s
MCP %s %s"
        (format const/version
                (or (get transport-names transport-id)
                    (-> transport-id u/as-str str/upper-case))
                (-> role u/as-str str/capitalize)
                details))))


(defn print-banner
  [{:keys [boxed-banner?]
    :or {boxed-banner? true}
    :as banner-options}]
  (let [banner-string (make-banner-string banner-options)
        printable-banner (if boxed-banner?
                           (boxed-banner-string banner-string)
                           (str banner-string "\n---------------------"))]
    (u/eprintln printable-banner)))
