# Integration Troubleshooting Runbook

## HTTP 429

When the API returns HTTP 429, stop sending requests until the `Retry-After` delay expires. Then retry using exponential backoff with jitter. Do not retry faster by opening additional concurrent connections.

## Connection reset

For `GW-204`, verify DNS resolution and TLS negotiation, then retry once with the same request ID recorded in the incident ticket. Do not log authorization headers.
