;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util.auth-util
  "Auth specific utility functions and macros."
  (:require [clojure.set :as set]
            [clojure.string :as str]
            [plumcp.core.util :as u])
  #?(:cljs (:require-macros [plumcp.core.util :refer []])))


(defn now-seconds
  "Return the number of seconds elapsed since epoch."
  ^long []
  (-> (u/now-millis)
      (/ 1000)
      long))


;; JWT util


(def ^:const ^long default-clock-skew-seconds 5)


(defn claims-expired?
  "Given JWT claims (map with string keys) return true if claims are
   expired, false otherwise or nil if no info available."
  ([claims ^long now-epoch-seconds ^long clock-skew-seconds]
   (when-let [exp (get claims "exp")]  ; expires-at
     (or (not (number? exp))
         (>= now-epoch-seconds (+ (long exp) clock-skew-seconds)))))
  ([claims]
   (claims-expired? claims
                    (now-seconds) default-clock-skew-seconds)))


(defn claims-not-yet-valid?
  "Given JWT claims (map with string keys) return true if the claims are
   not yet valid, false otherwise or nil if no info available."
  ([claims ^long now-epoch-seconds ^long clock-skew-seconds]
   (when-let [nbf (get claims "nbf")]  ; not-before
     (and (number? nbf)
          (< now-epoch-seconds (- (long nbf) clock-skew-seconds)))))
  ([claims]
   (claims-not-yet-valid? claims
                          (now-seconds) default-clock-skew-seconds)))


(defn claims-issued-in-future?
  "Given JWT claims (map with string keys) return true if the claims are
   issued in future, false otherwise or nil if no info available."
  ([claims ^long now-epoch-seconds ^long clock-skew-seconds]
   (when-let [iat (get claims "iat")]  ; issued-at
     (and (number? iat)
          (> (long iat) (+ now-epoch-seconds clock-skew-seconds)))))
  ([claims]
   (claims-issued-in-future? claims
                             (now-seconds) default-clock-skew-seconds)))


(defn claims-validate-time
  "Validate JWT claims (map with string keys) for time, returning
   {:valid? boolean
    :reason failure-reason}"
  [claims]
  (cond
    (claims-expired? claims) {:valid? false
                              :reason :expired}
    (claims-not-yet-valid? claims) {:valid? false
                                    :reason :not-yet-valid}
    (claims-issued-in-future? claims) {:valid? false
                                       :reason :issued-in-future}
    :else {:valid? true}))


(defn claims-validate-issuer
  "Validate JWT claims (map with string keys) for issuer, returning
   {:valid? boolean
    :reason failure-reason}"
  [claims valid-issuer-set]
  (if-let [issuer (get claims "iss")]  ; issuer
    (if (contains? valid-issuer-set issuer)
      {:valid? true}
      {:valid? false
       :reason :issuer-is-invalid})
    {:valid? false
     :reason :issuer-not-found}))


(defn claims-validate-audience
  "Validate JWT claims (map with string keys) for audience, returning
   {:valid? boolean
    :reason failure-reason}"
  [claims valid-audience-set]
  (if-let [audience (get claims "aud")]  ; audience (string or array)
    (let [audience-set (cond
                         (string? audience) #{audience}
                         (sequential? audience) (set audience)
                         :else #{})]
      (if (not-empty (set/intersection valid-audience-set
                                       audience-set))
        {:valid? true}
        {:valid? false
         :reason :audience-not-matching}))
    {:valid? false
     :reason :audience-not-found}))


(defn default-claims->scope-set
  "Extract scope set from given JWT claims map. Since the scope key in a
   JWT claims map is not standardized, an identity provider is free to
   use any key they like. Hence, the scope extraction is speculative and
   works on a best effort basis. You may want to use a specific logic to
   extract scope for your identity provider."
  [claims]
  (let [scope (get claims "scope")
        scp (get claims "scp")
        token-set (fn [s] (-> s
                              (str/split #" +")
                              set))]
    (cond
      (string? scope) (token-set scope)
      (string? scp) (token-set scp)
      (sequential? scope) (set scope)
      (sequential? scp) (set scp)
      :else #{})))


(defn claims-validate-scope
  "Validate JWT claims (map with string keys) for scope, returning
   {:valid? boolean
    :reason failure-reason}"
  [claims required-scope-set
   ^{:see [default-claims->scope-set]} claims->scope-set]
  (let [granted-scope-set (claims->scope-set claims)]
    (if (set/subset? required-scope-set granted-scope-set)
      {:valid? true}
      {:valid? false
       :reason :insufficient-scope})))
