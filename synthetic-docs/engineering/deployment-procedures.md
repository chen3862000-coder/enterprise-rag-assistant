# Engineering Deployment Procedures

Engineering deploys the Workspace service through the fictional release pipeline. A deployment requires a reviewed change, passing unit tests, and a verified rollback artifact.

## Rollback

If readiness checks fail for five consecutive minutes, stop promotion and restore the most recent healthy artifact. Record the change ID and `X-Request-Id` values in the internal release record.
