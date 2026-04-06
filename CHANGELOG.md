# Change Log

All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [TODO/IDEA]

### Added

- Protocol
  - Spec: 2025-11-25
  - https://blog.modelcontextprotocol.io/posts/2026-mcp-roadmap/
- MCP Client
  - Check that _initialized_ ops have session-context before sending request
  - Client app using https://github.com/TimoKramer/charm.clj
  - Client: Add option in `make-client` to auto-reinitialize on HTTP 404
    - Refactor to have session-context in the HTTP transport itself
- MCP Server
  - Drop idle sessions
    - Kwarg: `:drop-idle-session?` (default=false)
    - Kwarg: `:idle-session-timeout` (default=30mins)
  - Pagination support
    - Paginated endpoints
  - Tag selector support (for listed server items)
    - Each list item may have zero or more tags
    - `tags=foo,bar` should select items with tags `foo` and `bar`
    - All transports to support this (STDIO, HTTP, Zero)
  - Replace "single node" list-changed notifier with one for scale-out
    - Ensure capabilities list is first updated on all server hosts
      - Capability declaration from a host is enough indication
    - Cache old-capabilities in session - all hosts can share/lead
    - Ensure only one list-changed notification goes to the client
    - Consider:
      - https://github.com/filipesilva/sqlatom
      - https://github.com/jimpil/duratom
  - Use a fast router for prompt or tool name, resource URI/template etc
    - Consider https://git.nmm.ee/asko/ruuter
- MCP Server OAuth
  - Support for non-DCR authorization servers
    - https://github.com/modelcontextprotocol/modelcontextprotocol/discussions/659
    - A self-hosted authorization proxy endpoint for target server
  - OAuth scope annotation in vars
  - Enable:
    - Token audience validation
    - Scope enforcement
    - Expiration validation
    - HTTPS only
  - Cache JWKS keys
  - Log auth failures (but never log full tokens)
- Readable Last-access time
  - Server: In server-session
  - Client: In client state
- Happy transport test
  - test-heartbeat (requires server capable of dropping idle session)
- Unhappy transport test
  - Connect to a non-existent HTTP endpoint

### Changed

- MCP Client
  - Re-implement client as a protocol instance - easy self-reference
- Zero transport
  - Make Zero-transport bidirectionally asynchronous (like STDIO/HTTP)
  - Synchronous Zero-transport fails tests unless tweaked as background call
- Factor out HTTP Server/Client
  - WebSocket server
    - Java: https://developer.mozilla.org/en-US/docs/Web/API/WebSockets_API/Writing_a_WebSocket_server_in_Java
    - Node: https://blog.stackademic.com/native-websocket-support-in-node-js-24-2aa17c6026ea

## [Unreleased] - 2026-???-??

### Added

- Add public API `p.c.a.mcp-runtime` ns
  - `get-request-params-meta` (moved from `mcp-server`)
  - `get-request-id` (moved from `mcp-server`)
  - `get-runtime` (moved from `mcp-server`)
  - `remove-runtime` (moved from `mcp-server`)
- Client: Add client-to-server communication utility fns
  - `send-message-to-server`
  - `request->response`

### Changed

- [BREAKING] Prune redundant client API functions in `p.c.a.mcp-client`
  - `notify-cancelled`
  - `notify-progress`
  - `notify-roots-list-changed`
  - `respond-roots-list`
  - `respond-sampling-create-message`
  - `respond-create-elicitation`
- [Todo] Bump dependency in `plumcp.core-auth` module
  - [Todo] jose (npm)

### Fixed

- HTTP Client Transport: Handle HTTP 202 Accepted response from server

## [0.2.0-beta6] - 2026-Mar-31

### Changed

- [BREAKING] Client: Drop async API functions in `p.c.c.client-support` ns
  - `async-initialize!`
  - `async-initialize-and-notify!`
  - `async-list-tools`
  - `async-call-tool`
  - `async-list-resources`
  - `async-list-resource-templates`
  - `async-read-resource`
  - `async-list-prompts`
  - `async-get-prompt`
  - `async-complete`
  - `async-ping`
- Bump dependencies in `plumcp.core-dev` module
  - Bling (from `0.9.2` to `0.10.0`)
  - Malli (from `0.20.0` to `0.20.1`)

### Fixed

- Client: Transparently handle paginated list items
  - `list-prompts`
  - `list-resources`
  - `list-resource-templates`
  - `list-tools`
