;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.stdio-server-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.server.stdio-server :as stdio]))


#?(:clj
   (deftest process-stdin-exits-on-stdin-eof
     (testing "process-stdin returns when *in* reaches EOF. Without
               this, a client closing the pipe leaves the server JVM
               spinning forever on (read-line) returning nil."
       (let [calls   (atom [])
             reader  (clojure.lang.LineNumberingPushbackReader.
                      (java.io.StringReader. "line1\nline2\n"))
             done    (promise)
             worker  (doto (Thread.
                            #(do
                               (binding [*in* reader]
                                 (stdio/process-stdin
                                  (fn [line]
                                    (when (some? line)
                                      (swap! calls conj line)))))
                               (deliver done :ok)))
                       (.setDaemon true)
                       (.setName "stdio-eof-test-worker")
                       (.start))
             result  (deref done 2000 :timeout)]
         (is (= :ok result)
             (str "process-stdin must return when stdin reaches EOF. "
                  "Got :timeout, which means the loop kept calling "
                  "process-line with nil and never terminated."))
         (is (= ["line1" "line2"] @calls)
             "all pre-EOF lines must have been delivered to process-line")))))
