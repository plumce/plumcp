# PluMCP proposal - Clojurists Together 2026 Q2

My proposal includes two broad topics as follows:

1. PluMCP MCP spec upgrade to 2025-11-25
2. PluMCP Usage documentation

These topics are expanded upon below.

## PluMCP MCP spec upgrade to 2025-11-25

As of today the following MCP spec versions have been released:

| MCP spec version | PluMCP implementation status       |
| ---------------- | ---------------------------------- |
| 2025-11-25       | Not implemented - yet to implement |
| 2025-06-18       | Supported as the main spec version |
| 2025-03-26       | Supported in compatibility mode    |
| 2024-11-05       | Not supported, no plan to support  |

PluMCP will implement MCP protocol spec version 2025-11-25. The key
changes are listed at:
https://modelcontextprotocol.io/specification/2025-11-25/changelog

A summary of changes as TODO items are below:

### Major changes

1. Add support for OpenID Connect Discovery 1.0 to authorization server
   discovery
2. Allow servers to expose icons as additional metadata for tools,
   resources, resource templates, and prompts
3. Enhance authorization flows with incremental scope consent via
   `WWW-Authenticate`
4. Validate tool names as per the new spec
5. Update `ElicitResult` and `EnumSchema` to use a more standards-based
   approach and support titled, untitled, single-select, and multi-select
   enums
6. Add support for URL mode elicitation
7. Add tool calling support to sampling via `tools` and `toolChoice`
   parameters
8. Add support for OAuth Client ID Metadata Documents as a recommended
   client registration mechanism
9. Add support for (potentially long running) tasks to enable tracking
   durable requests with polling and deferred result retrieval

### Minor changes

1. Add utility function(s) to let servers using STDIO transport use
   STDERR for all types of logging, not just error messages
2. Add optional description field to `Implementation` (schema) interface
   to align with MCP registry `server.json` format and provide
   human-readable context during initialization
3. Have the servers respond with HTTP 403 Forbidden for invalid Origin
   headers in Streamable HTTP transport
4. Review _Security Best Practices Guidance_ - add required utility fns
5. Return Input validation errors as Tool Execution Errors rather than
   Protocol Errors to enable model self-correction
6. Support polling SSE streams by allowing servers to disconnect at will
7. Support polling in GET streams, resumption always via GET regardless
   of stream origin
8. Align OAuth 2.0 Protected Resource Metadata discovery with RFC 9728,
   making `WWW-Authenticate` header optional with fallback to
   `.well-known` endpoint
9. Add support for default values in all primitive types (string, number,
   enum) for elicitation schemas
10. Establish JSON Schema 2020-12 as the default dialect for MCP schema
    definitions (2020-12 is the ONLY supported dialect for now)

## PluMCP Usage documentation

The PluMCP documentation so far has been skeletal, covering only basic
use cases. I would like to extend the documentation with examples for a
wider set of features and use cases.

### MCP Server documentation

- Utility
  - Completion
  - Logging
- Advanced
  - Callbacks
  - OAuth
  - Ring adapter usage (e.g. Jetty)

### MCP Client documentation

- Features
  - Roots
  - Sampling
  - Elicitation
- Advanced
  - OAuth

### Example code

PluMCP already includes a sub-set of "Everything server":
https://github.com/modelcontextprotocol/servers/blob/main/src/everything/README.md

I want to fully implement the latest "Everything" server to demonstrate
how to use PluMCP features.
