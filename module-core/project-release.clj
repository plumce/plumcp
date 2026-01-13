(defproject io.github.plumce/plumcp.core "{{version}}"
  :description "Clojure/Script library for making MCP servers and clients"
  :url "https://github.com/plumce/plumcp"
  :license {:name "Eclipse Public License 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :global-vars {*warn-on-reflection* true
                *assert* true
                *unchecked-math* :warn-on-boxed}
  :resource-paths ["target/resources"]
  :dependencies []
  :plugins [[lein-project-edn "0.3.0"]]
  :hooks [leiningen.project-edn/activate]
  :project-edn {:output-file "target/resources/plumcp/core/project.edn"
                :output-mkdirs? true
                :select-keys [:version]}
  :repl-options {:init-ns plumcp.core}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.12.4"]]}})
