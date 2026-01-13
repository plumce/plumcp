(defproject io.github.plumce/plumcp.core-dev "{{version}}"
  :description "Dev module for PluMCP"
  :url "https://github.com/plumce/plumcp"
  :license {:name "Eclipse Public License 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :global-vars {*warn-on-reflection* true
                *assert* true
                *unchecked-math* :warn-on-boxed}
  :dependencies [[io.github.plumce/plumcp.core "{{version}}"]
                 [io.github.paintparty/bling "0.9.2"
                  :exclusions [org.clojure/clojure]]
                 [metosin/malli "0.20.0"
                  :exclusions [org.clojure/clojure]]]
  :repl-options {:init-ns plumcp.core}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.12.4"]]}})
