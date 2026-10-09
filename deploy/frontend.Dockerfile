# Build stage: the immutable dist is produced here, never inside the running container.
FROM node:24-alpine AS build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend ./
RUN npm run build

# Pin the rate-limit module so moving nginx to Caddy preserves the edge login budget.
FROM caddy:2.11.7-builder-alpine AS proxy-build
RUN xcaddy build v2.11.7 --with github.com/mholt/caddy-ratelimit@5625512f24f6f59d6f64fb3aafe5eecff0b286db

# Runtime stage: Caddy serves the static bundle and proxies the API.
FROM caddy:2.11.7-alpine

ARG INFRADESK_GIT_SHA=unknown
ARG INFRADESK_BUILD_VERSION=0.1.0-SNAPSHOT
LABEL org.opencontainers.image.version=${INFRADESK_BUILD_VERSION} org.opencontainers.image.revision=${INFRADESK_GIT_SHA}
ENV INFRADESK_TRUSTED_PROXY_CIDR=172.28.0.1/32 \
    TZ=UTC

COPY --from=proxy-build /usr/bin/caddy /usr/bin/caddy
COPY deploy/caddy/Caddyfile /etc/caddy/Caddyfile
COPY --from=build /workspace/frontend/dist /srv

EXPOSE 80 443

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget --quiet --spider http://127.0.0.1:8081/index.html || exit 1
