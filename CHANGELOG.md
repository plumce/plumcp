# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](https://keepachangelog.com/).

## [Unreleased] - 2026-??-??
### Added
- STDIO Client Transport
  - Options in `plumcp.core.client.stdio-client-transport/run-command`
    - Option `:dir` - current directory for process
    - Option `:env` - environment variables map
- Capability API fns in new ns `plumcp.core.api.capability-support`
  - Capability item-making fns
- Capability List-changed support
  - Capability-making fns from dereferenceable refs (eg. atom, volatile)
  - Send out notifications to all connected peers
    - Core implementation to detect and send list-changed notification
    - Server: Integrate list-changed notifier with `run-server`, `run-mcp-server`
    - [Todo] Client: Integrated with `make-client`
  - [Todo] Server: Send out notifications to all connected clients
  - [Todo] Client: Change listener (with client, so as to re-fetch list)
- Convenience functions for making capability
  - [Todo] In `plumcp.core.api.capability-support`: for making capability
  - [Todo] Cache/expose `InitializeResult` after `initialize!` success
  - [Todo] In `plumcp.core.api.mcp-client`: for "sync" client operations
    - [Todo] Return `InitializeResult` in "sync" `initialize-and-notify!`
- Convenience functions
  - Function `p.c.a.entity-support/prompt-message->get-prompt-result`
- Auth support
  - [Todo] Client: Support for Bearer token passed in options

### BREAKING CHANGE
- [Todo] Move into `capability-support` ns
  - capability/make-deref-xxxx-capability
  - capability/make-fixed-xxxx-capability
- [Todo] Refactor MCP client-op functions to return value/js-Promise

### Fixed
- Options destructuring in base-client-context construction

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
