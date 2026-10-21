#!/usr/bin/env bash
# Точка входа контейнера: поднимает keystore (если его не смонтировали) и
# запускает приложение.
#
# Keystore генерируется здесь, при старте контейнера, а не при сборке образа:
#   * пароль не оседает в слоях образа и в `docker history`
#   * смена пароля не требует пересборки — достаточно `docker compose up -d`
#   * сборка образа вообще не нуждается в секрете
#
# Если keystore смонтирован извне (prod: /app/ssl/keystore.p12), генерация
# пропускается и монтированный файл используется как есть.
set -eu

KEY_STORE_FILE=/app/ssl/keystore.p12

if [[ ! -f "$KEY_STORE_FILE" ]]; then
    : "${KEY_STORE_PASS:?KEY_STORE_PASS is required - copy infrastructure/.env.dev.example to infrastructure/.env}"
    : "${KEY_STORE_ALIAS:?KEY_STORE_ALIAS is required - set it in services.app.environment}"

    # Пароли уходят в keytool через переменную окружения (:env), а не как
    # аргумент командной строки: иначе они видны в списке процессов (`ps aux`)
    # и в `/proc/<pid>/cmdline` внутри контейнера.
    keytool -genkeypair \
        -alias "$KEY_STORE_ALIAS" \
        -keyalg RSA \
        -keysize 2048 \
        -storetype PKCS12 \
        -keystore "$KEY_STORE_FILE" \
        -storepass:env KEY_STORE_PASS \
        -keypass:env KEY_STORE_PASS \
        -dname "CN=localhost" \
        -validity 3650
fi

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
