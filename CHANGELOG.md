# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [TODO/IDEA] - ????-??-??
### Added

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

## [Unreleased] - 2026-??-??
### Added
- STDIO Client Transport
  - Options in `plumcp.core.client.stdio-client-transport/run-command`
    - Kwarg `:dir` - current directory for process
    - Kwarg `:env` - environment variables map
- Runtime override option
  - Server: Kwarg `:override` in `run-server`, `run-mcp-server`
  - Client: Kwarg `:override` in `make-client`, `make-mcp-client`
- Synchronous MCP Client API functions
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
    - `primitives->fixed-server-capabilities` (moved from `p.c.i.var-support`)
- Capability List-changed support
  - Capability-making fns from dereferenceable refs (eg. atom, volatile)
  - Send out notifications to all connected peers
    - Core implementation to detect and send list-changed notification
    - Server: Integrate list-changed notifier with `run-server`, `run-mcp-server`
    - Client: Integrate list-changed notifier with `make-client`, `make-mcp-client`
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

### Fixed
- MCP Client
  - Options destructuring in base-client-context construction
  - Applying HTTP response middleware in CLJS HTTP Client
  - Closing browser in CLJS/Node.js Client OAuth authorization flow

## [0.1.0] - 2026-01-29
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
