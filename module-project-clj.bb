(require '[clojure.pprint :as pp]
         '[clojure.string :as str]
         '[babashka.fs :as fs]
         '[selmer.parser :as sel])


(def module-version "0.1.0-SNAPSHOT")


(defn validate-module-dir-name!
  [module-dir]
  (let [prefix "module-core"]
    (when (not (str/starts-with? module-dir prefix))
      (throw (ex-info (str "Module dir must begin with '" prefix "'")
                      {:module-dir module-dir})))))


(defn validate-module-exists!
  [module-dir]
  (when (not (fs/directory? module-dir))
    (throw (ex-info "Module dir must be an existing directory"
                    {:module-dir module-dir}))))


(defn emit-project-clj
  [module-dir]
  (validate-module-dir-name! module-dir)
  (validate-module-exists! module-dir)
  (let [source-filename (str module-dir "/project-release.clj")
        source-template (slurp source-filename)
        projectclj-text (sel/render source-template {:version module-version})
        projectclj-path (str module-dir "/project.clj")]
    (spit projectclj-path projectclj-text)))


(defn erase-project-clj
  [module-dir]
  (validate-module-dir-name! module-dir)
  (let [projectclj-path (str module-dir "/project.clj")]
    (fs/delete projectclj-path)))


(defn list-module-dir-names
  []
  (let [module-dirs (->> (fs/list-dir ".")
                         (filter fs/directory?)
                         (map fs/file-name)
                         (filter #(str/starts-with? % "module-core"))
                         sort
                         vec)]
    (pp/pprint module-dirs)))


(defn eprintln
  [& args]
  (binding [*out* *err*]
    (apply println args)
    (flush)))


(defn help []
  (eprintln
   "Commands:
    emit <module-dir>  - over/write module project.clj
    erase <module-dir> - delete module project.clj
    list               - list module-dir names"))


(let [[command module-dir & _] *command-line-args*
      invoke-module-dir (fn [f]
                          (if (nil? module-dir)
                            (do
                              (eprintln "ERROR: Missing module-dir argument")
                              (help))
                            (f module-dir)))]
  (case command
    "emit" (invoke-module-dir emit-project-clj)
    "erase" (invoke-module-dir erase-project-clj)
    "list"  (list-module-dir-names)
    (do
      (eprintln "ERROR: Invalid command:" command)
      (help))))
