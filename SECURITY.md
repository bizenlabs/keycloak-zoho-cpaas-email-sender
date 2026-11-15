# Security

Please report a vulnerability privately through GitHub's
[security advisories](https://github.com/bizenlabs/keycloak-zoho-cpaas-email-sender/security/advisories/new),
not in a public issue. We answer within a week.

This extension handles a Zoho CPaaS API key (as the realm's SMTP password) and the
contents of Keycloak's email, which include one-time sign-in links. It never logs the key,
and it follows no redirects.
