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

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jboss.logging.Logger;
import org.keycloak.email.EmailException;
import org.keycloak.email.EmailSenderProvider;
import org.keycloak.util.JsonSerialization;

/**
 * Sends Keycloak's email through the Zoho CPaaS (formerly ZeptoMail) email API over HTTPS,
 * for hosts that block outbound SMTP.
 *
 * <p>The realm's SMTP settings are the configuration, so nothing new is stored and the
 * admin console's <em>Test connection</em> exercises this path:
 *
 * <ul>
 *   <li>{@code host}: the API host of your account's data centre, e.g. {@code cpaas.zoho.com}
 *       or {@code cpaas.zoho.in}. A full URL is accepted too (how the tests reach a stub).
 *   <li>{@code password}: the mail agent's API key, as a value or a {@code ${vault.…}}
 *       reference, which the factory resolves. Sent as {@code Authorization: Zoho-enczapikey <key>}.
 *   <li>{@code from}, {@code fromDisplayName}, {@code replyTo}, {@code replyToDisplayName}:
 *       as for SMTP.
 *   <li>{@code port}, {@code starttls}, {@code ssl}, {@code auth}, {@code user},
 *       {@code envelopeFrom}: not used. The username field is a handy place for the agent's name.
 * </ul>
 *
 * <p>Keycloak hands over the subject, the text body and the HTML body as three strings,
 * and the API takes them as {@code subject}, {@code textbody} and {@code htmlbody}; that is
 * the whole translation. An answer other than 2xx is an {@link EmailException} carrying
 * the status and Zoho's error body, which names the error code (an unverified domain, a
 * bad key) and never contains the key.
 */
public final class ZohoCpaasEmailSenderProvider implements EmailSenderProvider {

    private static final Logger LOG = Logger.getLogger(ZohoCpaasEmailSenderProvider.class);

    static final String PATH = "/v1.1/email";
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final int ERROR_BODY_LIMIT = 600;

    private final HttpClient http;
    private final Function<String, String> secrets;

    ZohoCpaasEmailSenderProvider(HttpClient http, Function<String, String> secrets) {
        this.http = http;
        this.secrets = secrets;
    }

    @Override
    public void send(Map<String, String> config, String address, String subject, String textBody, String htmlBody)
            throws EmailException {
        validate(config);
        if (isBlank(address) || !looksLikeAnAddress(address)) {
            throw new EmailException("No valid recipient address to send to");
        }
        String key = secrets.apply(config.get("password"));
        if (isBlank(key)) {
            throw new EmailException("The Zoho CPaaS API key is not set: the realm's SMTP password is empty or its vault reference did not resolve");
        }

        String body;
        try {
            body = JsonSerialization.writeValueAsString(message(config, address, subject, textBody, htmlBody));
        } catch (IOException e) {
            throw new EmailException("Could not encode the email for Zoho CPaaS", e);
        }

        HttpRequest request = HttpRequest.newBuilder(endpoint(config.get("host")))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Zoho-enczapikey " + key)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new EmailException("Could not reach Zoho CPaaS at " + request.uri().getHost(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new EmailException("Interrupted while sending through Zoho CPaaS", e);
        }

        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            throw new EmailException("Zoho CPaaS refused the email: HTTP " + status + " " + clip(response.body()));
        }
        LOG.debugv("Sent through Zoho CPaaS: HTTP {0} {1}", status, clip(response.body()));
    }

    @Override
    public void validate(Map<String, String> config) throws EmailException {
        if (config == null || isBlank(config.get("host"))) {
            throw new EmailException("The Zoho CPaaS API host is not set: put your data centre's API host in the realm's SMTP host, e.g. cpaas.zoho.com");
        }
        String from = config.get("from");
        if (isBlank(from) || !looksLikeAnAddress(from)) {
            throw new EmailException("The from address is missing or not an email address");
        }
        String replyTo = config.get("replyTo");
        if (!isBlank(replyTo) && !looksLikeAnAddress(replyTo)) {
            throw new EmailException("The reply-to address is not an email address");
        }
        endpoint(config.get("host"));
    }

    @Override
    public void close() {
    }

    /** The request body, in the field names Zoho's {@code /v1.1/email} documents. */
    static Map<String, Object> message(Map<String, String> config, String address, String subject, String textBody, String htmlBody) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("from", party(config.get("from"), config.get("fromDisplayName")));

        Map<String, Object> recipient = new LinkedHashMap<>();
        recipient.put("email_address", party(address, null));
        List<Map<String, Object>> to = new ArrayList<>(1);
        to.add(recipient);
        message.put("to", to);

        String replyTo = config.get("replyTo");
        if (!isBlank(replyTo)) {
            message.put("reply_to", List.of(party(replyTo, config.get("replyToDisplayName"))));
        }

        message.put("subject", subject == null ? "" : subject);
        if (!isBlank(textBody)) {
            message.put("textbody", textBody);
        }
        if (!isBlank(htmlBody)) {
            message.put("htmlbody", htmlBody);
        }
        // Keycloak's email is transactional (verification, password reset): one-time,
        // personal links, so no open or click tracking.
        message.put("track_opens", false);
        message.put("track_clicks", false);
        return message;
    }

    private static Map<String, Object> party(String address, String name) {
        Map<String, Object> party = new LinkedHashMap<>();
        party.put("address", address);
        if (!isBlank(name)) {
            party.put("name", name);
        }
        return party;
    }

    /** {@code cpaas.zoho.com} becomes {@code https://cpaas.zoho.com/v1.1/email}; a URL is taken as written. */
    static URI endpoint(String host) throws EmailException {
        String trimmed = host.trim();
        String spec = trimmed.contains("://") ? trimmed : "https://" + trimmed + PATH;
        URI uri;
        try {
            uri = URI.create(spec);
        } catch (IllegalArgumentException e) {
            throw new EmailException("The Zoho CPaaS API host is not a valid host or URL: " + trimmed);
        }
        if (uri.getHost() == null) {
            throw new EmailException("The Zoho CPaaS API host is not a valid host or URL: " + trimmed);
        }
        return uri;
    }

    private static boolean looksLikeAnAddress(String value) {
        int at = value.indexOf('@');
        return at > 0 && at < value.length() - 1 && value.indexOf('@', at + 1) < 0 && !value.chars().anyMatch(Character::isWhitespace);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String clip(String body) {
        if (body == null) {
            return "";
        }
        String oneLine = body.strip().replaceAll("\\s+", " ");
        return oneLine.length() <= ERROR_BODY_LIMIT ? oneLine : oneLine.substring(0, ERROR_BODY_LIMIT) + "…";
    }
}
