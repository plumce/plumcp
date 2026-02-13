;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


;; Equivalent of the following in Leiningen project.clj:
;; ```
;; :global-vars {*warn-on-reflection* true
;;               *assert* true
;;               *unchecked-math* :warn-on-boxed}
;; ```
;; See: https://ask.clojure.org/index.php/3787/theres-enable-warn-reflection-from-command-running-clojure?show=12656#a12656
;;
(when (-> (System/getenv "VERBOSE")        ; env var overrides sysprop
          (or (System/getProperty "verbose"))  ; meant for build files
          (or "true")    ; set value as NOT "true" to disable warnings
          parse-boolean
          true?)
  (binding [*out* *err*]
    (println)
    (println ",-----------------------------------------------------------.")
    (println "|  Reflection warning is ON. To turn this off, set either:  |")
    (println "|                                                           |")
    (println "|  - Environment var: `VERBOSE=false` (overrides sysprop)   |")
    (println "|  - System property: `verbose=false`                       |")
    (println "`-----------------------------------------------------------'")
    (println)
    (flush))
  (alter-var-root #'*warn-on-reflection* (constantly true))
  (alter-var-root #'*assert* (constantly true))
  (alter-var-root #'*unchecked-math* (constantly :warn-on-boxed)))


;; Enable #p for debugging
(require '[clojure+.hashp])
(clojure+.hashp/install!)

