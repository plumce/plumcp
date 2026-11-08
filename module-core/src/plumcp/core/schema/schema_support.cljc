;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.schema.schema-support
  "Support and utility functions related to schema."
  (:require
   [plumcp.core.schema.era26-schema-defs :as e26sd]))


(defn era26-request?
  "Return true if given JSON-RPC request belongs to Era26, false otherwise."
  [{params :params
    :as jsonrpc-request}]
  (->> [:_meta e26sd/key-protocol-version]  ; request protocol version
       (get-in params)
       (contains? e26sd/protocol-versions-supported-set)))
