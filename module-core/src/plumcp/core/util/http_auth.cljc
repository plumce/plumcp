;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util.http-auth
  "OAuth integration support utility functions for Streamable HTTP
   client transport."
  (:require
   #?(:cljs [clojure.string :as str])
   #?(:cljs [plumcp.core.util.async-bridge :as uab])
   [plumcp.core.schema.schema-defs :as sd]
   [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.util.http-auth])
     :clj (:import
           [java.nio.charset StandardCharsets]
           [java.security MessageDigest SecureRandom]
           [java.util Base64 Base64$Encoder])))


;; OpenID config is NOT in 2025-06-18 spec - it is still in Draft now
(def uri-openid-configuration "/.well-known/openid-configuration")


(defn well-known-authorization-server
  "Given a vector of authorization-server URLs, return the well-known
   OAuth authorization server URL."
  [authorization-servers]
  (-> (first authorization-servers)
      (u/inject-uri-prefix sd/uri-oauth-authorization-server)))


;; PKCE util
;;
;; Java version (adapted from):
;; https://www.appsdeveloperblog.com/pkce-code-verifier-and-code-challenge-in-java
;;
;; JavaScript version (adapted from):
;; https://stackoverflow.com/a/63336562


(defn make-code-verifier
  []
  #?(:cljs (let [array (js/Uint32Array. 28)
                 dec2hex (fn [dec-array]
                           (-> (str "0" (.toString dec-array 16))
                               (.substr -2)))]
             (js/crypto.getRandomValues array)
             (-> (js/Array.from array dec2hex)
                 (.join "")
                 (doto prn)))
     :clj (let [^SecureRandom secure-random (SecureRandom.)
                ^bytes code-verifier (byte-array 32)]
            (.nextBytes secure-random code-verifier)
            (-> ^Base64$Encoder (Base64/getUrlEncoder)
                (.withoutPadding)
                (.encodeToString code-verifier)))))


(defn with-code-challenge*
  "Make code-challenge using SHA-256 and call `(f digest-string)`. In
   CLJS the fn-call happens in a promise."
  [^String code-verifier f]
  #?(:cljs (let [sha256 (fn [plain]
                          (let [encoder (js/TextEncoder.)
                                data (.encode encoder plain)]
                            (-> js/crypto.subtle
                                (.digest "SHA-256" data))))
                 b64url-encode (fn [a]
                                 (-> (->> (js/Uint8Array. a)
                                          (map #(js/String.fromCharCode %))
                                          (str/join ""))
                                     (js/btoa)
                                     (str/replace "+" "-")
                                     (str/replace "/" "_")
                                     (str/replace #"=+$" "")))]
             (uab/let-await [hashed (sha256 code-verifier)]
               (-> (b64url-encode hashed)
                   (doto prn)
                   (f))))
     :clj (let [cv-bytes (->> (.toString StandardCharsets/UTF_8)
                              (.getBytes code-verifier))
                ^MessageDigest
                message-digest (MessageDigest/getInstance "SHA-256")]
            (.update message-digest cv-bytes)
            (-> ^Base64$Encoder (Base64/getUrlEncoder)
                (.withoutPadding)
                (.encodeToString (.digest message-digest))
                (f)))))


(defmacro with-code-verifier-challenge
  [[code-challenge code-verifier] & body]
  `(with-code-challenge* ~code-verifier
     (fn [~code-challenge]
       ~@body)))
