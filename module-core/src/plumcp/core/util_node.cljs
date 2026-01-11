(ns plumcp.core.util-node
  "Node.js specific utility functions."
  (:require
   ["child_process" :as cp]
   ["fs" :as fs]
   ["readline" :as readline]
   [plumcp.core.util-cljs :as us]))


(defn file-exists?
  "Return true if specified filename exists, false otherwise."
  [filename]
  (.existsSync fs filename))


(defn slurp-file
  "Read a file from the filesystem synchronously."
  [filename]
  (.readFileSync fs filename "utf8"))


(defn spit
  "Write out the file with specified content synchronously."
  [filename content]
  (.writeFileSync fs filename content))


(defn delete-file
  "Delete given file name synchronously."
  [filename]
  (.unlinkSync fs filename))


(defn process-input
  "Make a readline interface for console I/O."
  [prompt processor]
  (let [rl (-> readline
               (.createInterface #js{:input (.-stdin js/process)
                                     :output (.-stderr js/process)}))]
    (-> rl
        (.question prompt (fn [answer]
                            (processor answer)
                            (.close rl))))))


(def platform-opener
  "Platform-specific command or executable name to open a file/URL."
  (let [platform (.-platform js/process)]
    (case platform
      "darwin" "open"
      "win32" "start"  ; ["cmd" "/c" "start"]
      "xdg-open")))


(defn browse-url
  "Open given URL in browser, returning Node.js ChildProcess object.
   See: https://stackoverflow.com/a/49013356"
  ([url]
   (browse-url url (or (us/pget (.-env js/process) "PLUMCP_BROWSER")
                       platform-opener)))
  ([url browser-executable-name]
   (.exec cp (str browser-executable-name " '" url "'"))))
