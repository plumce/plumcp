(ns plumcp.core.auth.util-java
  "Java/JVM specific Auth utility functions."
  (:import [org.jose4j.jwk JsonWebKey JsonWebKeySet VerificationJwkSelector]
           [org.jose4j.jws JsonWebSignature]
           [org.jose4j.jwt JwtClaims]
           [org.jose4j.jwt.consumer JwtConsumer JwtConsumerBuilder]))


;; Adapted from:
;; https://github.com/scalekit-inc/scalekit-sdk-java/blob/v2.0.7/src/main/java/com/scalekit/api/impl/ScalekitAuthClient.java#L188
(defn validate-jwt
  "Given JSON Web keys (JWKS) as a JSON string and JWT, return the
   decoded claims as a map if the JWT is valid, throw exception
   otherwise."
  [^String jwks-json-str ^String jwt]
  (try
    (let [^JsonWebKeySet json-web-key-set (JsonWebKeySet. jwks-json-str)
          ^JsonWebSignature jws (doto (JsonWebSignature.)
                                  (.setCompactSerialization jwt))
          ^VerificationJwkSelector jwk-selector (VerificationJwkSelector.)
          ^JsonWebKey jwk (->> (.getJsonWebKeys json-web-key-set)
                               (.select jwk-selector jws))]
      (->> (.getKey jwk)
           (.setKey jws))
      ;; verify the signature
      (when (.verifySignature jws)  ; i.e. when this returns true
        ;; verify the expiry and get claims
        (let [^JwtConsumer
              jwt-consumer (-> (JwtConsumerBuilder.)
                               (.setRequireExpirationTime)
                               (.setAllowedClockSkewInSeconds 30)
                               (.setSkipSignatureVerification) ; Already verified above
                               (.setSkipDefaultAudienceValidation)
                               (.build))
              ;; it (below) throws an exception if the token is expired
              ^JwtClaims jwt-claims (.processToClaims jwt-consumer jwt)]
          (.getClaimsMap jwt-claims))))
    (catch Exception e
      (throw (ex-info (str "Failed to validate token and get claims: "
                           (.getMessage e))
                      {:jwt jwt}
                      e)))))
