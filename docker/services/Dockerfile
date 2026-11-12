# ScholarChain REST services image (ECS Fargate).
#
# Built by `sbt dockerLocal`, which assembles modules/services and passes the pinned Java
# runtime image from the version ledger (project/build.properties, image.java-runtime).
# There is deliberately no default base image: building without the ledger value fails.
#
# The build context is the staging directory sbt creates; it contains only app.jar.

ARG JAVA_RUNTIME_IMAGE
FROM ${JAVA_RUNTIME_IMAGE}

# Non-root runtime user with fixed numeric IDs.
RUN groupadd --system --gid 10001 scholarchain \
 && useradd --system --uid 10001 --gid scholarchain --no-create-home \
      --home-dir /nonexistent --shell /usr/sbin/nologin scholarchain \
 && mkdir -p /opt/scholarchain \
 && chown root:root /opt/scholarchain \
 && chmod 0755 /opt/scholarchain

COPY --chown=root:root --chmod=0444 app.jar /opt/scholarchain/app.jar

# Memory is sized as a percentage of the container limit (ECS task memory), never fixed -Xmx.
ENV JAVA_TOOL_OPTIONS="-XX:InitialRAMPercentage=50.0 -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"

USER 10001:10001
WORKDIR /opt/scholarchain

ENTRYPOINT ["java", "-jar", "/opt/scholarchain/app.jar"]
