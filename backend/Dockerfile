# syntax=docker/dockerfile:1
# The backend image (ADR-0008): one image for Fly.io today and any container host later.
# Build from the repository root, because the API interfaces are generated from api/openapi.yaml:
#   docker build -f backend/Dockerfile -t moneytrail-backend .
# Base images are pinned by digest; Renovate keeps the digests current.

FROM eclipse-temurin:25-jdk-noble@sha256:95e350ad1eb738f943e141640f7e3d797e483359a3b33c3864255bfd1ad71f8a AS build
WORKDIR /workspace
COPY api/ api/
COPY backend/ backend/
WORKDIR /workspace/backend
# Only the jar: the tests already ran in CI before the image is built.
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew bootJar --no-daemon --console=plain
# Split the jar into layers ordered by how often they change (libraries rarely, our code
# often), so an update only rebuilds and uploads the layers that changed.
RUN java -Djarmode=tools -jar build/libs/moneytrail.jar extract --layers --destination /workspace/extracted

FROM eclipse-temurin:25-jre-noble@sha256:a98128a57de944a72bd31fbc79b898edd5a5864d3652071d5bbd2160ab7d7dce
# Ubuntu security fixes released after the base image was built: the CI scan fails on fixable
# HIGH and CRITICAL vulnerabilities, and the base image is only rebuilt every few weeks.
RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get upgrade -y --no-install-recommends \
    && rm -rf /var/lib/apt/lists/*
# A user without a shell or home: if someone breaks into the app, they are not root.
RUN useradd --system --uid 10001 --no-create-home --shell /usr/sbin/nologin moneytrail
WORKDIR /app
COPY --from=build /workspace/extracted/dependencies/ ./
COPY --from=build /workspace/extracted/spring-boot-loader/ ./
COPY --from=build /workspace/extracted/snapshot-dependencies/ ./
COPY --from=build /workspace/extracted/application/ ./
USER 10001
EXPOSE 8080
# The heap gets 75% of the machine's memory; metaspace, threads and the OS need the rest.
# On OutOfMemoryError the process exits, so the platform restarts it instead of leaving a
# half-working JVM running.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-jar", "moneytrail.jar"]
