# ---------- STAGE 1: Build JAR ----------
#JDK
FROM maven:3.9.6-eclipse-temurin-21 AS build

WORKDIR /app

# Сборка проекта (config — конфиги Spotless/Checkstyle, нужные на фазе validate).
# Зависимости живут в кэше BuildKit, а не в слое образа: кэш переживает любую
# правку pom.xml, поэтому отдельный dependency:go-offline не нужен.
COPY pom.xml .
COPY src ./src
COPY config ./config
# lombok.config обязателен на фазе компиляции: он задаёт lombok.log.fieldName=logger,
# без него @Slf4j генерирует поле «log», а код проекта обращается к «logger» —
# сборка образа падает на компиляции (JwtAuthFilter, GlobalExceptionHandler и др.).
COPY lombok.config .
# Сборка + переименование jar + раскладка слоёв Boot одним RUN (hadolint DL3059).
# Glob target/*.jar матчит только fat-jar: *.jar.original оканчивается на
# .original и не совпадает, поэтому пайп с ls|grep не нужен (DL4006/SC2010).
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests && \
    cp target/*.jar app.jar && \
    java -Djarmode=tools -jar app.jar extract --layers --destination /app/extracted

# Самоподписанный keystore при сборке НЕ генерируется: пароль не должен
# попадать в слои образа и в `docker history`. Он создаётся при старте
# контейнера — см. scripts/docker/entrypoint.sh.


# ---------- STAGE 2: Runtime ----------
FROM eclipse-temurin:21-jre

# Ревизия git для метки образа: передаётся при сборке (--build-arg GIT_SHA=$(git rev-parse HEAD))
ARG GIT_SHA=unknown
LABEL org.opencontainers.image.revision=$GIT_SHA \
      org.opencontainers.image.source="https://github.com/dx9119/RealHelpDesk"

WORKDIR /app

# Создаём группу и пользователя с фиксированными uid/gid: tmpfs и монтируемые
# тома (ssl, gc) настраиваются под эти идентификаты без chown на каждом старте.
RUN groupadd -r -g 10001 spring-group && \
    useradd -r -u 10001 -g spring-group \
            -d /app \
            -s /sbin/nologin \
            -c "Spring Boot application user" \
            spring-user

# /usr/bin/pebble (init из базового Ubuntu-образа) приложением не используется:
# entrypoint — наша обёртка над java, PID1 не нужен. Бинарник собран на старой
# Go и даёт 3 уязвимости HIGH в trivy — удаляем на этапе сборки.
# Директория для SSL: keystore.p12 появляется здесь при старте контейнера,
# если свой файл не смонтирован volume (см. scripts/docker/entrypoint.sh)
RUN rm -f /usr/bin/pebble && \
    mkdir -p /app/ssl && chown spring-user:spring-group /app /app/ssl

# Точка входа: поднимает keystore (пароль берёт из окружения, не из сборки)
# и запускает приложение. --chown/--chmod задаются здесь же, чтобы не делать
# отдельный слой chown -R: он повторял бы содержимое слоёв jar (+73 МБ).
COPY --chown=spring-user:spring-group --chmod=755 scripts/docker/entrypoint.sh /app/entrypoint.sh

# Слои jar вместо одного жирного файла (sec.md §7.6). Порядок важен для кэша:
# реже меняющиеся слои идут первыми, слой приложения — последним.
# Boot 4.1.1 раскладывает jar так: application/app.jar (свой код и манифест
# с Class-Path) + dependencies/lib/*.jar (124 библиотеки); запуск — java -jar
# app.jar, манифест сам поднимает lib/.
COPY --from=build --chown=spring-user:spring-group /app/extracted/dependencies/ ./
COPY --from=build --chown=spring-user:spring-group /app/extracted/snapshot-dependencies/ ./
COPY --from=build --chown=spring-user:spring-group /app/extracted/spring-boot-loader/ ./
COPY --from=build --chown=spring-user:spring-group /app/extracted/application/ ./

# Переключаемся на непривилегированного пользователя
USER spring-user

# Настройки JVM
# Размер Code Cache для JIT‑компиляции
ENV JAVA_RESERVED_CODE_CACHE_SIZE="240M"
# Ограничение Direct ByteBuffer (off‑heap)
ENV JAVA_MAX_DIRECT_MEMORY_SIZE="10M"
# Максимальный размер Metaspace — область, где JVM хранит метаданные классов.
ENV JAVA_MAX_METASPACE_SIZE="179M"
# Размер стека на поток (-Xss).
ENV JAVA_XSS="1M"
# Максимальный размер heap. Пустое значение (переопределяется в
# infrastructure/docker-compose.yaml) переключает entrypoint на -XX:MaxRAMPercentage.
ENV JAVA_XMX="345M"
# Доля heap от лимита памяти контейнера — используется, только если JAVA_XMX пуст.
# Имеет смысл только с mem_limit в infrastructure/docker-compose.yaml.
ENV JAVA_RAM_PERCENTAGE="75"
# Логирование GC и safepoint-пауз: всегда в stderr — попадает в `docker logs`
# без файлов на диске и без ротации
ENV JAVA_GC_LOG_OPTS="-Xlog:gc*,safepoint:stderr"
# Java Flight Recorder по умолчанию выключен (sec.md §7.8); включается ненулевым
# значением JAVA_JFR_OPTS в infrastructure/.env/окружении при необходимости
ENV JAVA_JFR_OPTS=""
# Ошибка JVM в файл + heap dump при OOM + немедленный выход (важно для контейнеров)
ENV JAVA_ERROR_FILE_OPTS="-XX:ErrorFile=/tmp/java_error.log"
ENV JAVA_HEAP_DUMP_OPTS="-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/tmp"
ENV JAVA_ON_OOM_OPTS="-XX:+ExitOnOutOfMemoryError"
# Native Memory Tracking (анализ нативной памяти JVM)
ENV JAVA_NMT_OPTS="-XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics"
# JMX: локальный доступ, без SSL и аутентификации
ENV JAVA_JMX_OPTS="-Djava.rmi.server.hostname=127.0.0.1 \
    -Dcom.sun.management.jmxremote.authenticate=false \
    -Dcom.sun.management.jmxremote.ssl=false \
    -Dcom.sun.management.jmxremote.port=5005 \
    -Dcom.sun.management.jmxremote.rmi.port=5005"

# Приложение работает на 8443 (SERVER_PORT), actuator — на отдельном
# управляющем порту 8081 (MANAGEMENT_PORT)
EXPOSE 8443
EXPOSE 8081

# Запуск приложения. Entrypoint сначала поднимает keystore, затем exec java.
ENTRYPOINT ["/app/entrypoint.sh"]
