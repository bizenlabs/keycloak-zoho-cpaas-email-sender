#!/usr/bin/env bash
# Runs the built jar inside a real Keycloak and checks that Keycloak's own
# "Test connection" email reaches a stub of the Zoho CPaaS API, as the API
# expects it. Needs Docker; run after `mvn package`.
#
#   scripts/integration-test.sh                 # Keycloak from pom.xml
#   KEYCLOAK_VERSION=26.8.2 scripts/integration-test.sh
set -euo pipefail

die() { echo "integration-test: $*" >&2; exit 1; }
cd "$(dirname "$0")/.."

KEYCLOAK_VERSION="${KEYCLOAK_VERSION:-$(sed -n 's:.*<keycloak.version>\(.*\)</keycloak.version>.*:\1:p' pom.xml | head -1)}"
JAR="$(find target -maxdepth 1 -name "keycloak-zoho-cpaas-email-sender-*-keycloak-${KEYCLOAK_VERSION}.jar" 2>/dev/null | head -1)"
[ -n "$JAR" ] || die "no jar for Keycloak $KEYCLOAK_VERSION in target/; run: mvn package -Dkeycloak.version=$KEYCLOAK_VERSION"

NET="zoho-cpaas-it-$$"
STUB="zoho-cpaas-stub-$$"
KC="zoho-cpaas-keycloak-$$"
PORT="${PORT:-18080}"
cleanup() { docker rm -f "$KC" "$STUB" >/dev/null 2>&1 || true; docker network rm "$NET" >/dev/null 2>&1 || true; }
trap cleanup EXIT

docker network create "$NET" >/dev/null

# The stub: answers like Zoho's /v1.1/email, and prints each request as one JSON line.
docker run -d --name "$STUB" --network "$NET" python:3.13-slim python -u -c '
import json
from http.server import BaseHTTPRequestHandler, HTTPServer
class H(BaseHTTPRequestHandler):
    def do_POST(self):
        body = self.rfile.read(int(self.headers["Content-Length"])).decode()
        print(json.dumps({"path": self.path, "auth": self.headers.get("Authorization"),
                          "type": self.headers.get("Content-Type"), "body": json.loads(body)}), flush=True)
        out = b"{\"data\":[{\"code\":\"EM_104\",\"message\":\"OK\"}],\"message\":\"OK\",\"request_id\":\"stub\"}"
        self.send_response(200); self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(out))); self.end_headers(); self.wfile.write(out)
    def log_message(self, *a): pass
HTTPServer(("0.0.0.0", 8080), H).serve_forever()
' >/dev/null

docker run -d --name "$KC" --network "$NET" -p "127.0.0.1:${PORT}:8080" \
  -v "$PWD/$JAR:/opt/keycloak/providers/$(basename "$JAR"):ro" \
  -e KC_BOOTSTRAP_ADMIN_USERNAME=admin -e KC_BOOTSTRAP_ADMIN_PASSWORD=admin \
  -e KC_SPI_EMAIL_SENDER__PROVIDER_DEFAULT=zoho-cpaas \
  "quay.io/keycloak/keycloak:${KEYCLOAK_VERSION}" start-dev >/dev/null

echo "Keycloak ${KEYCLOAK_VERSION} with $(basename "$JAR"): starting"
for _ in $(seq 90); do
  curl -sf "http://127.0.0.1:${PORT}/realms/master" >/dev/null 2>&1 && break
  sleep 2
done
curl -sf "http://127.0.0.1:${PORT}/realms/master" >/dev/null || { docker logs "$KC" | tail -30; die "Keycloak did not start"; }

token() {
  curl -sf -d grant_type=password -d client_id=admin-cli -d username=admin -d password=admin \
    "http://127.0.0.1:${PORT}/realms/master/protocol/openid-connect/token" \
    | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}
admin() { curl -s -H "Authorization: Bearer $(token)" -H "Content-Type: application/json" "$@"; }

# Test connection sends to the signed-in admin's own address, so give them one.
uid="$(admin "http://127.0.0.1:${PORT}/admin/realms/master/users?username=admin&exact=true" \
  | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["id"])')"
admin -X PUT "http://127.0.0.1:${PORT}/admin/realms/master/users/${uid}" \
  -d '{"email":"admin@example.invalid","firstName":"Test","lastName":"Admin","emailVerified":true}' >/dev/null

# The realm's SMTP settings, as this provider reads them: host is the API (here
# the stub, as a URL), password is the key.
status="$(admin -o /dev/null -w '%{http_code}' -X POST "http://127.0.0.1:${PORT}/admin/realms/master/testSMTPConnection" \
  -d "{\"host\":\"http://${STUB}:8080/v1.1/email\",\"from\":\"no-reply@example.com\",\"fromDisplayName\":\"Example\",\"password\":\"it-test-key\"}")"
[ "$status" = 204 ] || { docker logs "$KC" 2>&1 | grep -iE "email|zoho" | tail -10; die "Test connection answered HTTP $status, not 204"; }

sleep 1
line="$(docker logs "$STUB" 2>&1 | grep '^{' | tail -1)"
[ -n "$line" ] || die "Keycloak answered 204 but nothing reached the stub: is the provider in use?"
python3 - "$line" <<'PY'
import json, sys
seen = json.loads(sys.argv[1])
body = seen["body"]
checks = {
    "path is /v1.1/email": seen["path"] == "/v1.1/email",
    "Authorization: Zoho-enczapikey <key>": seen["auth"] == "Zoho-enczapikey it-test-key",
    "JSON body": (seen["type"] or "").startswith("application/json"),
    "from": body.get("from") == {"address": "no-reply@example.com", "name": "Example"},
    "to the admin": body.get("to") == [{"email_address": {"address": "admin@example.invalid"}}],
    "a subject": bool(body.get("subject")),
    "a text or HTML body": bool(body.get("textbody") or body.get("htmlbody")),
    "the key is not in the body": "it-test-key" not in json.dumps(body),
}
for name, ok in checks.items():
    print(("  ok    " if ok else "  FAIL  ") + name)
sys.exit(0 if all(checks.values()) else 1)
PY
echo "Keycloak ${KEYCLOAK_VERSION} sent its test email through the provider."
