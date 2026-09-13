;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns user)


(defn env-true?
  [env-var sys-prop]
  (-> (System/getenv env-var)          ; env var overrides sysprop
      (or (System/getProperty sys-prop))   ; meant for build files
      (or "true")    ; set value as NOT "true" to disable warnings
      parse-boolean
      true?))


(def verbose? (env-true? "VERBOSE" "verbose"))
(def devmode? (env-true? "DEVMODE" "devmode"))


(defn eprintln
  "Print to STDERR."
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))


(when verbose?
  (eprintln)
  (eprintln "╭────────────────────────────────────────────────────────╮")
  (eprintln "│ VERBOSE mode is ON. Prints the following to STDERR:    │")
  (eprintln "│ - Reflection warnings                                  │")
  (eprintln "│ - Instrumentation events                               │")
  (eprintln "│                                                        │")
  (eprintln "│ To turn verbose mode off, set either:                  │")
  (eprintln "│ - Environment var: `VERBOSE=false` (overrides sysprop) │")
  (eprintln "│ - System property: `verbose=false`                     │")
  (eprintln "╰────────────────────────────────────────────────────────╯")
  (eprintln))


(when devmode?
  (eprintln)
  (eprintln "╭────────────────────────────────────────────────────────╮")
  (eprintln "│ DEVMODE mode is ON. Does the following:                │")
  (eprintln "│ - Instrumentation & patching                           │")
  (eprintln "│                                                        │")
  (eprintln "│ To turn DEVMODE off, set either:                       │")
  (eprintln "│ - Environment var: `DEVMODE=false` (overrides sysprop) │")
  (eprintln "│ - System property: `devmode=false`                     │")
  (eprintln "╰────────────────────────────────────────────────────────╯")
  (eprintln))


;; Equivalent of the following in Leiningen project.clj:
;; ```
;; :global-vars {*warn-on-reflection* true
;;               *assert* true
;;               *unchecked-math* :warn-on-boxed}
;; ```
;; See: https://ask.clojure.org/index.php/3787/theres-enable-warn-reflection-from-command-running-clojure?show=12656#a12656
;;
(when verbose?
  (alter-var-root #'*warn-on-reflection* (constantly true))
  (alter-var-root #'*assert* (constantly true))
  (alter-var-root #'*unchecked-math* (constantly :warn-on-boxed)))


;; Enable #p for debugging
(require '[clojure+.hashp])
(clojure+.hashp/install!)


;; Enable humane test output
(require 'pjstadig.humane-test-output)
(pjstadig.humane-test-output/activate!)


;; Patch Malli schema-spec validation
(require '[malli.core :as mc]
         '[plumcp.core.schema.schema-util :as su])
(when devmode?
  (when verbose?
    (eprintln)
    (eprintln "╭─────────────────────────────────────────────────────────╮")
    (eprintln "│ PATCHING plumcp.core.schema.schema-util/validate-schema │")
    (eprintln "│ for early Malli schema validation. Throws up on error.  │")
    (eprintln "╰─────────────────────────────────────────────────────────╯")
    (eprintln))
  (alter-var-root #'su/validate-schema
                  (constantly (fn [spec]
                                (mc/schema spec)
                                spec))))
