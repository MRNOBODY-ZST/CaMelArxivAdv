# syntax=docker/dockerfile:1.7
FROM node:24.10.0-alpine3.22 AS builder

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --ignore-scripts
COPY frontend/ ./
RUN npm run build

FROM nginxinc/nginx-unprivileged:1.29.1-alpine AS runtime

COPY --chown=101:101 infra/nginx/default.conf /etc/nginx/conf.d/default.conf
COPY --from=builder --chown=101:101 /workspace/frontend/dist /usr/share/nginx/html

USER 101
EXPOSE 8080
HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget -qO- http://127.0.0.1:8080/healthz >/dev/null || exit 1
