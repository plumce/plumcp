# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [TODO/IDEA] - ????-??-??
### Added

- MCP Client
  - Re-implement client as a protocol instance - easy self-reference
- MCP Server
  - Replace "single node" list-changed notifier with one for scale-out
    - Ensure capabilities list is first updated on all server hosts
      - Capability declaration from a host is enough indication
    - Cache old-capabilities in session - all hosts can share/lead
    - Ensure only one list-changed notification goes to the client
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

## [Todo] - 2026-???-??
### Added
- Server: Public API functions for server operations
  - `get-request-params-meta`
  - `get-request-id`
  - `get-callback-context`
  - `make-callback-context`
  - `get-runtime`
  - `remove-runtime`
  - `send-notification-to-client`
  - `with-logger`     (moved from `runtime-support` ns)
  - `log-7-debug`     (moved from `runtime-support` ns)
  - `log-6-info`      (moved from `runtime-support` ns)
  - `log-5-notice`    (moved from `runtime-support` ns)
  - `log-4-warning`   (moved from `runtime-support` ns)
  - `log-3-error`     (moved from `runtime-support` ns)
  - `log-2-critical`  (moved from `runtime-support` ns)
  - `log-1-alert`     (moved from `runtime-support` ns)
  - `log-0-emergency` (moved from `runtime-support` ns)
  - `get-client-capabilities`
  - `send-request-to-client`
  - `stop-server`

### Changed
- Client: Make result handling more flexible
  - Allow kwarg `:on-result` in client op function options
  - Cache original `:result` value (not `:on-result` applied)
    - Background and user calls with different `:on-result` can co-exist

### Fixed
- Fix module description on Clojars
  - `plumcp.core-json-charred` module
  - `plumcp.core-json-cheshire` module
  - `plumcp.core-json-datajson` module
  - `plumcp.core-json-jsonista` module

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
  - Kwarg `:ssl-params`  - `javax.net.ssl.SSLParameters` instance
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
