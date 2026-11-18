FROM eclipse-temurin:21-jdk AS build
WORKDIR /workspace
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
COPY common/build.gradle.kts common/build.gradle.kts
COPY compiler/build.gradle.kts compiler/build.gradle.kts
COPY reference/build.gradle.kts reference/build.gradle.kts
COPY engine/build.gradle.kts engine/build.gradle.kts
COPY control-api/build.gradle.kts control-api/build.gradle.kts
COPY simulator/build.gradle.kts simulator/build.gradle.kts
COPY tests/build.gradle.kts tests/build.gradle.kts
RUN chmod +x gradlew
COPY . .
RUN ./gradlew --no-daemon :control-api:bootJar

FROM eclipse-temurin:21-jre
ARG GIT_SHA=dev
LABEL org.opencontainers.image.source="https://github.com/${GITHUB_REPOSITORY}" \
      org.opencontainers.image.revision="${GIT_SHA}"
ENV GIT_SHA=${GIT_SHA}
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --system aether \
    && useradd --system --gid aether --home-dir /app --shell /usr/sbin/nologin aether
COPY --from=build --chown=aether:aether /workspace/control-api/build/libs/control-api-0.1.0.jar /app/app.jar
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
USER aether
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 CMD curl --fail --silent http://localhost:8080/api/health || exit 1
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar"]
