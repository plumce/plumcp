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
