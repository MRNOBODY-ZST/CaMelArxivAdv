#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file="$project_root/docker-compose.yml"
development_compose_file="$project_root/docker-compose.dev.yml"

# Contract rendering uses deterministic non-production values. The Compose file itself
# must reject missing runtime authentication keys.
export JWT_SIGNING_KEY_BASE64=${JWT_SIGNING_KEY_BASE64:-MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=}
export AUTH_FINGERPRINT_HMAC_KEY_BASE64=${AUTH_FINGERPRINT_HMAC_KEY_BASE64:-YWJjZGVmMDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODk=}

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
required_services=(postgres redis rabbitmq minio backend-api mail-worker arxiv-worker frontend mailpit)

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

for backend_service in backend-api mail-worker; do
  if [[ $(jq -r --arg service "$backend_service" '.services[$service].environment.AUTH_COOKIE_SECURE' <<<"$compose_json") != "true" ]]; then
    echo "$backend_service must require secure authentication cookies in the production baseline" >&2
    exit 1
  fi
  if [[ -z $(jq -r --arg service "$backend_service" '.services[$service].environment.JWT_SIGNING_KEY_BASE64' <<<"$compose_json") ]]; then
    echo "$backend_service must receive JWT signing key material" >&2
    exit 1
  fi
done

if ! grep -Fq 'JWT_SIGNING_KEY_BASE64: ${JWT_SIGNING_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require JWT signing key material" >&2
  exit 1
fi

if ! grep -Fq 'AUTH_FINGERPRINT_HMAC_KEY_BASE64: ${AUTH_FINGERPRINT_HMAC_KEY_BASE64:?' "$compose_file"; then
  echo "Production Compose must require authentication fingerprint key material" >&2
  exit 1
fi

if grep -Fq '$proxy_add_x_forwarded_for' "$project_root/infra/nginx/default.conf"; then
  echo "Edge Nginx must not trust a client-supplied X-Forwarded-For chain" >&2
  exit 1
fi

for internal_service in postgres redis rabbitmq minio backend-api mail-worker arxiv-worker mailpit; do
  if [[ $(jq -r --arg service "$internal_service" '.services[$service] | has("ports")' <<<"$compose_json") == "true" ]]; then
    echo "$internal_service must not publish production ports" >&2
    exit 1
  fi
done

if [[ $(jq -r '.services.frontend.ports[0].target' <<<"$compose_json") != "8080" ]]; then
  echo "frontend must be the only production ingress on container port 8080" >&2
  exit 1
fi

for app_service in backend-api mail-worker arxiv-worker frontend; do
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
