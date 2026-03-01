;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.test-util-java
  "Extra test harness for the Java platform"
  (:require
   [clojure.test :as t]
   [plumcp.core.test.test-constant :as tc]
   [plumcp.core.util :as u]))


(def ^:dynamic *current-test-var* nil)

(defn replace-reporter []
  (let [orig-report t/report]
    (alter-var-root
     #'t/report
     (fn [_]
       (fn [m]
         (when (= (:type m) :begin-test-var)
           (let [v (:var m)]
             (alter-var-root #'*current-test-var* (constantly v))
             (when tc/verbose?
               (let [{:keys [name file line]} (meta v)]
                 (u/eprintln "Running Test:" name "at" file ":" line)))))
         (orig-report m))))))


(defn catch-and-stop! []
  (alter-var-root #'*current-test-var* (constantly nil))
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ thread err]
       (if-let [v *current-test-var*]
         (-> "UNCAUGHT in %s\nError-mesg: %s\nError-data: %s"
             (format (:name (meta v)) (ex-message err) (ex-data err))
             u/eprintln)
         (u/eprintln "UNCAUGHT (no active test)"))
       (u/print-stack-trace err)
       (System/exit 1)))))


(defn catch-and-continue! []
  (alter-var-root #'*current-test-var* (constantly nil))
  (Thread/setDefaultUncaughtExceptionHandler
   (reify Thread$UncaughtExceptionHandler
     (uncaughtException [_ _thread err]
       (binding [*out* *err*]
         (if-let [v *current-test-var*]
           (-> "UNCAUGHT in %s\nError-mesg: %s\nError-data: %s"
               (format (:name (meta v)) (ex-message err) (ex-data err))
               u/eprintln)
           (u/eprintln "UNCAUGHT (no active test)"))
         ;; print full stack trace
         (u/print-stack-trace err)
         ;; also report to clojure.test so it counts as an :error
         (when-let [v *current-test-var*]
           (t/report {:type :error
                      :var v
                      :message (.getMessage err)
                      :expected nil
                      :actual err})))))))
