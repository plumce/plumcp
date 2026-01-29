# PluMCP

[![Clojars Project](https://img.shields.io/clojars/v/io.github.plumce/plumcp.svg)](https://clojars.org/io.github.plumce/plumcp)
[![cljdoc badge](https://cljdoc.org/badge/io.github.plumce/plumcp)](https://cljdoc.org/d/io.github.plumce/plumcp)
[![Clojurians Slack](https://img.shields.io/badge/clojurians-%23plumcp-4A154B?logo=slack)](https://clojurians.slack.com/archives/plumcp)


PluMCP is a low-dependency
[Clojure](https://clojure.org)/[ClojureScript](https://clojurescript.org)
library for making
[Model Context Protocol (MCP)](https://modelcontextprotocol.io/)
clients and servers. Connect your business (data, process and software)
with AI Agents using MCP and idiomatic Clojure.

### Rationale

- _Complete:_ Enjoy all non-deprecated MCP features and transports
- _Reach:_ Clojure/ClojureScript reaches Java/JavaScript eco-systems
- _Ergonomic:_ User-friendly API, automatic error-checking
- _Light:_ Low dependency, Bring your own dependency
- _Flexible:_ Composable design with configurable/overridable defaults
- _Secure:_ OAuth 2.1 integrated with Streamable HTTP transport

## Development

There are Makefile targets for various development tasks:

Setup and teardown:

```
make setup
make clean
make distclean  # needs `make setup` later
```

Running tests:

```
make clj-test   # run tests in Clojure/JVM
make cljs-test  # run tests in Node.js
```

Module release:

```
# Edit `module-project-clj.bb` for version
make install-all-modules
make clean-all-modules
```

## Funding and Support

You can support this project via [Github Sponsors](https://github.com/sponsors/kumarshantanu)

# License

Copyright © 2025-2026 Shantanu Kumar

This program and the accompanying materials are made available under the
terms of the Eclipse Public License 1.0 which is available at
https://www.eclipse.org/legal/epl/epl-v10.html.
