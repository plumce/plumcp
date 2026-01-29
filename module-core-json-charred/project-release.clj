(defproject io.github.plumce/plumcp.core-json-charred "{{version}}"
  :description "Dev module for PluMCP"
  :url "https://github.com/plumce/plumcp"
  :license {:name "Eclipse Public License 1.0"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :global-vars {*warn-on-reflection* true
                *assert* true
                *unchecked-math* :warn-on-boxed}
  :dependencies [[io.github.plumce/plumcp.core "{{version}}"]
                 [com.cnuernber/charred "1.037"]]
  :repl-options {:init-ns plumcp.core}
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.12.4"]]}}
  :deploy-repositories [["releases" {:url "https://clojars.org"
                                     :creds :gpg}]])
