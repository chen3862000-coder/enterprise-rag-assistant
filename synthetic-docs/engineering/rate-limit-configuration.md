# Enterprise API Policy

## Rate Limits

The default Enterprise API limit is 1,000 requests per minute per workspace. The gateway returns HTTP 429 when the configured limit is exceeded and includes `Retry-After` in seconds when a retry window is known.

## Client behavior

Clients must honor `Retry-After` and apply exponential backoff with jitter. Repeated requests during the wait interval can extend throttling.
