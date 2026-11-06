;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.test.era26-mcp-method-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.era26-entity-gen :as e26eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.era26-impl-method :as e26im]
   [plumcp.core.schema.era26-schema-defs :as e26sd]))


(deftest discover-test
  (let [client-capabilities (e26eg/make-client-capabilities)
        server-capabilities (e26eg/make-server-capabilities)
        request-meta-object (e26eg/make-request-meta-object
                             {:protocol-version e26sd/protocol-version-max
                              :client-capabilities client-capabilities})
        request-id (eg/make-id)
        discover-request (e26eg/make-discover-request
                          {:id request-id
                           :request-meta-object request-meta-object})
        discover-result (-> discover-request
                            (rt/?server-capabilities server-capabilities)
                            (rt/?server-instructions "Test server")
                            e26im/server-discover)]
    (testing "server/discover"
      (is (= {:ttlMs 3600000,
              :cacheScope "public",
              :supportedVersions ["2026-07-28"],
              :capabilities {},
              :resultType "complete",
              :instructions "Test server"}
             discover-result)))))
