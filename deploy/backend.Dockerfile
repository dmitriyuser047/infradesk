# Build stage: sbt and the source tree live here and never reach the runtime image.
FROM eclipse-temurin:21-jdk AS build

ARG INFRADESK_BUILD_VERSION=0.1.0-SNAPSHOT
ENV INFRADESK_BUILD_VERSION=${INFRADESK_BUILD_VERSION}

RUN apt-get update \
 && apt-get install --no-install-recommends --assume-yes curl gnupg ca-certificates \
 && curl -fsSL https://github.com/sbt/sbt/releases/download/v1.11.7/sbt-1.11.7.tgz -o /tmp/sbt.tgz \
 && tar -xzf /tmp/sbt.tgz -C /opt \
 && rm -rf /tmp/sbt.tgz /var/lib/apt/lists/*
ENV PATH="/opt/sbt/bin:${PATH}"

WORKDIR /workspace

# Dependency resolution is cached separately from the sources it later compiles.
COPY build.sbt ./
COPY project/build.properties project/plugins.sbt project/
RUN sbt -batch --no-server update

COPY src src
RUN sbt -batch --no-server "Universal/stage" \
 && mkdir -p /opt/infradesk \
 && cp -r "$(find target -type d -path '*/universal/stage' | head -n 1)"/. /opt/infradesk/

# Runtime stage: a JRE, the staged artifact, and nothing else.
FROM eclipse-temurin:21-jre

ARG INFRADESK_BUILD_VERSION=0.1.0-SNAPSHOT
ARG INFRADESK_GIT_SHA=unknown
ENV INFRADESK_BUILD_VERSION=${INFRADESK_BUILD_VERSION} \
    INFRADESK_GIT_SHA=${INFRADESK_GIT_SHA} \
    TZ=UTC \
    LANG=C.UTF-8 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -Dfile.encoding=UTF-8 -Duser.timezone=UTC"

# curl is the one runtime addition: the container healthcheck needs a client, and the image
# should not depend on the JRE having one.
RUN apt-get update  && apt-get install --no-install-recommends --assume-yes curl  && rm -rf /var/lib/apt/lists/*  && useradd --system --create-home --uid 10001 infradesk
COPY --from=build --chown=infradesk:infradesk /opt/infradesk /opt/infradesk

USER infradesk
WORKDIR /opt/infradesk
EXPOSE 8080

HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl --fail --silent --show-error http://127.0.0.1:8080/health || exit 1

# The built artifact, never a source build.
ENTRYPOINT ["/opt/infradesk/bin/infradesk"]
