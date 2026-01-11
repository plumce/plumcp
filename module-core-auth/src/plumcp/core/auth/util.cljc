(ns plumcp.core.auth.util
  "Cross-platform Auth utility functions."
  (:require #?(:cljs [plumcp.core.auth.util-cljs :as au]
               :clj [plumcp.core.auth.util-java :as au])))


(def validate-jwt
  "Given JSON Web keys (JWKS) as a JSON string and JWT, return the
   decoded claims as a map if the JWT is valid, throw exception
   otherwise."
  au/validate-jwt)
