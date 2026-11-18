# ScholarChain REST services image (ECS Fargate, linux/arm64).
#
# Build context: modules/services/target/docker-context, staged by `sbt services/dockerStage`
# (no Docker). It holds exactly app.jar, config/course.json and context.sha256.
# The image is built by scripts/package.sh (and, for local use, `sbt dockerLocal`) with
#   docker buildx build --platform linux/arm64 --load --file docker/services/Dockerfile
#     --build-arg JAVA_RUNTIME_IMAGE=<image.java-runtime from project/build.properties>
#     --tag <tag> modules/services/target/docker-context
# There is deliberately no default base image: building without the ledger value fails.
#
# No HEALTHCHECK (Phase 13 decision 5 item 5, planned deviation): ECS does not use it, the
# runtime image has no HTTP client, and the internal ALB checks /healthz.

ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}

# Non-root runtime user with fixed numeric IDs.
RUN groupadd --system --gid 10001 scholarchain \
 && useradd --system --uid 10001 --gid scholarchain --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin scholarchain \
 && mkdir -p /opt/scholarchain/config \
 && chown root:root /opt/scholarchain /opt/scholarchain/config \
 && chmod 0755 /opt/scholarchain /opt/scholarchain/config

# Root-owned and read-only for the runtime user. The ECS task command reads the config path.
COPY --chown=root:root --chmod=0444 app.jar /opt/scholarchain/app.jar
COPY --chown=root:root --chmod=0444 config/course.json /opt/scholarchain/config/course.json

# Memory is sized as a percentage of the container limit (ECS task memory), never fixed -Xmx.
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001
WORKDIR /opt/scholarchain

ENTRYPOINT ["java", "-jar", "/opt/scholarchain/app.jar"]
