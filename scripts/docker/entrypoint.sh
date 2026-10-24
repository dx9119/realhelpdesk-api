#!/usr/bin/env bash
# Точка входа контейнера: запускает приложение.
set -eu

# Память: JAVA_XMX задаёт жёсткий -Xmx. Пустой JAVA_XMX переключает JVM на
# долю от лимита контейнера (-XX:MaxRAMPercentage) — этот режим корректен
# только вместе с mem_limit в infrastructure/docker-compose.yaml, иначе JVM возьмёт долю
# от памяти хоста. При заданном JAVA_XMX доля игнорируется (проверено).
if [[ -n "${JAVA_XMX:-}" ]]; then
    MEM_OPTS=(-Xmx"${JAVA_XMX}")
else
    MEM_OPTS=(-XX:MaxRAMPercentage="${JAVA_RAM_PERCENTAGE:-75}")
fi

exec java \
    -XX:ReservedCodeCacheSize="${JAVA_RESERVED_CODE_CACHE_SIZE}" \
    -XX:MaxDirectMemorySize="${JAVA_MAX_DIRECT_MEMORY_SIZE}" \
    -XX:MaxMetaspaceSize="${JAVA_MAX_METASPACE_SIZE}" \
    "-Xss${JAVA_XSS}" \
    "${MEM_OPTS[@]}" \
    ${JAVA_HEAP_DUMP_OPTS} \
    ${JAVA_ON_OOM_OPTS} \
    ${JAVA_ERROR_FILE_OPTS} \
    ${JAVA_NMT_OPTS} \
    ${JAVA_GC_LOG_OPTS} \
    ${JAVA_JFR_OPTS} \
    ${JAVA_JMX_OPTS} \
    -jar app.jar
