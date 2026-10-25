# Internal API Architecture

The Acme API gateway routes `/v1` requests to the Workspace service. The gateway validates bearer tokens before routing. The Audit service records request IDs and administrative changes; it does not store bearer token values.

## Request identifiers

Every response includes an `X-Request-Id` value. Include that value in engineering support escalations.