- Fix strict Content-Type matching in HTTP client transport - @ericdallo

## [0.2.0-beta5] - 2026-Mar-23

### Added

- "Sampling" entity generator fns in `p.c.a.entity-support` ns
  - `make-sampling-text-message`
  - `make-sampling-text-message-request`
  - `make-sampling-text-message-result`
- Client/Server "Cancellation" fns in `p.c.a.mcp-client`/`p.c.a.mcp-server`
  - `cancel-sent-request`
  - `cancel-request-received?`
- Roundtrip tests
  - test-client-request-cancellation
  - test-server-request-cancellation

### Changed

- Client HTTP transport:
  - Treat all non-200 responses as errors, not just 400/404/500
    - JSON-RPC errors have `:plumcp.core/http-status` placed under `:error`
    - Remove support for redundant `:on-other-response` option kwarg
  - Gracefully handle server not supporting GET (stream)
    - Retry fetching GET-stream (only once) after JVM/IOException

### Fixed

- Request cancellation for both client and server

## [0.2.0-beta4] - 2026-Mar-17

### Added

- Unhappy transport tests
  - Client Op without initialization
  - Client Op with fake handshake
  - Client Op after server terminates session

### Changed

- Client: HTTP Client
  - HTTP 400/404/500 are now returned as JSON-RPC error response
  - Self-contained stopping of CLJ/JVM HTTP client transport
    - No more (out-of-band) explicitly interrupting GET (stream) thread

### Fixed

- Server: Fix `initialize` to return session ID when body is `text/event-stream`
- Fix Server HTTP transport to return 404 for non-existing session-ID
  - https://modelcontextprotocol.io/specification/2025-11-25/basic/transports#session-management
- Fix timeout option processing in client operations
- Fix CLJS (Node/Bun only) STDERR printing

## [0.2.0-beta3] - 2026-Mar-12

### Added

- Client: Public API Functions (`p.c.a.m-c` ns)
  - `register-client-request-progress-tokens`
  - `get-client-request-progress`
- Server: Public API Functions (`p.c.a.m-s` ns)
  - `register-server-request-progress-tokens`
  - `get-server-request-progress`
- Server: Kwarg `:notification-handlers` in `run-server`/`run-mcp-server`
- Client: Add init-check utility fn `p.c.a.c-s/wrap-initialized-check`
- Roundtrip tests
  - List changed (primitives)
    - Client: _roots_
    - Server: _prompts_, _resources_, _resource-templates_, _tools_
  - Progress tracking
    - `test-client-progress-tracking`
    - `test-server-progress-tracking`
  - MCP Logging: `test-mcp-logging`

### Changed

- Protocol `IServerSession` fns
  - Add: `add-progress-tokens`, `progress-token->id`, `remove-progress-tokens`
  - Add: `read-pending-request`, `save-request-progress`
  - [BREAKING] Remove: `get-progress`, `update-progress`, `remove-progress`

### Fixed

- MCP Client
  - Track request progress on user-declared request progress-tokens
  - Flush CLJ/JVM client STDIO transport after sending a message
  - Guard on _initialized_ state for list-changed refetch handlers
- MCP Server
  - Track request progress on user-declared request progress-tokens

## [0.2.0-beta2] - 2026-Mar-07

### Added

- Server: Public API functions for server operations
  - `get-request-params-meta`
  - `get-request-id`
  - `get-callback-context`
  - `make-callback-context`
  - `get-runtime`
  - `remove-runtime`
  - `send-notification-to-client`
  - `with-logger` (moved from `runtime-support` ns)
  - `log-7-debug` (moved from `runtime-support` ns)
  - `log-6-info` (moved from `runtime-support` ns)
  - `log-5-notice` (moved from `runtime-support` ns)
  - `log-4-warning` (moved from `runtime-support` ns)
  - `log-3-error` (moved from `runtime-support` ns)
  - `log-2-critical` (moved from `runtime-support` ns)
  - `log-1-alert` (moved from `runtime-support` ns)
  - `log-0-emergency` (moved from `runtime-support` ns)
  - `get-client-capabilities`
  - `send-request-to-client`
  - `stop-server`

### Changed

- Client: Make result handling more flexible
  - Allow kwarg `:on-result` in client op function options
  - Cache original `:result` value (not `:on-result` applied)
    - Background and user calls with different `:on-result` can co-exist
- Protocol: Bump compliant protocol version to `2025-11-25`
  - So that tools (MCP Inspector, Claude Code) do not refuse to work
  - Capabilities are still all `2025-06-18`

