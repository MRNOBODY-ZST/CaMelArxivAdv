#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file="$project_root/docker-compose.yml"
development_compose_file="$project_root/docker-compose.dev.yml"

# Contract rendering uses deterministic non-production values. The Compose file itself
# must reject missing runtime authentication keys.
export JWT_SIGNING_KEY_BASE64=${JWT_SIGNING_KEY_BASE64:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}
export AUTH_FINGERPRINT_HMAC_KEY_BASE64=${AUTH_FINGERPRINT_HMAC_KEY_BASE64:-YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=}
export APP_ENCRYPTION_KEY_BASE64=${APP_ENCRYPTION_KEY_BASE64:-QUJDREVGR0hJSktMTU5PUFFSU1RVVldYWVowMTIzNDU=}
export APP_EMAIL_HMAC_KEY_BASE64=${APP_EMAIL_HMAC_KEY_BASE64:-emFiY2RlZjAxMjM0NTY3ODlhYmNkZWYwMTIzNDU2Nzg=}
export TEMPLATE_ASSET_SIGNING_KEY_BASE64=${TEMPLATE_ASSET_SIGNING_KEY_BASE64:-c2lnbmVkYXNzZXRzMDEyMzQ1Njc4OWFiY2RlZjAxMjM=}

if [[ ! -f "$compose_file" ]]; then
  echo "Missing root docker-compose.yml" >&2
  exit 1
fi

compose_json=$(docker compose --project-directory "$project_root" -f "$compose_file" config --format json)
development_compose_json=$(
  docker compose \
    --project-directory "$project_root" \
    -f "$compose_file" \
    -f "$development_compose_file" \
    config --format json
)
actual_services=$(jq -r '.services | keys[]' <<<"$compose_json")
required_services=(postgres redis rabbitmq minio backend-api mail-worker arxiv-worker ray-head ray-worker personalization-worker frontend mailpit)

for service in "${required_services[@]}"; do
  if ! grep -qx "$service" <<<"$actual_services"; then
    echo "Missing required service: $service" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services["mail-worker"].environment.ALLOW_LIVE_SMTP' <<<"$compose_json") != "false" ]]; then
  echo "mail-worker must disable live SMTP by default" >&2
  exit 1
fi

if [[ $(jq -r '.services["backend-api"].environment.ALLOW_LIVE_SMTP' <<<"$compose_json") != "false" ]]; then
  echo "backend-api must disable live SMTP by default" >&2
  exit 1
fi

if [[ $(jq -r '.services["backend-api"].environment.TEMPLATE_ASSET_BUCKET' <<<"$compose_json") != "template-assets" ]]; then
  echo "backend-api must use the dedicated private template asset bucket" >&2
  exit 1
fi

if [[ $(jq -r '.services["backend-api"].environment.SMTP_LOCAL_ALLOWED_HOSTS' <<<"$compose_json") != *"mailpit"* ]]; then
  echo "backend-api local SMTP allowlist must include Mailpit" >&2
  exit 1
fi

for backend_service in backend-api mail-worker; do
  if [[ $(jq -r --arg service "$backend_service" '.services[$service].environment.AUTH_COOKIE_SECURE' <<<"$compose_json") != "true" ]]; then
    echo "$backend_service must require secure authentication cookies in the production baseline" >&2
    exit 1
  fi
  if [[ -z $(jq -r --arg service "$backend_service" '.services[$service].environment.JWT_SIGNING_KEY_BASE64' <<<"$compose_json") ]]; then
    echo "$backend_service must receive JWT signing key material" >&2
    exit 1
  fi
  if [[ -z $(jq -r --arg service "$backend_service" '.services[$service].environment.APP_ENCRYPTION_KEY_BASE64' <<<"$compose_json") ]]; then
    echo "$backend_service must receive contact encryption key material" >&2
    exit 1
  fi
  if [[ -z $(jq -r --arg service "$backend_service" '.services[$service].environment.APP_EMAIL_HMAC_KEY_BASE64' <<<"$compose_json") ]]; then
    echo "$backend_service must receive contact HMAC key material" >&2
    exit 1
  fi
  if [[ -z $(jq -r --arg service "$backend_service" '.services[$service].environment.TEMPLATE_ASSET_SIGNING_KEY_BASE64' <<<"$compose_json") ]]; then
    echo "$backend_service must receive template asset signing key material" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services["backend-api"].profiles // [] | length' <<<"$compose_json") != "0" ]]; then
  echo "backend-api must be present in the default production profile" >&2
  exit 1
