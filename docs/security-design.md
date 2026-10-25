# Security Design

## Demo Identity

The development login accepts only Alice, Bob, or Carol. The backend resolves each identity to a fixed role set and issues a server-side session. Client-provided `role`, `department`, or user ID fields do not alter authorization. Sessions use HTTP-only, same-site cookies, login rotates the session ID, and unsafe methods require a session CSRF token.

This demo login is an identity selector, not password authentication or enterprise SSO. Do not expose it as a production authentication system. Set `DEMO_AUTHENTICATION_ENABLED=false` when the identity selector is not appropriate.

## Authorization Boundaries

- Alice can retrieve public and engineering documents.
- Bob can retrieve public and HR documents.
- Carol can retrieve public and security documents. Carol separately has the demo `documentAdmin` capability for management and evaluation operations.
- Document management authority does not expand retrieval roles. Carol can manage all document metadata but retrieval and source access remain constrained to her own `PUBLIC` and `SECURITY_ADMIN` roles.
- Vector, full-text, and exact identifier retrieval queries all filter roles, active document status, and active document version in SQL before candidate chunks reach RRF or the model context.
- Source preview re-authorizes role, document status, active version, and active index status in SQL.
- Document listing/version metadata is role-filtered for ordinary users; the explicit document administrator may list/manage all metadata.
- Evaluation run execution/results require the explicit administrator capability. Trace reads are visible only to the trace owner or explicit administrator.
- Identical active content avoids embedding/version work but transactionally applies changed title, department, classification, and ACL. A narrowed ACL takes effect for subsequent vector, full-text, exact, citation, and source queries. Failed-content retries do not update the active ACL until successful activation.
- `POST /api/documents/{id}/disable` disables retrieval while retaining the logical record; `DELETE /api/documents/{id}` soft-deletes by setting `DELETED`, also immediately excluding it from retrieval. Neither operation physically purges retained chunks.

## Data Handling

Routine traces store a query hash and bounded timing/count/model/status metadata, not raw query text or retrieved source text. The chat response may contain authorized source citations; the source endpoint resolves the chunk under the caller's current ACL and active-version state. Trace retention defaults to 30 days.

## License and Data Ownership

The repository is distributed under the MIT License. The Acme corpus is synthetic and intended for publication. Do not upload confidential or personal source material into the portfolio demo.

## Limitations

This is an educational MVP using fixed demo identities and application-level SQL predicates. PostgreSQL row-level security, SSO, DLP, audit-grade tamper protection, rate limiting, production secret management, and formal security review are not implemented. Prompt instructions and citation validation do not guarantee model factuality or complete prompt-injection resistance.
