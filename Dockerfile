# syntax=docker/dockerfile:1

# Multi-stage build producing a single image with both backend jars (api,
# processor). See openspec/changes/00-bootstrap-monorepo/design.md,
# "Dos procesos, un artefacto": they share domain/geo-core and must always
# run the exact same build of that shared code, which two independently
# built images could not guarantee.

FROM eclipse-temurin:21-jdk AS builder
WORKDIR /workspace

# .git is part of the build context on purpose (see .dockerignore): the
# gradle-git-properties plugin applied in backend/build.gradle.kts reads it
# to embed the real commit SHA into git.properties, which api's
# /actuator/info exposes (task 5.2). The .git directory itself never reaches
# the runtime stage below.
COPY .git .git
COPY backend backend

WORKDIR /workspace/backend
# backend/gradlew is committed with its executable bit set (see git tree
# mode 100755) so this runs directly; sh is used as a defensive fallback in
# case a future checkout tool drops that bit again, matching the same class
# of Windows-checkout quirk already documented for @nx/gradle (task 1.4) and
# openapi-generator-cli (task 4.2).
RUN --mount=type=cache,target=/root/.gradle \
    sh gradlew --no-daemon --console=plain :api:bootJar :processor:bootJar \
    && mkdir -p /workspace/dist \
    && cp $(find api/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar') /workspace/dist/api.jar \
    && cp $(find processor/build/libs -maxdepth 1 -name '*.jar' ! -name '*-plain.jar') /workspace/dist/processor.jar

FROM eclipse-temurin:21-jre-alpine AS runtime

# Non-root user (task 6.4): neither jar ever runs as root.
RUN addgroup -S fleetpulse && adduser -S -G fleetpulse -h /app fleetpulse

WORKDIR /app
COPY --from=builder /workspace/dist/api.jar /app/api.jar
COPY --from=builder /workspace/dist/processor.jar /app/processor.jar
COPY docker/backend/entrypoint.sh docker/backend/healthcheck.sh /app/
RUN chmod +x /app/entrypoint.sh /app/healthcheck.sh \
    && chown -R fleetpulse:fleetpulse /app

USER fleetpulse
EXPOSE 8080

# See entrypoint.sh / healthcheck.sh: FLEETPULSE_PROCESS (or Fly.io's own
# FLY_PROCESS_GROUP) selects which jar runs and which check applies.
HEALTHCHECK --interval=10s --timeout=5s --start-period=45s --retries=5 \
    CMD ["/app/healthcheck.sh"]

ENTRYPOINT ["/app/entrypoint.sh"]
