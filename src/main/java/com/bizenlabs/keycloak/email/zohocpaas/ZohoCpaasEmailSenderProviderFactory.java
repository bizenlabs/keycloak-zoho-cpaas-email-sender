/*
 * Copyright 2026 BizenLabs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.bizenlabs.keycloak.email.zohocpaas;

import java.net.http.HttpClient;
import java.time.Duration;

import org.keycloak.Config;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.email.EmailSenderProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.vault.VaultStringSecret;

/**
 * Registers {@link ZohoCpaasEmailSenderProvider} as the email sender {@code zoho-cpaas}.
 *
 * <p>Keycloak uses the sender with the id {@code default} unless told otherwise, so
 * merely adding this jar changes nothing. Opt in with
 * {@code --spi-email-sender--provider-default=zoho-cpaas} (or the environment variable
 * {@code KC_SPI_EMAIL_SENDER__PROVIDER_DEFAULT=zoho-cpaas}).
 *
 * <p>One HTTP client for the server's lifetime: it is thread-safe and holds the
 * connection pool. Each provider instance is per request, as Keycloak's are.
 */
public final class ZohoCpaasEmailSenderProviderFactory implements EmailSenderProviderFactory {

    public static final String ID = "zoho-cpaas";

    private HttpClient http;

    @Override
    public EmailSenderProvider create(KeycloakSession session) {
        return new ZohoCpaasEmailSenderProvider(http, reference -> resolve(session, reference));
    }

    /**
     * Resolves the realm's SMTP password the way Keycloak's own sender does: a
     * {@code ${vault.<key>}} reference is read from the vault, anything else is the
     * value itself. An unresolvable reference yields {@code null}, which the provider
     * reports as a configuration error rather than sending an empty key.
     */
    private static String resolve(KeycloakSession session, String reference) {
        if (reference == null) {
            return null;
        }
        try (VaultStringSecret secret = session.vault().getStringSecret(reference)) {
            return secret.get().orElse(null);
        }
    }

    @Override
    public void init(Config.Scope config) {
        http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
    }

    @Override
    public void close() {
        http = null;
    }

    @Override
    public String getId() {
        return ID;
    }
}
