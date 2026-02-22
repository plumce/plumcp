;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.test-constant
  "Test constants"
  #?(:cljs (:require-macros [plumcp.core.test.test-constant
                             :refer [find-verbose]])))


#?(:clj
   (defmacro find-verbose
     "Return true if verbose mode is on, false otherwise."
     []
     (-> (System/getenv "VERBOSE")        ; env var overrides sysprop
         (or (System/getProperty "verbose"))  ; meant for build files
         (or "true")    ; set value as NOT "true" to disable warnings
         parse-boolean
         true?)))


(def verbose? "Whether to be VERBOSE" (find-verbose))