fi

if [[ $(jq -r '.services["backend-api"].environment.SPRING_PROFILES_ACTIVE' <<<"$compose_json") != "api" ]]; then
  echo "backend-api must activate only the API runtime profile" >&2
  exit 1
fi

if [[ $(jq -r '.services["mail-worker"].environment.SPRING_PROFILES_ACTIVE' <<<"$compose_json") != "mail-worker" ]]; then
  echo "mail-worker must activate only the isolated worker runtime profile" >&2
  exit 1
fi

if [[ $(jq -r '.services["arxiv-worker"].environment.ARXIV_WORKER_MIN_REQUEST_INTERVAL_SECONDS' <<<"$compose_json") != "3" ]]; then
  echo "arxiv-worker must enforce the three-second official request floor" >&2
  exit 1
fi

if [[ $(jq -r '.services["arxiv-worker"].environment.ARXIV_WORKER_SOURCE_BASE_URL' <<<"$compose_json") != "https://export.arxiv.org/e-print" ]]; then
  echo "arxiv-worker must use the official arXiv Source endpoint" >&2
  exit 1
fi

if [[ $(jq -r '.services["arxiv-worker"].environment.ARXIV_WORKER_TEMP_ROOT' <<<"$compose_json") != "/var/tmp/arxiv-source" ]]; then
  echo "arxiv-worker must use the bounded Source temporary root" >&2
  exit 1
fi

if [[ $(jq -r '(.services["personalization-worker"].entrypoint // []) | join(" ")' <<<"$compose_json") != "python -m app.personalization.main" ]]; then
  echo "personalization-worker must use the isolated personalization entrypoint" >&2
  exit 1
fi

if [[ $(jq -r '.services["personalization-worker"].environment.PERSONALIZATION_RAY_ADDRESS' <<<"$compose_json") != "ray://ray-head:10001" ]]; then
  echo "personalization-worker must connect to the internal Ray Client endpoint" >&2
  exit 1
fi

for ray_service in ray-head ray-worker; do
  if [[ $(jq -r --arg service "$ray_service" '(.services[$service].entrypoint // []) | join(" ")' <<<"$compose_json") != "python -m ray.scripts.scripts" ]]; then
    echo "$ray_service must use the relocatable Ray module entrypoint" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services["backend-api"].environment.RAY_CONFIGURED' <<<"$compose_json") != "true" ]]; then
  echo "backend-api must report the configured Ray runtime" >&2
  exit 1
fi

if ! jq -e '.services["arxiv-worker"].tmpfs | index("/var/tmp/arxiv-source:size=536870912,mode=1777")' <<<"$compose_json" >/dev/null; then
  echo "arxiv-worker must mount the bounded Source temporary root as tmpfs" >&2
  exit 1
fi

for source_limit in \
  ARXIV_WORKER_MAX_ARCHIVE_BYTES \
  ARXIV_WORKER_MAX_EXTRACTED_BYTES \
  ARXIV_WORKER_MAX_SINGLE_FILE_BYTES \
  ARXIV_WORKER_MAX_FILE_COUNT \
  ARXIV_WORKER_MAX_COMPRESSION_RATIO; do
  if [[ -z $(jq -r --arg key "$source_limit" '.services["arxiv-worker"].environment[$key]' <<<"$compose_json") ]]; then
    echo "arxiv-worker must receive $source_limit" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services["backend-api"].environment.ARXIV_LEGACY_BASE_URL' <<<"$compose_json") != "https://export.arxiv.org/api/query" ]]; then
  echo "backend-api must use the official arXiv Legacy API endpoint" >&2
  exit 1
