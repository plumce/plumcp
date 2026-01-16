;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.apps.weather
  (:require
   [clojure.string :as str]
   [plumcp.core.impl.var-support :as vs]
   [plumcp.core.support.http-client :as hc]
   [plumcp.core.util :as u :refer [#?(:cljs format)]]
   [plumcp.core.util.async-bridge :as uab]))


(def nws-api-base "https://api.weather.gov")
(def user-agent "weather-app/1.0")


(defn make-nws-request
  "Make a request to NWS API"
  [url]
  (uab/as-async [return reject]
    (hc/with-http-client [client [url]]
      (uab/let-await
        [response (hc/http-get client
                               {:headers
                                {"User-Agent" user-agent
                                 "Accept" "application/geo+json"}})]
        (if (= 200 (:status response))
          (uab/let-await [data (-> (:on-msg response)
                                   (u/invoke u/json-parse-str))]
            (return data))
          (do
            (reject (ex-info "Error calling NWS API" response))
            (u/throw! "Error calling NWS API" response)))))))


(defn format-alert
  "Format an alert feature into a readable string."
  [feature-map]
  (let [props (get feature-map "properties")]
    (format "
Event: %s
Area: %s
Severity: %s
Description: %s
Instructions: %s
"
            (get props "event" "Unknown")
            (get props "areaDesc" "Unknown")
            (get props "severity" "Unknown")
            (get props "description" "No description available")
            (get props "instruction" "No specific instructions"))))


(defn get-alerts
  [state]
  (let [url (str nws-api-base "/alerts/active/area/" state)]
    (uab/let-await [data (make-nws-request url)]
      (if-let [features (get data "features")]
        (->> features
             (map format-alert)
             (str/join "\n---\n"))
        (if data
          "No active alerts for this state."
          "Unable to fetch alerts or no alerts found.")))))


(defn ^{:mcp-name "get_alerts"
        :mcp-type :tool} tool-get-alerts
  "Get weather alerts for a US state.
     
  Args:
    state: Two-letter US state code (e.g. CA, NY)"
  [{:keys [^{:type "string"
             :doc "Two-letter US state code (e.g. CA, NY)"} state]}]
  (-> (get-alerts state)
      (u/dotee #(u/eprintln "[tool-get-alerts]" %))))


(defn get-forecast
  [latitude longitude]
  (let [points-url (format "%s/points/%s,%s" nws-api-base latitude longitude)]
    (uab/let-await [points-data (make-nws-request points-url)]
      (if points-data
        (let [forecast-url (get-in points-data ["properties" "forecast"])]
          (uab/let-await [forecast-data (make-nws-request forecast-url)]
            (if forecast-data
              (let [periods (get-in forecast-data ["properties" "periods"])]
                (->> (take 5 periods)
                     (map (fn [each-period]
                            (format "
                  %s:
                  Temperature: %s°%s
                  Wind: %s %s
                  Forecast: %s
                  "
                                    (get each-period "name")
                                    (get each-period "temperature") (get each-period "temperatureUnit")
                                    (get each-period "windSpeed") (get each-period "windDirection")
                                    (get each-period "detailedForecast"))))
                     (str/join "\n---\n")))
              "Unable to fetch detailed forecast.")))
        "Unable to fetch forecast data for this location."))))


(defn ^{:mcp-name "get_forecast"
        :mcp-type :tool} tool-get-forecast
  "Get weather forecast for a location.
     
  Args:
    latitude: Latitude of the location
    longitude: Longitude of the location"
  [{:keys [^{:type "number"
             :doc "Latitude of the location"} latitude
           ^{:type "number"
             :doc "Longitude of the location"} longitude]}]
  (get-forecast latitude longitude))


(def tools
  [(vs/make-tool-from-var #'tool-get-alerts)
   (vs/make-tool-from-var #'tool-get-forecast)])


(def mcp-primitives (-> (u/find-vars)
                        (vs/vars->server-primitives)))
