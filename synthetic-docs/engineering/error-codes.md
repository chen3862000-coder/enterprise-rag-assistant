# API Error Code Reference

## GW-204

`GW-204` indicates the gateway could not establish a connection to an upstream service. Verify DNS and TLS, then use the integration troubleshooting runbook.

## API-429

`API-429` is the internal label for a rate-limit response. The HTTP status is 429. Honor the `Retry-After` header and retry with exponential backoff and jitter.
