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
   [plumcp.core.api.capability :as cap]
   [plumcp.core.api.entity-gen :as eg]
   [plumcp.core.api.entity-support :as es]
   [plumcp.core.api.era26-entity-gen :as e26eg]
   [plumcp.core.deps.runtime :as rt]
   [plumcp.core.impl.era26-impl-method :as e26im]
   [plumcp.core.impl.impl-capability :as ic]
   [plumcp.core.schema.era26-schema-defs :as e26sd]
   [plumcp.core.test.test-support :as ts]
   [plumcp.core.util :as u]))


(deftest ^:async server-discover-test
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
                            (rt/?server-info (es/make-info "Test server" "0.1.0"))
                            (rt/?server-capabilities server-capabilities)
                            (rt/?server-instructions "Test server instructions")
                            e26im/server-discover
                            u/do-await)]
    (testing "server/discover"
      (is (= {:result {:ttlMs 3600000,
                       :cacheScope "public",
                       :supportedVersions ["2026-07-28"],
                       :capabilities {},
                       :resultType "complete",
                       :instructions "Test server instructions"
                       :_meta {:io.modelcontextprotocol/serverInfo {:name "Test server"
                                                                    :version "0.1.0"}}}}
             discover-result)))))


(deftest ^:async skills-test
  (let [;; -- skill entities --
        resource-1 (e26eg/make-skill-resource
                    {:uri "skill://greeting/hello.md"
                     :digest "a591a6d40bf420404a011733cfb7b190"
                     :size 10})
        frontmatter (e26eg/make-skill-frontmatter
                     {:name "Hello world"
                      :description "Greeting skill - Hello World"})
        skill-1 (e26eg/make-skill
                 {:uri "skill://greeting/hello.md"
                  :frontmatter frontmatter
                  :resources [resource-1]})
        skill-1-handler (fn [{:keys [uri]}]
                          {:skill skill-1})
        skill-items (-> [(cap/make-skill-item skill-1 skill-1-handler)]
                        ic/make-skills-capability)
        ;; -- plumbing --
        server-info (es/make-info "Test server" "0.1.0")
        client-capabilities (e26eg/make-client-capabilities)
        server-capabilities (e26eg/make-server-capabilities
                             {:skills skill-items})
        request-meta-object (e26eg/make-request-meta-object
                             {:protocol-version e26sd/protocol-version-max
                              :client-capabilities client-capabilities})]
    (testing "skills/list"
      (let [skills-list-request (e26eg/make-list-skills-request
                                 {:id (eg/make-id)
                                  :request-meta-object request-meta-object})
            skills-list-result (-> skills-list-request
                                   (rt/?server-info server-info)
                                   (rt/?server-capabilities server-capabilities)
                                   e26im/skills-list
                                   u/do-await)]
        (is (ts/malli-validate e26sd/ListSkillsResult
                               (:result skills-list-result)))))
    (testing "skills/get"
      (let [skills-get-request (e26eg/make-get-skill-request
                                {:uri "skill://greeting/hello.md"
                                 :request-meta-object request-meta-object})
            skills-get-result (-> skills-get-request
                                  (rt/?server-info server-info)
                                  (rt/?server-capabilities server-capabilities)
                                  e26im/skills-get
                                  u/do-await)]
        (is (ts/malli-validate e26sd/GetSkillResult
                               (:result skills-get-result)))))))
