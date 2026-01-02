(ns plumcp.core.util
  "Common (CLJ, CLJS) utility functions and macros"
  (:require
   [clojure.string :as str])
  #?(:clj (:import
           [clojure.lang ExceptionInfo]
           [java.text SimpleDateFormat]
           [java.util Base64 Date TimeZone])))


;; --- Backfill ---


#?(:cljs
   (defn bytes?
     "Return true if x is a byte array."
     [x]
     (instance? js/Uint8Array x)))


;; --- Type coercion ---


(defn as-str
  "Turn given argument (by reading name if named entity) into a string."
  [x]
  (if (keyword? x)
    (if-let [the-ns (namespace x)]
      (str the-ns "/" (name x))
      (name x))
    (str x)))


(defn repeat-str
  "Repeat given string N times, returning a single string."
  [^long n token]
  (if (pos? n)
    (-> (repeat n token)
        str/join)
    ""))


(defn hex-str
  "Return Hexadecimal string representation of the given number."
  ([num]
   #?(:cljs (.toString num 16)
      :clj (format "%x" num)))
  ([num len]
   #?(:cljs (let [hs (.toString num 16)]
              (str (repeat-str (- len (count hs)) "0")
                   hs))
      :clj (-> (str "%0" len "x")
               (format num)))))


;; --- String pruning ---


(defn stripl
  "Remove specified token from the left side of string."
  [s token]
  (if (str/starts-with? s token)
    (subs s (count token))
    s))


(defn stripr
  "Remove specified token from the right side of string."
  [s token]
  (if (str/ends-with? s token)
    (subs s 0 (- (count s)
                 (count token)))
    s))


;; --- Map manipulation ---


(defn assoc-missing
  "Like clojure.core/assoc, except it assoc's only when the key does not
   exist."
  ([m k v]
   (if (contains? m k)
     m
     (assoc m k v)))
  ([m k v & more]
   (->> (partition 2 more)
        (reduce (fn [m [k v]]
                  (assoc-missing m k v))
                (assoc-missing m k v)))))


(defn assoc-some
  "Like clojure.core/assoc, except it assoc's only when value is not nil."
  ([m k v]
   (if (some? v)
     (assoc m k v)
     m))
  ([m k v & more]
   (->> (partition 2 more)
        (reduce (fn [m [k v]]
                  (assoc-some m k v))
                (assoc-some m k v)))))


(defn copy-keys
  "Copy specified keys from source (map) to destination (map)."
  [map-dest map-src keyseq]
  (->> keyseq
       (reduce (fn [m k]
                 (if (contains? map-src k)
                   (assoc m k (get map-src k))
                   m))
               map-dest)))


;; --- Exception convenience ---


