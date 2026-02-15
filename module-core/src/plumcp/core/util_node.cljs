;   Copyright (c) Shantanu Kumar. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file LICENSE at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.


(ns plumcp.core.util-node
  "Node.js specific utility functions."
  (:require
   ["child_process" :as cp]
   ["fs" :as fs]
   ["path" :as path]
   ["readline" :as readline]
   [clojure.string :as str]
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


(defn env-val
  "Return the environment variable value if defined, nil otherwise."
  [env-var-name]
  (us/pget (.-env js/process) (str env-var-name)))


(defn exec-path
  "For given full command return short command if available in PATH,
   else return the full command as it is."
  [command]
  (let [syspath (env-val "PATH")
        pathsep (.-sep path)        ; '\' on Windows, '/' on Posix
        pathdel (.-delimiter path)  ; ';' on Windows, ':' on Posix
        exename (-> command
                    (str/split pathsep)
                    last)]
    (if (and (seq syspath)
             (->> (str/split syspath pathdel)
                  (some (fn [each-path]
                          (let [exepath (str each-path pathsep exename)]
                            (and (file-exists? exepath)
                                 ; realpathSync() subsumes readlinkSync()
                                 (= (.realpathSync fs exepath)
                                    command)))))))
      exename
      command)))


(defn rel-path
  "Return relative path from CWD for given arg (path)."
  [arg]
  (let [cwd (.cwd js/process)]
    (.relative path cwd arg)))


(def platform-opener
  "Platform-specific command or executable name to open a file/URL."
  (let [platform (.-platform js/process)]
    (case platform
      "darwin" "open"
      "win32" "start"  ; ["cmd" "/c" "start"]
      "xdg-open")))


(defn launch-process-group
  "Launch a process group indepent of Node. Used for running apps that
   fork their own child processes, e.g. browsers."
  ^ChildProcess [cmd args]
  (let [^ChildProcess
        proc (.spawn cp cmd
                     (clj->js args)
                     #js {; process group for tree-wide signal control
                          :detached true
                          ; remove IO ties to Node, so Node can exit
                          :stdio "ignore"})]
    (.unref proc)  ; as Node may not exit if busy tracking sub-process
    proc))


(defn kill-process-tree
  "Kill process tree. Similar to Clojure/JVM `(.destroy ^Process proc)`"
  [^ChildProcess subproc]
  (let [pid (.-pid subproc)]
    (if (= js/process.platform "win32")
      ;; Windows - "/T" kills process tree, "/F" by force
      (.spawn cp "taskkill"
              #js ["/PID" (str pid) "/T" "/F"])
      ;; Unix (Linux/macOS) - negative PID kills process tree
      (.kill js/process (- pid) "SIGTERM"))))


(defn browse-url
  "Open given URL in browser, returning Node.js ChildProcess object.
   See: https://stackoverflow.com/a/49013356"
  (^ChildProcess [url]
   (browse-url url (or (env-val "PLUMCP_BROWSER")
                       platform-opener)))
  (^ChildProcess [url browser-executable-name]
   (launch-process-group browser-executable-name [url])))
