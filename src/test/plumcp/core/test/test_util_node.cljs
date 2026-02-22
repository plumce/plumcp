;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.test-util-node
  "Extra test harness for Node.js"
  (:require
   [clojure.test :as t :include-macros true]
   [plumcp.core.test.test-constant :as tc]
   [plumcp.core.util :as u]))


(def ^:dynamic *current-test-var* nil)  ; updated as and when test runs


(defn replace-reporter
  "Replace the test reporter by wrapping original to track the current
   test var, and if VERBOSE env var is not false, print the test var."
  []
  ;; Save the original reporter
  (let [orig-report t/report]
    ;; Override reporter to also catch uncaught exceptions
    (set! t/report
          (fn [m]
            ;; If begin-test-var, remember the current test var
            (when (= (:type m) :begin-test-var)
              (let [test-var (:var m)]
                (when tc/verbose?
                  (let [meta (meta test-var)]
                    (println "Running Test:"
                             (:name meta)
                             "at"
                             (:file meta)
                             ":"
                             (:line meta))))
                (set! *current-test-var* test-var)))
            ;; Forward event to original reporter
            (orig-report m)))))


(defn report-error
  "Report the error encountered durin testing."
  ([on-done err]
   (u/dprint "Arity-2" {:on-done on-done :err err})
   (let [v *current-test-var*]
     (if v
       (do
         ;; Print stack trace immediately
         (println "FAIL in" (:name (meta v)) "stack:\n" (.-stack err))
         ;; Report to cljs.test as an error
         (t/report {:type :error
                    :var v
                    :message (.-message err)
                    :expected nil
                    :actual (.-stack err)}))
       ;; If no active test (namespace load etc.), still print stack
       (js/console.error "UNCAUGHT (no active test):" (.-stack err))))
   (on-done))
  ([err]
   (report-error u/nop err)))


(defn exit-node!
  "Exit Node with failure code"
  []
  (js/process.exit 1))


(defn catch-and-stop!
  "Catch error and stop without running any further tests."
  []
  ;; Track the currently running test var
  (set! *current-test-var* nil)

  ;; Catch any uncaught exception and mark the current test as :error
  (js/process.on "uncaughtException"
                 (fn [err]
                   (report-error exit-node! err))))


(defn catch-and-continue!
  "Catch error and continue running other tests."
  []
  (js/process.on "beforeExit"
                 (fn []
                   (let [rcs (-> (t/get-current-env)
                                 :report-counters)
                         tfc (+ (:fail rcs (:error rcs)))]
                     (when (pos? tfc)  ; total fail count
                       (u/eprintln "Test suite finished with" tfc
                                   "failed/error tests")
                       (js/process.exit 1)))))
  ;; Keep track of uncaught exceptions without crashing Node
  (js/process.on "uncaughtException"
                 (fn [err]
                   (report-error err))))
