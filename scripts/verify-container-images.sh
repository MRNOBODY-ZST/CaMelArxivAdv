#!/usr/bin/env bash
set -euo pipefail

images=(
  camel-arxiv/backend:dev
  camel-arxiv/arxiv-worker:dev
  camel-arxiv/frontend:dev
)

for image in "${images[@]}"; do
  configured_user=$(docker image inspect "$image" --format '{{.Config.User}}')
  if [[ -z "$configured_user" || "$configured_user" == "0" || "$configured_user" == "root" ]]; then
    echo "$image must declare a non-root runtime user" >&2
    exit 1
  fi
done

docker run --rm --entrypoint python camel-arxiv/arxiv-worker:dev \
  -c "from app.main import run; from app.arxiv.oai_client import OaiClient; from app.messaging.contracts import MessageType; assert MessageType.ARXIV_SYNC_TAXONOMY.value == 'ARXIV_SYNC_TAXONOMY'; print('arxiv worker phase-three imports verified')"

echo "Container image contracts verified for ${#images[@]} images"