### Fixed

- Fix module description on Clojars
  - `plumcp.core-json-charred` module
  - `plumcp.core-json-cheshire` module
  - `plumcp.core-json-datajson` module
  - `plumcp.core-json-jsonista` module
- Fix SSE stream items extraction for CLJ/JVM

## [0.2.0-beta1] - 2026-Mar-02

### Added

- Client: Notification handling
  - Cancellation: Cancel server request (to client)
  - Progress update: Update progress of pending client request
    - Function `p.c.a.mcp-client/get-request-progress` to read progress
  - Logging message: Log the server-sent message
  - List changed (prompts, resources, tools): Re-fetch by default
- Client: Cache primitives (prompts, resources, tools)
  - Kwarg `:cache-primitives?` in `make-client` for primitives caching
  - Functions reading primitives list transparently apply caching
- Client: Heartbeat mechanism to keep connection alive
- Client: Support for SSL context/params in CLJ/JVM HTTP Client
  - Opts in `p.c.s.h-c-j/make-client-context` or `p.c.s.h-c/make-http-client`
  - Kwarg `:ssl-context` - `javax.net.ssl.SSLContext` instance
  - Kwarg `:ssl-params` - `javax.net.ssl.SSLParameters` instance
- Client: Support for HTTPS proxy in CLJS HTTP client (request keys)
  - Node.js: `:dispatcher` and (legacy) `:agent` options
  - Bun.js: `:proxy` option
- Server: Caching layer for roots
  - List-changed listening support for roots: Re-fetch roots
  - Cache roots in the session
  - API for reading roots

### Changed

- [BREAKING] Move fns `p.c.a.mcp-client` to `p.c.c.client-support` ns
  - `async-initialize!`
  - `async-initialize-and-notify!`
  - `async-list-tools`
  - `async-call-tool`
  - `async-list-resources`
  - `async-list-resource-templates`
  - `async-read-resource`
  - `async-list-prompts`
  - `async-get-prompt`
  - `async-complete`
  - `async-ping`
  - `initialize!`
  - `notify-initialized`
- [BREAKING] Rename ns `p.c.i.capability` to `p.c.i.impl-capability`
- [BREAKING] Rename ns `p.c.a.capability-support` to `p.c.a.capability`
- [BREAKING] Drop redundant notification method vars in `p.c.c.client-support`
  - `on-cancelled` (use `sd/method-notifications-cancelled` instead)
  - `on-progress` (use `sd/method-notifications-progress` instead)
  - `on-log-message` (use `sd/method-notifications-message` instead)
  - `on-prompts-list-changed` (use `sd/method-notifications-prompts-list_changed` instead)
  - `on-resources-list-changed` (use `sd/method-notifications-resources-list_changed` instead)
  - `on-resource-updated` (use `sd/method-notifications-resources-updated` instead)
  - `on-tools-list-changed` (use `sd/method-notifications-tools-list_changed` instead)

### Fixed

- Git/SCM coordinates in module `pom.xml`, which reflects on Clojars

## [0.2.0-alpha1] - 2026-Feb-18

### Added

- STDIO Client Transport
  - Options in `plumcp.core.client.stdio-client-transport/run-command`
    - Kwarg `:dir` - current directory for process
    - Kwarg `:env` - environment variables map
- Runtime override option
  - Server: Kwarg `:override` in `run-server`, `run-mcp-server`
  - Client: Kwarg `:override` in `make-client`, `make-mcp-client`
- Synchronous MCP Client API functions in `p.c.a.mcp-client` ns
  - `initialize!`
  - `initialize-and-notify!`
  - `list-prompts`
  - `list-resources`
  - `list-resource-templates`
  - `list-tools`
  - `call-tool`
  - `get-prompt`
  - `read-resource`
  - `complete`
  - `ping`
  - `fetch-prompts` (useful for prompts-list-changed notification)
  - `fetch-resources` (useful for resources-list-changed notification)
  - `fetch-tools` (useful for tools-list-changed notification)
- Capability API fns in new ns `plumcp.core.api.capability-support`
  - Capability item makers
    - `make-root-item`
    - `make-prompt-item`
    - `make-resource-item`
    - `make-resource-template-item`
    - `make-tool-item`
  - Primitives handlers
    - `make-sampling-handler`
    - `make-elicitation-handler`
    - `primitives->client-capabilities` (moved from `p.c.i.var-support`)
    - `make-completions-reference-item` (moved from `p.c.i.capability`)
    - `primitives->server-capabilities` (moved from `p.c.i.var-support`)
