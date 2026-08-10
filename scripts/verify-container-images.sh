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
  -c "import ray; from app.main import run; from app.arxiv.oai_client import OaiClient; from app.arxiv.source_downloader import SourceDownloader; from app.extraction.archive_guard import ArchiveLimits; from app.jobs.source_extraction import SourceExtractionRunner; from app.messaging.contracts import MessageType; from app.personalization.main import run as run_personalization; from app.personalization.ray_executor import RayPersonalizationExecutor; assert MessageType.ARXIV_SYNC_TAXONOMY.value == 'ARXIV_SYNC_TAXONOMY'; assert MessageType.ARXIV_FETCH_AND_PARSE_SOURCE.value == 'ARXIV_FETCH_AND_PARSE_SOURCE'; print('worker and Ray personalization imports verified')"

echo "Container image contracts verified for ${#images[@]} images"