(defmacro catch!
  "Evaluate given body of code, returning `[result nil]` on success,
   or `[nil exception]` in case any exception is caught."
  [& body]
  (if (:ns &env) ;; :ns only exists in CLJS
    `(try
       [(do ~@body) nil]
       (catch js/Error ex#
         [nil ex#]))
    `(try
       [(do ~@body) nil]
       (catch Exception ex#
         [nil ex#]))))


(defn throw!
  "Throw exception using clojure.core/ex-info."
  ([message]
   (throw (ex-info message {})))
  ([message data]
   (throw (ex-info message data)))
  ([message data cause]
   (throw (ex-info message data cause))))


(defn ex-info-parts
  "Return `[ex-message ex-data]` if argument is an `ex-info` exception,
   `nil` otherwise."
  [ex]
  (when (instance? ExceptionInfo ex)
    [(ex-message ex)
     (ex-data ex)
     (ex-cause ex)]))


;; --- Expectation assertion ---


(defn expected!
  "Throw an 'Expected <message>' exception with {:found <found>} data."
  ([found message]
   (throw! (str "Expected " message) {:found found}))
  ([found pred message]
   (if (pred found)
     found
     (expected! found message))))


(defn expected-enum!
  "Throw an 'Expected value to be either of <...>' exception with
   {:found <found>} data. The `enum-set-or-map` argument must be a
   set or a map."
  [found enum-set-or-map]
  (expected! found
             #(contains? enum-set-or-map %)
             (str "value to be either of " (if (map? enum-set-or-map)
                                             (keys enum-set-or-map)
                                             enum-set-or-map))))


;; --- Time tracking ---


(defn now-millis
  "Return the current time since Epoch (or specified TS) in milliseconds."
  (^long []
   #?(:clj (System/currentTimeMillis)
      :cljs (.getTime (js/Date.))))
  (^long [^long since-millis]
   (- (now-millis) since-millis)))


(defn now-iso8601-utc
  "Return the current time in UTC timezone as ISO 8601 string."
  []
  #?(:clj (let [tz (TimeZone/getTimeZone "UTC")
                df (SimpleDateFormat. "yyyy-MM-dd'T'HH:mm'Z'")]
            (.setTimeZone df tz)
            (.format df (Date.)))
     :cljs (-> (js/Date.)
               (.toISOString))))


;; --- UUID generation ---


(defn uuid-v4
  "Return a random UUID-v4 string."
  []
  (str (random-uuid)))


(defn uuid-v7
  "Generate a random UUID-v7 (time ordered) string.
   Ref: https://gist.github.com/fabiolimace/c725349dd34aedc7b69867dabec59c08
   See: https://gist.github.com/fabiolimace/c0c11c5ea013d4ec54cf6b0d43d366c6"
  []
  (let [random (fn [^long bits]
                 (let [bits (min 52 bits)]
                   #?(:cljs (-> (js/Math.random)
                                (* (js/Math.pow 2 bits))
                                js/Math.floor)
                      :clj (-> (Math/random)
                               (* (Math/pow 2 bits))
                               Math/floor  ; returns double
                               long))))
        millis (now-millis)
        time0x (hex-str millis 12)
        rawstr (str (subs time0x 0 8)
                    \-
                    (subs time0x 8 12)
                    \-
                    (hex-str (random 16) 4)
                    \-
                    (hex-str (random 16) 4)
                    \-
                    (hex-str (random 48) 12))]
    (str (subs rawstr 0 14)
         \7  ; version
         (subs rawstr 15 19)
         (rand-nth [\8 \9 \a \b])  ; variant
         (subs rawstr 20))))


;; --- Base64 conversion ---


(defn str->byte-array
  [^String s]
  (when (string? s)
    #?(:cljs (let [encoder (js/TextEncoder.)]
               (.encode encoder s))
       :clj (.getBytes s))))


(defn bytes->base64-string ^String [^bytes raw-bytes]
  #?(:cljs (let [decoder (js/TextDecoder. "utf8")]
             (-> (.decode decoder raw-bytes)
                 js/btoa))
     :clj (-> (Base64/getEncoder)
              (.encodeToString raw-bytes))))


(defn base64-string->bytes ^bytes [^String base64-string]
  #?(:cljs (-> (js/atob base64-string)
               (.split "")
               (.map (fn [c] (.charCodeAt c 0)))
               (js/Uint8Array.))
     :clj (-> (Base64/getDecoder)
              (.decode base64-string))))


(defn as-base64-str [x x-name]
  (cond
    (string? x) x  ; assume Base64 string
    (bytes? x) (bytes->base64-string x)
    :else (expected! x (str x-name
                            " to be a Base64 string or byte-array"))))


;; --- URI-template handling ---


(defn uri-template->variable-names
  "Given a string URI-template with {param} e.g. `/foo/{id}/bar/{subId}`
   return a vector of arg names, e.g. `[\"id\" \"subId\"]`."
  [uri-template]
  (->> uri-template
       (re-seq #"\{([a-zA-Z0-9_]+)\}")
       (mapv second)))


(defn uri-template->matching-regex
  "Given a string URI-template with {param} e.g. /foo/{id}/bar/{subId}
   return `(fn [uri]) -> match-result`."
  [uri-template]
  (-> uri-template
      (str/replace #"\{([a-zA-Z0-9_]+)\}" "([a-zA-Z0-9_]+)")
      re-pattern))