fi

if [[ $(jq -r '.services["backend-api"].environment.ARXIV_OAI_BASE_URL' <<<"$compose_json") != "https://oaipmh.arxiv.org/oai" ]]; then
  echo "backend-api must use the official arXiv OAI endpoint" >&2
  exit 1
fi

if ! grep -Fq 'JWT_SIGNING_KEY_BASE64: ${JWT_SIGNING_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require JWT signing key material" >&2
  exit 1
fi

if ! grep -Fq 'AUTH_FINGERPRINT_HMAC_KEY_BASE64: ${AUTH_FINGERPRINT_HMAC_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require authentication fingerprint key material" >&2
  exit 1
fi

if ! grep -Fq 'APP_ENCRYPTION_KEY_BASE64: ${APP_ENCRYPTION_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require contact encryption key material" >&2
  exit 1
fi

if ! grep -Fq 'APP_EMAIL_HMAC_KEY_BASE64: ${APP_EMAIL_HMAC_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require contact HMAC key material" >&2
  exit 1
fi

if ! grep -Fq 'TEMPLATE_ASSET_SIGNING_KEY_BASE64: ${TEMPLATE_ASSET_SIGNING_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require template asset signing key material" >&2
  exit 1
fi

if grep -Fq '$proxy_add_x_forwarded_for' "$project_root/infra/nginx/default.conf"; then
  echo "Edge Nginx must not trust a client-supplied X-Forwarded-For chain" >&2
  exit 1
fi

signed_asset_location=$(
  sed -n '/location \^~ \/api\/v1\/template-assets\//,/^    }/p' \
    "$project_root/infra/nginx/default.conf"
)
if [[ -z "$signed_asset_location" ]] || ! grep -Fq 'access_log off;' <<<"$signed_asset_location"; then
  echo "Edge Nginx must suppress access logs for signed template asset capability URLs" >&2
  exit 1
fi

for internal_service in postgres redis rabbitmq minio backend-api mail-worker arxiv-worker ray-head ray-worker personalization-worker mailpit; do
  if [[ $(jq -r --arg service "$internal_service" '.services[$service] | has("ports")' <<<"$compose_json") == "true" ]]; then
    echo "$internal_service must not publish production ports" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services.frontend.ports[0].target' <<<"$compose_json") != "8080" ]]; then
  echo "frontend must be the only production ingress on container port 8080" >&2
  exit 1
fi

for app_service in backend-api mail-worker arxiv-worker ray-head ray-worker personalization-worker frontend; do
  if [[ $(jq -r --arg service "$app_service" '.services[$service] | has("healthcheck")' <<<"$compose_json") != "true" ]]; then
    echo "$app_service must declare a healthcheck" >&2
    exit 1
  fi
done

if [[ $(jq -r '(.services["arxiv-worker"].entrypoint // []) | join(" ")' <<<"$development_compose_json") != "python -m app.main" ]]; then
  echo "arxiv-worker must use a relocatable Python module entrypoint" >&2
  exit 1
fi

for development_service in mailpit minio; do
  if [[ $(jq -r --arg service "$development_service" '.services[$service].networks | has("edge")' <<<"$development_compose_json") != "true" ]]; then
    echo "$development_service must join the edge network when its development port is published" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services.mailpit.ports[0].target' <<<"$development_compose_json") != "8025" ]]; then
  echo "Mailpit development UI must publish container port 8025" >&2
  exit 1
fi

if [[ $(jq -r '.services.minio.ports[0].target' <<<"$development_compose_json") != "9001" ]]; then
  echo "MinIO development console must publish container port 9001" >&2
  exit 1
fi

for backend_service in backend-api mail-worker; do
  if [[ $(jq -r --arg service "$backend_service" '.services[$service].environment.AUTH_COOKIE_SECURE' <<<"$development_compose_json") != "false" ]]; then
    echo "$backend_service localhost development cookie must explicitly disable Secure" >&2
    exit 1
  fi
done

echo "Compose contract verified for ${#required_services[@]} services"
