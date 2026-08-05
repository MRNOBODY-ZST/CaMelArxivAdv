#!/usr/bin/env bash
set -euo pipefail

project_root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
compose_file="$project_root/docker-compose.yml"

if [[ ! -f "$compose_file" ]]; then
  echo "Missing root docker-compose.yml" >&2
  exit 1
fi

compose_json=$(docker compose --project-directory "$project_root" -f "$compose_file" config --format json)
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

echo "Compose contract verified for ${#required_services[@]} services"
