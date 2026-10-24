# ---------- STAGE 1: Build JAR ----------
FROM maven:3.9.16-eclipse-temurin-25-noble AS build

WORKDIR /app

COPY pom.xml lombok.config ./
COPY src ./src
COPY config ./config

# Сборка с кэшем BuildKit и распаковка fat-jar на слои
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests && \
    cp target/*.jar app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --destination /app/extracted


# ---------- STAGE 2: Runtime ----------
FROM eclipse-temurin:25-jre-noble

ARG GIT_SHA=unknown
LABEL org.opencontainers.image.revision=$GIT_SHA \
      org.opencontainers.image.source="https://github.com/dx9119/realhelpdesk-api"

WORKDIR /app

# Фиксированные UID/GID (10001) для монтирования томов и tmpfs без chown на старте
RUN groupadd -r -g 10001 spring-group && \
    useradd -r -u 10001 -g spring-group \
            -d /app \
            -s /sbin/nologin \
            -c "Spring Boot application user" \
            spring-user

# Удаляем pebble (CVE в Trivy) и создаём директорию под SSL keystore
RUN rm -f /usr/bin/pebble && \
    mkdir -p /app/ssl && chown spring-user:spring-group /app /app/ssl

COPY --chown=spring-user:spring-group --chmod=755 scripts/docker/entrypoint.sh /app/entrypoint.sh

# Порядок слоёв от редко меняющихся к часто меняющимся
COPY --from=build --chown=spring-user:spring-group /app/extracted/dependencies/ ./
COPY --from=build --chown=spring-user:spring-group /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring-user:spring-group /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring-user:spring-group /app/extracted/application/ ./

USER spring-user

# Настройки JVM (если JAVA_XMX пуст, entrypoint использует JAVA_RAM_PERCENTAGE)
ENV JAVA_RESERVED_CODE_CACHE_SIZE="240M"
ENV JAVA_MAX_DIRECT_MEMORY_SIZE="10M"
ENV JAVA_MAX_METASPACE_SIZE="179M"
ENV JAVA_XSS="1M"
ENV JAVA_XMX="345M"
ENV JAVA_RAM_PERCENTAGE="75"
ENV JAVA_GC_LOG_OPTS="-Xlog:gc*,safepoint:stderr"
ENV JAVA_JFR_OPTS=""
ENV JAVA_ERROR_FILE_OPTS="-XX:ErrorFile=/tmp/java_error.log"
ENV JAVA_HEAP_DUMP_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
ENV JAVA_ON_OOM_OPTS="-XX:+ExitOnOutOfMemoryError"
ENV JAVA_NMT_OPTS="-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics"
ENV JAVA_JMX_OPTS="-Djava.rmi.server.hostname=127.0.0.1 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.port=5005 \
    -Dcom.sun.management.jmxremote.rmi.port=5005"

# 8443 — API, 8081 — Actuator
EXPOSE 8443
EXPOSE 8081

ENTRYPOINT ["/app/entrypoint.sh"]
