;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.test-support
  "Support infra for tests."
  (:require
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.deps.runtime-support :as rs]))


(defn make-runtime-server-session
  ([seed-runtime]
   (let [context {}
         server-session (rs/set-server-session context :test-session-id
                                               (fn [context message]
                                                 #_(u/eprintln "->Client:"
                                                               message)))
         context (-> context
                     (rt/upsert-runtime seed-runtime)
                     (rt/?session server-session))]
     (rs/set-initialized-timestamp context)
     (rt/get-runtime context)))
  ([]
   (make-runtime-server-session {})))
