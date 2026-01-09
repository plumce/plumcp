(ns plumcp.core.util
  "Common (CLJ, CLJS) utility functions and macros"
  (:require
   #?(:cljs [goog.string :as gstring])
   #?(:cljs [goog.string.format])
   #?(:cljs [plumcp.core.util-cljs :as us])
   #?(:clj [plumcp.core.util.json :as json])
   [clojure.pprint :as pp]
   [clojure.string :as str])
  #?(:cljs (:require-macros [plumcp.core.util])
     :clj (:import
           [clojure.lang ExceptionInfo]
           [java.text SimpleDateFormat]
           [java.util Base64 Date TimeZone])))


;; --- Backfill ---


#?(:cljs
   (defn format [fstr & args]
     (apply gstring/format fstr args)))


#?(:cljs
   (defn bytes?
     "Return true if x is a byte array."
     [x]
     (instance? js/Uint8Array x)))


;; --- Coercion/transformation ---


(defn as-vec [x]
  (if (or (counted? x)
          (list? x)
          (set? x))
    (vec x)
    [x]))


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


;; --- common predicates ---


(defn non-empty-string?
  "Return true if argument is a non-empty string, false otherwise."
  [s]
  (and (string? s)
       (seq s)))


(defn non-empty-map?
  "Return true if argument is a non-empty map, false otherwise."
  [m]
  (and (map? m)
       (seq m)))


(defn non-empty-vector?
  "Return true if argument is a non-empty vector, false otherwise."
  [v]
  (and (vector? v)
       (seq v)))


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


;; --- Function invocation ---


(def nop "Do nothing at all, returning nil."
  (constantly nil))


(defn invoke
  "Invoke given function. May be useful with `->` threading macro usage."
  ([f]
   (f))
  ([f arg]
   (f arg))
  ([f arg & more]
   (apply f arg more)))


(defn only-when
  "Return argument only when `(pred x)` returns truthy, `nil` otherwise."
  [pred x]
  (when (pred x)
    x))


;; --- Evaluate body of code ---


(defn dotee
  "An alternative to `clojure.core/doto` that behaves like the `tee`
   Unix command by invoking `(f x & args)` before returning `x`."
  ([x f]
   (f x)
   x)
  ([x f & args]
   (apply f x args)
   x))


;; --- Printing ---


(defn pprint-str
  "Return pretty-printed data as a string."
  [data]
  (if (string? data)
    data
    (with-out-str (pp/pprint data))))


(defn err
  "Print to (STD)ERR using the printer fn and args."
  [printer-f & args]
  (let [s (with-out-str (apply printer-f args))]
    #?(:cljs (if us/env-node-js?
               (.write js/process.stderr s) ; print no extra newline
               (js/console.error s))
       :clj (binding [*out* *err*]
              (print s)
              (flush)))))


(defn eprn
  "Like `prn`, but to STDERR."
  [& args]
  (apply err prn args))


(defn eprintln
  "Like `println`, but to STDERR."
  [& args]
  (apply err println args))


(defn epprint
  "Like clojure.pprint/pprint, but to STDERR."
  [arg]
  (err pp/pprint arg))


(defn dprint
  "Pretty-print for debugging."
  [header data]
  (let [h-line (repeat-str (count header) "-")
        e-line (repeat-str (count header) "~")]
    (eprintln h-line)
    (eprintln header)
    (eprintln h-line)
    (epprint data)
    (eprintln e-line)
    (eprintln)))


(defn print-stack-trace
  [e]
  (eprintln e)
  #?(:cljs (js/console.error e.stack)  ;(.trace js/console)
     :clj (.printStackTrace ^Throwable e)))


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


;; --- JSON codec ---


(defn json-parse
  "Parse JSON string as data. Map keys are converted into keywords.
   See: json-parse-str"
  [json-str]
  #?(:cljs (-> (.parse js/JSON json-str)
               (js->clj :keywordize-keys true))
     :clj  (json/json-parse json-str)))


(defn json-parse-str
  "Parse JSON string as data.
   See: json-parse"
  [json-str]
  #?(:cljs (-> (.parse js/JSON json-str)
               (js->clj))
     :clj  (json/json-parse-str json-str)))


(defn json-write
  "Emit JSON string from given data."
  [data]
  #?(:cljs (.stringify js/JSON
                       (clj->js data))
     :clj  (json/json-write data)))


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


;; --- SSE utility fns ---


(defn chunkify-string-lines
  "Given a sequence of string lines, return a sequence of chunks of
   non-empty string lines. Every chunk is a collection of non-empty
   string lines."
  [string-lines]
  (let [splitter (comp boolean seq)]
    (->> string-lines
         (partition-by splitter)
         (filter #(-> % first seq)))))


(defn parse-sse-event-lines
  "Match the given lines-of-string against the following pattern:
   ```
   event: message
   id: <id> (optional)
   data: <string>
   ```
   Return the data-string if found, nil otherwise."
  [event-lines]
  (when (= "event: message" (first event-lines))
    (when-some [data-line (some #(when (str/starts-with? % "data:") %)
                                event-lines)]
      (-> (subs data-line 5)
          str/trim))))


(defn make-sse-event-string
  "Generate SSE event string from given `event-id` and `data-string`."
  [event-id data]
  (str "event: message\nid: " event-id
       "\ndata: " (json-write data) "\n\n"))


;; --- Var discovery ---


;; Adapted from:
;; https://ask.clojure.org/index.php/10965/how-do-you-access-namespace-values-in-a-macro?show=10979#a10979
#?(:clj
   (defmacro find-vars
     "Find vars from given (or current) namespace or namespace symbol.
      Caution: Being a macro, it finds vars at the call-site - take
               care when calling this macro within a function."
     ([ns-or-sym]
      (let [syms (if (:ns &env) ;; :ns only exists in CLJS
                   ;; cljs.env require at ns-level needs CLJS dependency
                   ;; undesirable for CLJ apps, so use requiring-resolve
                   (let [ns-sym (if (symbol? ns-or-sym)
                                  ns-or-sym
                                  (ns-name ns-or-sym))]
                     (-> (requiring-resolve 'cljs.env/*compiler*)
                         deref deref  ; deref var, then deref atom
                         (get-in [:cljs.analyzer/namespaces
                                  ns-sym
                                  :defs])
                         keys
                         (->> (mapv #(symbol (str ns-sym "/" %))))))
                   (mapv symbol (-> (if (symbol? ns-or-sym)
                                      (the-ns ns-or-sym)
                                      ns-or-sym)
                                    ns-publics
                                    vals)))
            form (->> syms
                      (mapv #(list 'var %)))]
        `[~@form]))
     ([]
      `(find-vars ~*ns*))))
