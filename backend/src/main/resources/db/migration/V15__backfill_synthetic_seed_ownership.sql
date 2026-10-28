UPDATE documents
SET seed_managed = true
WHERE id IN (
    'doc-public-support-faq',
    'doc-public-service-availability',
    'doc-public-public-api-overview',
    'doc-public-customer-onboarding',
    'doc-public-product-overview',
    'doc-engineering-rate-limit-configuration',
    'doc-engineering-error-codes',
    'doc-engineering-deployment-procedures',
    'doc-engineering-service-dependencies',
    'doc-engineering-api-architecture',
    'doc-engineering-integration-troubleshooting',
    'doc-hr-employee-onboarding',
    'doc-hr-leave-policy',
    'doc-hr-benefits-overview',
    'doc-hr-remote-work-policy',
    'doc-security-security-escalation',
    'doc-security-access-control-standards',
    'doc-security-restricted-incident-report',
    'doc-security-security-controls',
    'doc-security-incident-response'
);
