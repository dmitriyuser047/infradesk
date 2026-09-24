# Build stage: the immutable dist is produced here, never inside the running container.
FROM node:24-alpine AS build

WORKDIR /workspace/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci

COPY frontend ./
RUN npm run build

# Runtime stage: nginx serves the static bundle and proxies the API.
FROM nginx:1.29-alpine

ARG INFRADESK_GIT_SHA=unknown
LABEL org.opencontainers.image.revision=${INFRADESK_GIT_SHA}
ENV INFRADESK_TRUSTED_PROXY_CIDR=172.28.0.1/32 \
    TZ=UTC

# The official nginx entrypoint renders this template from the small, explicit environment
# contract above. Nginx's own variables remain untouched by its allow-list based envsubst step.
COPY deploy/nginx/infradesk.conf /etc/nginx/templates/default.conf.template
COPY deploy/nginx/infradesk-proxy.inc /etc/nginx/conf.d/infradesk-proxy.inc
COPY --from=build /workspace/frontend/dist /usr/share/nginx/html

EXPOSE 80

HEALTHCHECK --interval=15s --timeout=5s --start-period=10s --retries=5 \
  CMD wget --quiet --spider http://127.0.0.1/index.html || exit 1
