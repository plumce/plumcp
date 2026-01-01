;; Equivalent of he following in Leiningen project.clj:
;; ```
;; :global-vars {*warn-on-reflection* true
;;               *assert* true
;;               *unchecked-math* :warn-on-boxed}
;; ```
;; See: https://ask.clojure.org/index.php/3787/theres-enable-warn-reflection-from-command-running-clojure?show=12656#a12656
;;
(alter-var-root #'*warn-on-reflection* (constantly true))
(alter-var-root #'*assert* (constantly true))
(alter-var-root #'*unchecked-math* (constantly :warn-on-boxed))

