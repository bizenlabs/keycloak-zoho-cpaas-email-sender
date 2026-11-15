# Keycloak email sender for Zoho CPaaS

A [Keycloak](https://www.keycloak.org) extension that sends Keycloak's email (address
verification, password reset, account linking, the admin console's *Test connection*)
through the **[Zoho CPaaS](https://www.zoho.com/cpaas/) email API over HTTPS** instead
of SMTP. Zoho CPaaS is Zoho's transactional-email service, **formerly called ZeptoMail**.

> This is **not** for Zoho Mail, Zoho's mailbox service. It is for transactional email
> sent through a Zoho CPaaS (ZeptoMail) mail agent.

## Why

Keycloak only speaks SMTP. Many hosts block outbound SMTP: DigitalOcean, for example,
blocks ports 25, 465 and 587 on every account and does not lift the block on request.
Zoho CPaaS takes email over an HTTPS API on port 443, which is open everywhere. This
extension is the bridge: Keycloak hands it the subject, text and HTML of each email, and
it posts them to the API.

## What you need

- Keycloak **26.8** (the release you install names the Keycloak it was built and tested
  against; see [Compatibility](#compatibility)).
- A Zoho CPaaS account with a **mail agent** whose sending domain is verified, and that
  agent's **API key** (the "Send Mail token" for the API, not the SMTP password).
- Your account's **data centre**, which decides the API host: for example
  `cpaas.zoho.com`, or `cpaas.zoho.in` for an account in Zoho's India data centre. Use
  the host your account's API page shows; the key only works on its own data centre.

## Install

1. Download `keycloak-zoho-cpaas-email-sender-<version>-keycloak-<keycloak>.jar` and its
   `.sha256` from the [releases](https://github.com/bizenlabs/keycloak-zoho-cpaas-email-sender/releases),
   and check it: `sha256sum -c keycloak-zoho-cpaas-email-sender-*.jar.sha256`.
2. Put the jar in Keycloak's `providers/` directory (`/opt/keycloak/providers` in the
   official image). In a Dockerfile:

   ```dockerfile
   FROM quay.io/keycloak/keycloak:26.8.2
   COPY keycloak-zoho-cpaas-email-sender-1.0.0-keycloak-26.8.2.jar /opt/keycloak/providers/
   ```

   If you start Keycloak with `--optimized`, run `kc.sh build` after adding it.
3. Make it Keycloak's email sender. Adding the jar alone changes nothing:

   ```sh
   kc.sh start --spi-email-sender--provider-default=zoho-cpaas
   # or, in the environment:
   KC_SPI_EMAIL_SENDER__PROVIDER_DEFAULT=zoho-cpaas
   ```

   This applies to every realm. A realm whose email you never set up keeps sending
   nothing, as before.

## Configure a realm

The realm's ordinary **Email** settings (*Realm settings → Email*, or `smtpServer` in a
realm export) are the configuration. Nothing new is stored.

| Setting | What to put there |
| --- | --- |
| **Host** | The API host, e.g. `cpaas.zoho.in`. It becomes `https://<host>/v1.1/email`. A full URL also works. |
| **Password** (with *Authentication* on) | The mail agent's API key. A vault reference such as `${vault.zoho-key}` works and is the better place for it. |
| **From**, **From display name** | The sender, on the agent's verified domain. |
| **Reply to**, **Reply to display name** | Optional, as for SMTP. |
| Port, Encryption, Username, Envelope from | Not used. The username field is a convenient place to note the agent's name. |

As a realm export (for example with keycloak-config-cli):

```yaml
smtpServer:
  host: cpaas.zoho.in
  auth: "true"
  user: my-mail-agent
  password: ${vault.zoho-key}
  from: no-reply@example.com
  fromDisplayName: Example
```

Then use **Test connection** in the admin console: it sends a real email through the API
to your own address.

## What it sends

One `POST https://<host>/v1.1/email` per email, with `Authorization: Zoho-enczapikey <key>`
and a JSON body in the API's field names: `from`, `to` (one recipient), `reply_to` (when
set), `subject`, `textbody`, `htmlbody`, and `track_opens`/`track_clicks` set to `false`,
since Keycloak's email holds one-time personal links. Anything other than a 2xx answer
fails the send with Zoho's status and error code (an unverified domain, a bad key) in
Keycloak's log. The key is never logged.

## Compatibility

The email sender interface is part of Keycloak's internal SPI and may change between
Keycloak releases. Each release of this jar says which Keycloak it was built against and
passed the [integration test](scripts/integration-test.sh) on: a real Keycloak sending its
test email through the jar to a stub of the API.

| Release | Keycloak |
| --- | --- |
| 1.0.0 | 26.8.2 |

To build for another Keycloak: `mvn package -Dkeycloak.version=<version>`, then
`KEYCLOAK_VERSION=<version> scripts/integration-test.sh`.

## Building and testing

```sh
mvn verify                       # unit tests, against a stub of the API
scripts/integration-test.sh      # the jar inside a real Keycloak (needs Docker)
```

## History

Written by BizenLabs for its own identity service, after DigitalOcean declined to lift its
SMTP block, and published so others in the same spot need not write it again.

## Licence

[Apache License 2.0](LICENSE). Keycloak is a trademark of Red Hat, Inc.; Zoho and Zoho
CPaaS are trademarks of Zoho Corporation. This project is not affiliated with either.
