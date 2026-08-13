;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.server.http-ring-transport-auth
  "Auth helpers for the Server HTTP Ring transport."
  (:require [clojure.string :as str]
            [plumcp.core.util :as u :refer [#?(:cljs format)]]
            [plumcp.core.util.auth-util :as uau]))


;; ----- JWT Token/claims Verification responses -----


(defn auth-error-headers
  "Return the `WWW-Authenticate` header name/value pair."
  [resource-metadata required-scope-set extra-attrs]
  (let [base (-> {"realm" "OAuth"
                  "resource_metadata" resource-metadata}
                 (merge extra-attrs))
        scope (some->> required-scope-set
                       seq  ; may produce nil
                       (str/join " "))]
    {"WWW-Authenticate"
     (str "Bearer "
          (->> (u/assoc-some base "scope" scope)
               seq  ; turn map into a sequence
               (map (fn [[k v]]
                      (format "%s=\"%s\"" k v)))
               (str/join " ")))}))


(defn auth-error-response
  [status {:keys [resource-metadata required-scope-set wwwa-attrs
                  error error-detail]}]
  {:status status
   :headers (as-> wwwa-attrs $
              (u/assoc-some $
                            "error" error
                            "error_description" error-detail)
              (auth-error-headers resource-metadata required-scope-set $))
   :body {:error error
          :error-description error-detail}})


(defn invalid-token-response
  [resource-metadata required-scope-set error-detail
   & {:keys [status
             error]
      :or {status 401
           error "invalid_token"}}]
  (->> {:resource-metadata resource-metadata
        :scope-set required-scope-set
        :wwwa-attrs {}
        :error error
        :error-detail error-detail}
       (auth-error-response status)))


(defn induce-verify-expiry
  [claims resource-metadata required-scope-set]
  (let [{:keys [valid? reason]} (uau/claims-validate-time claims)]
    (if valid?
      claims
      (let [invalid-time (fn [error-detail]
                           (invalid-token-response resource-metadata
                                                   required-scope-set
                                                   error-detail))]
        (-> "JWT Claims:Time/Expiry Validation - [%s] found"
            (format (str reason))
            (u/dprint claims))
        (-> reason
            (case
             :expired (invalid-time "The access token expired")
             :not-yet-valid (invalid-time
                             "The access token is not valid yet")
             :issued-in-future (invalid-time
                                "The access token is issued in the future")
             (invalid-time "Unknown"))
            reduced)))))


(defn induce-verify-issuer
  [claims valid-issuer-set resource-metadata required-scope-set]
  (let [{:keys [valid? reason]} (uau/claims-validate-issuer claims
                                                            valid-issuer-set)]
    (if valid?
      claims
      (let [invalid-issuer (fn [error-detail]
                             (invalid-token-response resource-metadata
                                                     required-scope-set
                                                     error-detail))]
        (-> "JWT Claims:Issuer Validation - [%s] found"
            (format (str reason))
            (u/dprint claims))
        (-> reason
            (case
             :issuer-is-invalid (invalid-issuer "Issuer is invalid")
             :issuer-not-found (invalid-issuer "Issuer is not found")
             (invalid-issuer "Unknown"))
            reduced)))))


(defn induce-verify-audience
  [claims valid-audience-set resource-metadata required-scope-set]
  (let [{:keys [valid? reason]} (uau/claims-validate-audience
                                 claims
                                 valid-audience-set)]
    (if valid?
      claims
      (let [invalid-audience (fn [error-detail]
                               (invalid-token-response
                                resource-metadata
                                required-scope-set
                                error-detail))]
        (-> "JWT Claims:Audience Validation - [%s] found"
            (format (str reason))
            (u/dprint claims))
        (-> reason
            (case
             :audience-not-matching (invalid-audience
                                     "Audience is not matching")
             :audience-not-found (invalid-audience
                                  "Audience is not found")
             (invalid-audience "Unknown"))
            reduced)))))


(defn induce-verify-scopes
  [claims claims->scope-set resource-metadata required-scope-set]
  (let [{:keys [valid? reason]} (uau/claims-validate-scope
                                 claims
                                 required-scope-set
                                 claims->scope-set)]
    (when-not valid?
      (-> "JWT Claims:Scope Validation - [%s] found"
          (format (str reason))
          (u/dprint claims)))
    (if valid?
      claims
      (-> reason
          (case
           :insufficient-scope (invalid-token-response
                                resource-metadata
                                required-scope-set
                                "Additional permissions are required"
                                {:status 403
                                 :error "insufficient_scope"})
           (invalid-token-response resource-metadata
                                   required-scope-set
                                   "Additional permissions are required"
                                   {:status 403
                                    :error "insufficient_scope"}))
          reduced))))
