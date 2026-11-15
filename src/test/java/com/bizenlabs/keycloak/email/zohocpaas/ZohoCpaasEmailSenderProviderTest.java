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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.keycloak.email.EmailException;
import org.keycloak.util.JsonSerialization;

/**
 * The contract with Zoho's {@code /v1.1/email}, checked against a stub that records what
 * arrives: the path, the two headers that matter, and the body in Zoho's field names.
 */
class ZohoCpaasEmailSenderProviderTest {

    private HttpServer server;
    private final AtomicReference<HttpExchange> seen = new AtomicReference<>();
    private final AtomicReference<String> seenBody = new AtomicReference<>();
    private volatile int answerStatus = 200;
    private volatile String answerBody = "{\"data\":[{\"code\":\"EM_104\",\"message\":\"OK\"}],\"message\":\"OK\",\"request_id\":\"req-1\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                seenBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            seen.set(exchange);
            byte[] out = answerBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(answerStatus, out.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(out);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + ZohoCpaasEmailSenderProvider.PATH;
    }

    private Map<String, String> realmSmtpConfig(String host) {
        Map<String, String> config = new HashMap<>();
        config.put("host", host);
        config.put("port", "443");
        config.put("from", "no-reply@example.com");
        config.put("fromDisplayName", "Example");
        config.put("user", "mail-agent");
        config.put("password", "${vault.smtpPassword}");
        return config;
    }

    private ZohoCpaasEmailSenderProvider provider(Map<String, String> vault) {
        // As the factory does: a vault reference resolves or yields null; a plain value is itself.
        return new ZohoCpaasEmailSenderProvider(HttpClient.newHttpClient(),
                reference -> reference != null && reference.startsWith("${vault.") ? vault.get(reference) : reference);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sendsTheThreeStringsAsZohoFields() throws Exception {
        ZohoCpaasEmailSenderProvider p = provider(Map.of("${vault.smtpPassword}", "k3y"));

        p.send(realmSmtpConfig(url()), "person@example.invalid", "Verify email",
                "Click https://app.example/verify", "<p>Click <a href=\"https://app.example/verify\">here</a></p>");

        HttpExchange x = seen.get();
        assertNotNull(x, "nothing reached the stub");
        assertEquals("POST", x.getRequestMethod());
        assertEquals(ZohoCpaasEmailSenderProvider.PATH, x.getRequestURI().getPath());
        assertEquals("Zoho-enczapikey k3y", x.getRequestHeaders().getFirst("Authorization"));
        assertEquals("application/json", x.getRequestHeaders().getFirst("Content-Type"));

        Map<String, Object> body = JsonSerialization.readValue(seenBody.get(), Map.class);
        assertEquals(Map.of("address", "no-reply@example.com", "name", "Example"), body.get("from"));
        assertEquals(List.of(Map.of("email_address", Map.of("address", "person@example.invalid"))), body.get("to"));
        assertEquals("Verify email", body.get("subject"));
        assertEquals("Click https://app.example/verify", body.get("textbody"));
        assertTrue(((String) body.get("htmlbody")).contains("<a href"));
        assertEquals(Boolean.FALSE, body.get("track_clicks"));
        assertFalse(body.containsKey("reply_to"), "no reply-to was configured");
        assertFalse(seenBody.get().contains("k3y"), "the key is a header, never part of the message");
    }

    @Test
    @SuppressWarnings("unchecked")
    void carriesReplyToWhenTheRealmSetsOne() throws Exception {
        Map<String, String> config = realmSmtpConfig(url());
        config.put("replyTo", "help@example.com");
        config.put("replyToDisplayName", "Example support");

        provider(Map.of("${vault.smtpPassword}", "k3y")).send(config, "person@example.invalid", "s", "t", null);

        Map<String, Object> body = JsonSerialization.readValue(seenBody.get(), Map.class);
        assertEquals(List.of(Map.of("address", "help@example.com", "name", "Example support")), body.get("reply_to"));
        assertFalse(body.containsKey("htmlbody"), "an absent HTML body is left out, not sent empty");
    }

    @Test
    void aBareHostBecomesTheApiEndpoint() throws Exception {
        assertEquals("https://cpaas.zoho.in/v1.1/email", ZohoCpaasEmailSenderProvider.endpoint("cpaas.zoho.in").toString());
        assertEquals("https://cpaas.zoho.in/v1.1/email", ZohoCpaasEmailSenderProvider.endpoint(" cpaas.zoho.in ").toString());
        assertEquals("https://cpaas.zoho.com/v1.1/email", ZohoCpaasEmailSenderProvider.endpoint("cpaas.zoho.com").toString());
        assertEquals("http://127.0.0.1:1/v1.1/email", ZohoCpaasEmailSenderProvider.endpoint("http://127.0.0.1:1/v1.1/email").toString());
        assertThrows(EmailException.class, () -> ZohoCpaasEmailSenderProvider.endpoint("not a host"));
    }

    @Test
    void zohosRefusalIsReportedWithItsCodeAndWithoutTheKey() {
        answerStatus = 400;
        answerBody = "{\"data\":{\"error_code\":\"TM_3004\",\"message\":\"Invalid request\"},\"message\":\"error\"}";

        EmailException e = assertThrows(EmailException.class, () -> provider(Map.of("${vault.smtpPassword}", "k3y"))
                .send(realmSmtpConfig(url()), "person@example.invalid", "s", "t", "h"));

        assertTrue(e.getMessage().contains("HTTP 400"), e.getMessage());
        assertTrue(e.getMessage().contains("TM_3004"), e.getMessage());
        assertFalse(e.getMessage().contains("k3y"), e.getMessage());
    }

    @Test
    void anUnresolvedVaultReferenceIsAConfigurationErrorNotARequest() {
        EmailException e = assertThrows(EmailException.class, () -> provider(Map.of())
                .send(realmSmtpConfig(url()), "person@example.invalid", "s", "t", "h"));

        assertTrue(e.getMessage().contains("API key is not set"), e.getMessage());
        assertEquals(null, seen.get(), "nothing should have been sent");
    }

    @Test
    void anUnreachableHostIsReportedByName() {
        EmailException e = assertThrows(EmailException.class, () -> provider(Map.of("${vault.smtpPassword}", "k3y"))
                .send(realmSmtpConfig("http://127.0.0.1:1/v1.1/email"), "person@example.invalid", "s", "t", "h"));

        assertTrue(e.getMessage().contains("Could not reach"), e.getMessage());
    }

    @Test
    void validateChecksWhatTheConsoleSaves() {
        ZohoCpaasEmailSenderProvider p = provider(Map.of());
        assertThrows(EmailException.class, () -> p.validate(null));
        assertThrows(EmailException.class, () -> p.validate(Map.of("from", "no-reply@example.com")));
        assertThrows(EmailException.class, () -> p.validate(Map.of("host", "cpaas.zoho.in", "from", "nobody")));
        assertThrows(EmailException.class, () -> p.validate(Map.of("host", "cpaas.zoho.in", "from", "a@b.c", "replyTo", "x")));
        assertThrows(EmailException.class, () -> p.send(realmSmtpConfig(url()), "", "s", "t", "h"));
    }
}
