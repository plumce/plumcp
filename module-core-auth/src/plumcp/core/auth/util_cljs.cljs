(ns plumcp.core.auth.util-cljs
  "CLJS Auth utility functions."
  (:require
   ["jose" :as jose]
   [clojure.string :as str]
   [plumcp.core.util :as u]
   [plumcp.core.util.async-bridge :as uab]))


;; Adapted from:
;; https://github.com/scalekit-inc/scalekit-sdk-node/blob/v2.1.6/src/scalekit.ts#L388
(defn extract-scopes-from-payload
  "Get scopes from payload and return a clean vector of string."
  [payload]
  (let [scopes (aget payload "scopes")]
    (if (.isArray js/Array scopes)
      (->> (seq scopes)
           (mapv str/trim)
           (filterv not-empty))
      [])))


;; Adapted from:
;; https://github.com/scalekit-inc/scalekit-sdk-node/blob/v2.1.6/src/scalekit.ts#L369
(defn verify-scopes
  "Return true if the token covers the required scopes, throw exception
   otherwise."
  [jwt required-scopes]
  (let [payload (.decodeJwt jose jwt)
        scopes (extract-scopes-from-payload payload)
        scope-set (set scopes)
        missing-scopes (->> required-scopes
                            (remove #(contains? scope-set %))
                            vec)]
    (when (seq missing-scopes)
      (->> missing-scopes
           (str/join ", ")
           (str "Token is missing required scopes: ")
           (u/throw!)))
    true))


;; Adapted from:
;; https://github.com/scalekit-inc/scalekit-sdk-node/blob/v2.1.6/src/scalekit.ts#L340
(defn validate-jwt
  "Given JSON Web keys (JWKS) as a JSON string and JWT, return the
   decoded claims as a map if the JWT is valid, throw exception
   otherwise."
  [^String jwks-json-str ^String jwt & {:keys [issuer
                                               audience
                                               required-scopes]
                                        :or {required-scopes []}}]
  (let [jwks (.createLocalJWKSet jose #js{:keys (-> jwks-json-str
                                                    u/json-parse-str
                                                    (get "keys")
                                                    clj->js)})]
    (try
      (uab/let-await
        [result (.jwtVerify jose jwt jwks
                            (-> {}
                                (u/assoc-some :issuer issuer
                                              :audience audience)
                                clj->js))]
        (let [payload (aget result "payload")]  ; payload is claims
          (when (seq required-scopes)
            (verify-scopes jwt required-scopes))
          payload))
      (catch js/Error error
        (throw (ex-info (str "Failed to validate token and get claims: "
                             (ex-message error))
                        {:jwt jwt}
                        error))))))
