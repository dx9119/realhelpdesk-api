package com.ukhanov.realhelpdesk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт чистоты миграций: dev и prod стартуют с пустой схемы, поэтому данные не должны просачиваться в структурные changeset'ы.
 *
 * <p>
 * Разделение контуров: {@code changes/} + {@code sql/} — только DDL; любые фикстуры живут в {@code seed/} и помечены
 * {@code context="seed"}. Приложение по умолчанию работает с {@code DB_MIGRATIONS_CONTEXTS=schema}, поэтому seed-changeset'ы подключены, но
 * не выполняются: база остаётся чистой, а сид — явная команда {@code make db-seed}.
 * </p>
 */
@DisplayName("Миграции Liquibase: DDL в changes/, данные — только в seed/ с context=\"seed\"")
class LiquibaseChangelogGuardTest {

    private static final Path CHANGELOG_DIR = Path.of("src", "main", "resources", "db", "changelog");
    private static final Path MASTER = CHANGELOG_DIR.resolve("db.changelog-master.xml");

    /** DML-операторы в SQL: вставка, изменение, удаление, слияние, очистка таблицы. */
    private static final Pattern SQL_DML = Pattern
            .compile("(?i)\\b(insert\\s+into|update\\s+[\\w.\"]+\\s+set|delete\\s+from|merge\\s+into|truncate\\b)");

    /** DML-теги Liquibase (XML-обёртка над теми же операциями, что и в SQL). */
    private static final Pattern XML_DML_TAGS = Pattern.compile("<\\s*(insert|update|delete|loadData|loadUpdateData)\\b");

    /**
     * Строка вида {@code <changeSet ...>}: group(1) — атрибуты. Тег многострочный, поэтому DOTALL и ленивый захват до {@code >}.
     */
    private static final Pattern CHANGE_SET_OPEN = Pattern.compile("<changeSet\\b([^>]*)>", Pattern.DOTALL);

    @Test
    @DisplayName("DML встречается только в seed/ — структурные миграции (changes/, sql/) не содержат данных")
    void changelog_dmlOutsideSeed_returnsEmpty() throws IOException {

        // given
        // when
        List<Path> files = changelogFiles();
        // then
        assertThat(files).as("файлы changelog в " + CHANGELOG_DIR).isNotEmpty();

        List<String> dmlOutsideSeed = files.stream().filter(file -> !isSeed(file)).filter(LiquibaseChangelogGuardTest::containsDml)
                .map(CHANGELOG_DIR::relativize).map(Path::toString).sorted().toList();

        assertThat(dmlOutsideSeed).as("DML вне seed/ — фикстуры в структурных миграциях forbidden").isEmpty();
    }

    @Test
    @DisplayName("seed-фикстуры подключены в master и каждый их changeset помечен context=\"seed\"")
    void seedChangesets_notIncludedOrUnscoped_fails() throws IOException {

        // given
        List<Path> seedXml;
        // when
        try (Stream<Path> stream = Files.list(CHANGELOG_DIR.resolve("seed"))) {
            seedXml = stream.filter(path -> path.toString().endsWith(".xml")).sorted().toList();
        }
        // then
        assertThat(seedXml).as("каталог seed должен существовать и содержать changelog фикстур").isNotEmpty();

        String master = stripComments(read(MASTER));

        for (Path file : seedXml) {
            String include = CHANGELOG_DIR.relativize(file).toString().replace('\\', '/');
            assertThat(master).as("master должен подключать " + include).contains("file=\"" + include + "\"");

            String content = stripComments(read(file));
            Matcher changeset = CHANGE_SET_OPEN.matcher(content);
            int found = 0;
            while (changeset.find()) {
                found++;
                assertThat(changeset.group(1)).as(file + ": changeset без context=\"seed\" выполнится при DB_MIGRATIONS_CONTEXTS=schema")
                        .contains("context=\"seed\"");
            }
            assertThat(found).as(file + ": хотя бы один changeset").isPositive();
        }
    }

    private static List<Path> changelogFiles() throws IOException {
        try (Stream<Path> stream = Files.walk(CHANGELOG_DIR)) {
            return stream.filter(Files::isRegularFile).filter(path -> {
                String name = path.toString();
                return name.endsWith(".xml") || name.endsWith(".sql");
            }).toList();
        }
    }

    private static boolean isSeed(Path file) {
        return CHANGELOG_DIR.relativize(file).startsWith("seed");
    }

    private static boolean containsDml(Path file) {
        String content = stripComments(read(file));
        return SQL_DML.matcher(content).find() || XML_DML_TAGS.matcher(content).find();
    }

    /** SQL-комментарии (строчные и блочные) и XML-комментарии вырезаны: упоминание операции в комментарии — не DML. */
    private static String stripComments(String content) {
        return content.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?s)<!--.*?-->", " ").replaceAll("--[^\\n]*", " ");
    }

    private static String read(Path file) {
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
