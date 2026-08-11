# syntax=docker/dockerfile:1.7
FROM ghcr.io/astral-sh/uv:0.11.21 AS uv
FROM python:3.12.10-slim-bookworm AS builder

COPY --from=uv /uv /uvx /bin/
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy
WORKDIR /workspace/worker
COPY worker/pyproject.toml worker/uv.lock ./
RUN uv sync --frozen --no-dev --no-install-project
COPY worker/src ./src
RUN uv sync --frozen --no-dev --no-editable

FROM python:3.12.10-slim-bookworm AS runtime

RUN groupadd --system camel && useradd --system --gid camel --home-dir /app --create-home camel
WORKDIR /app
COPY --from=builder --chown=camel:camel /workspace/worker/.venv /app/.venv

ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
USER camel
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
  CMD python -c "import app.main; import app.personalization.main" || exit 1

ENTRYPOINT ["python", "-m", "app.main"]
