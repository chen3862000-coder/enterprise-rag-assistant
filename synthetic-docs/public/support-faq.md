# Support FAQ

## Contacting support

Use the support portal to open a ticket. Include the request ID and approximate UTC timestamp, but do not include access tokens or passwords.

## HTTP 429

HTTP 429 means the caller has exceeded an API rate limit. Follow the `Retry-After` response header when it is present and use exponential backoff with jitter.
