;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.auth.util
  "Cross-platform Auth utility functions."
  (:require #?(:cljs [plumcp.core.auth.util-cljs :as au]
               :clj [plumcp.core.auth.util-java :as au])))


(def validate-jwt
  "Given JSON Web keys (JWKS) as a JSON string and JWT, return the
   decoded claims as a map if the JWT is valid, throw exception
   otherwise."
  au/validate-jwt)
