.PHONY: help

.DEFAULT_GOAL := help

## Defaults overridable by environment variable
PLUMCP_NODE ?= node
PLUMCP_NPM ?= npm
PLUMCP_NPX ?= npx

help: # Source: https://stackoverflow.com/a/65243296
	@printf "%-20s %s\n" "Target" "Description"
	@printf "%-20s %s\n" "------" "-----------"
	@make -pqR : 2>/dev/null \
		| awk -v RS= -F: '/^# File/,/^# Finished Make data base/ {if ($$1 !~ "^[#.]") {print $$1}}' \
		| sort \
		| egrep -v -e '^[^[:alnum:]]' -e '^$@$$' \
		| xargs -I _ sh -c 'printf "%-20s " _; make _ -nB | (grep -i "^# Help:" || echo "") | tail -1 | sed "s/^# Help: //g"'

clean:
	@# Help: Remove generated files
	rm -rf cljs-test-runner-out
	rm -rf module-core/target
	rm -rf out

distclean: clean
	@# Help: Removed all downloaded and generated files
	rm -rf node_modules
	rm -rf module-core-json-template/out

deps-tree:
	@# Help: Show dependency tree with any conflicts
	#clj -X:deps tree
	clj -Stree #| hili8 older

setup:
	@# Help: Initialize the project for development work
	$(PLUMCP_NPM) install
	cd module-core-json-template && bb generate-json-modules.bb

pedantic-abort:
	@# Help: Equivalent of Leiningen's `{:pedantic? :abort}`
	#!(clj -Stree | grep -i older-version)
	!(clj -X:deps tree | grep -i older-version)

nrepl:
	@# Help: Start Cider nREPL server
	clj -M:cider-nrepl

clj-test:
	@# Help: Run CLJ tests
	clj -M:clj-test

cljs-test:
	@# Help: Run CLJS tests
	clj -M:cljs-test

lint:
	@# Help: Run linter
	clojure-lsp diagnostics


## ----- STDIO Server -----


run-server-stdio-java: #pedantic-abort
	@# Help: Run Dev MCP STDIO server
	@clj -M:run-dev-stdio-server

run-server-stdio-node: #pedantic-abort
	@# Help: Run Dev MCP STDIO server using Node.js
	@npx shadow-cljs compile :node-stdio-server 1>&2
	@node out/node-stdio-server.js


## ----- STDIO Client -----


run-client-stdio-java: #pedantic-abort
	@# Help: Run Dev MCP STDIO client app
	clj -M:run-dev-stdio-client


run-client-stdio-node: #pedantic-abort
	@# Help: Run Dev MCP STDIO client using Node.js
	npx shadow-cljs compile :node-client
	node out/node-client.js make run-server-stdio-node


## ----- Streamable HTTP Server -----


run-server-http-java: #pedantic-abort
	@# Help: Run Dev MCP STDIO server
	@clj -M:run-dev-http-server

run-server-http-node: #pedantic-abort
	@# Help: Run Dev MCP STDIO server using Node.js
	@npx shadow-cljs compile :node-http-server 1>&2
	@node out/node-http-server.js


## ----- Streamable HTTP Client -----


run-client-http-java: #pedantic-abort
	@# Help: Run Dev MCP Streamable HTTP client app
	clj -M:run-dev-http-client


run-client-http-node: #pedantic-abort
	@# Help: Run Dev MCP STDIO client using Node.js
	npx shadow-cljs compile :node-client
	node out/node-client.js http://localhost:3000/mcp


## ----- Module installation -----


clean-module:
	@#Help: Clean the module or generated artifacts
	rm -rf $(MODULE)/project.clj $(MODULE)/pom.xml $(MODULE)/target
	@#bb module-project-clj.bb clean $(MODULE)


install-module:
	@#Help: Clean the module or generated artifacts
	bb module-project-clj.bb emit $(MODULE)
	cd $(MODULE) && lein install
	@#rm $(MODULE)/project.clj


clean-all-modules:
	@#Help: Clean all modules or generated artifacts
	@for a in module-core*; do \
	  echo "\nCleaning module:" $$a; \
	  make clean-module MODULE=$$a; \
	done


install-all-modules:
	@#Help: Clean all modules or generated artifacts
	@for a in module-core*; do \
	  echo "\nInstalling module:" $$a; \
	  make install-module MODULE=$$a; \
	done


## ----- Count lines of code -----


cloc:
	@#Help: Count total lines of code (needs `cloc` installed)
	@echo "\n===== Lines of code: Modules =====\n"
	cloc module-core module-core-auth module-core-dev \
	     module-core-json-charred \
		 module-core-json-cheshire \
		 module-core-json-datajson \
		 module-core-json-jsonista
	@echo "\n===== Lines of code: Tests/scafolding =====\n"
	cloc src
