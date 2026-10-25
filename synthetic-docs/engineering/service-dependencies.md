# Service Dependencies

The Workspace service depends on the API gateway, the identity token validator, and the Audit event sink. Audit sink degradation does not block read-only workspace requests, but administrative updates are paused until audit delivery recovers.