- Capability List-changed support
  - Capability-making fns enhanced to support fixed items and mutable refs
  - Send out notifications to all connected peers
    - Core implementation to detect and send list-changed notification
    - Server: Integrate list-changed notifier with `run-server`, `run-mcp-server`
    - Client: Integrate list-changed notifier with `make-client`, `make-mcp-client`
  - Provision for re-fetch on list-changed notification
    - Client: Add option kwarg `:notification-handlers` to `make-client`
- Make initialization info accessible
  - Server: Store initialization params in server session
  - Client: Store initialization result in client context
    - Add fn `p.c.a.mcp-client/get-initialize-result` to return the result
- Convenience functions
  - Function `p.c.a.entity-support/prompt-message->get-prompt-result`
- OAuth options `plumcp.core.client.http-client-transport-auth/handle-authz-flow`
  - Kwarg `:prm-request-middleware` - for Protected Resource Metadata request
  - Kwarg `:asm-request-middleware` - for Authorization Server Metadata request
  - Kwarg `:dcr-request-middleware` - for Dynamic Client Regitration request
    - https://github.com/modelcontextprotocol/modelcontextprotocol/discussions/659

### Changed

- [BREAKING CHANGE] Replace protocol fn `log-mcp-notification` with
  - `log-incoming-jsonrpc-notification`
  - `log-outgoing-jsonrpc-notification`
- [BREAKING CHANGE] Rename old async fns in `plumcp.core.api.mcp-client` ns
  - `initialize!` to `async-initialize!`
  - `initialize-and-notify!` to `async-initialize-and-notify!`
  - `list-prompts` to `async-list-prompts`
  - `list-resources` to `async-list-resources`
  - `list-resource-templates` to `async-list-resource-templates`
  - `list-tools` to `async-list-tools`
  - `call-tool` to `async-call-tool`
  - `get-prompt` to `async-get-prompt`
  - `read-resource` to `async-read-resource`
  - `complete` to `async-complete`
  - `ping` to `async-ping`
- [BREAKING CHANGE] Change fn arity in `p.c.a.mcp-client` ns
  - `async-initialize!` (result-handler -> response-handler)
  - `async-list-tools` (result-handler -> response-handler)
  - `async-call-tool` (result-handler -> response-handler)
  - `async-list-resources` (result-handler -> response-handler)
  - `async-list-resource-templates` (result-handler -> response-handler)
  - `async-read-resource` (result-handler -> response-handler)
  - `async-list-prompts` (result-handler -> response-handler)
  - `async-get-prompt` (result-handler -> response-handler)
  - `async-complete` (result-handler -> response-handler)
  - `async-ping` (result-handler -> response-handler)
- [BREAKING CHANGE] Drop capability making fns in `p.c.i.capability` ns
  - `make-fixed-roots-capability` (use `make-roots-capability` instead)
  - `make-fixed-prompts-capability` (use `make-prompts-capability` instead)
  - `make-fixed-resources-capability` (use `make-resources-capability` instead)
  - `make-fixed-tools-capability` (use `make-tools-capability` instead)

### Fixed

- MCP Client
  - Options destructuring in base-client-context construction
  - Applying HTTP response middleware in CLJS HTTP Client
  - Closing browser in CLJS/Node.js Client OAuth authorization flow
- Dev module
  - Do not emit `:request` entry in schema validation error responses

## [0.1.0] - 2026-Jan-29

### Added

- MCP Protocol spec implementation
  - 2025-06-18, 2025-03-26
- MCP Transports (client and server)
  - STDIO
  - Streamable HTTP
  - OAuth 2.1 for Streamable HTTP
- MCP Server features
  - Prompts
  - Resources (and Resource Templates)
  - Tools
  - Callbacks (to handle responses for requests sent to client)
  - Completion
- MCP Client features
  - Roots
  - Sampling
  - Elicitation
- Public API for MCP client and server
  - Support for annotated vars as MCP primitives
- Modules
  - `module-core`
  - `module-core-auth`
  - `module-core-dev`
  - `module-core-json-<codec>`
    - Each of `charred`, `cheshire`, `data.json`, `jsonista`
- Utility
  - Ring based HTTP Client and Server implementation

[Unreleased]: https://github.com/plumce/plumcp/compare/v0.1.0...HEAD
