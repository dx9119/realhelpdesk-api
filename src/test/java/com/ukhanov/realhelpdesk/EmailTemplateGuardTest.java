package com.ukhanov.realhelpdesk;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Целостность почтовых шаблонов: enum {@link EmailTemplateId} и файлы в {@code templates/mail/} обязаны оставаться
 * синхронными, а вёрстка — пригодной для отправки.
 *
 * <p>
 * Проверки читают файлы с диска, а не из classpath: рассинхрон enum'а и вёрстки ловится до сборки jar, иначе битый
 * шаблон всплывает только при первой реальной отправке письма.
 * </p>
 */
@DisplayName("Почтовые шаблоны: синхронность enum'а и вёрстки, безопасный HTML")
class EmailTemplateGuardTest {

    private static final Path MAIL_DIR = Path.of("src", "main", "resources", "templates", "mail");

    private static final Path FRAGMENTS_DIR = MAIL_DIR.resolve("fragments");

    @Test
    @DisplayName("У каждого значения EmailTemplate есть HTML-файл — письмо соберётся при отправке")
    void templateValues_htmlFiles_allExist() {

        // given
        Set<String> existing = htmlFileNames(MAIL_DIR);

        // when
        List<String> missing = Arrays.stream(EmailTemplateId.values())
                .map(EmailTemplateGuardTest::fileName)
                .filter(name -> !existing.contains(name))
                .sorted()
                .toList();

        // then
        assertThat(missing).as("нет файлов шаблонов для").isEmpty();
    }

    @Test
    @DisplayName("Ни один HTML в templates/mail не остался без значения EmailTemplate")
    void htmlFiles_templateValues_coverAll() {

        // given
        Set<String> declared = Arrays.stream(EmailTemplateId.values())
                .map(EmailTemplateGuardTest::fileName)
                .collect(Collectors.toCollection(TreeSet::new));

        // when
        List<String> orphans = htmlFileNames(MAIL_DIR).stream()
                .filter(name -> !declared.contains(name))
                .sorted()
                .toList();

        // then
        assertThat(orphans).as("HTML без значения EmailTemplateId").isEmpty();
    }

    @Test
    @DisplayName("Каждый шаблон подключает mailHeader и mailFooter — шапка и подвал не дублируются вёрсткой")
    void htmlFiles_fragmentsUsage_headerAndFooterPresent() {

        // given
        // when
        List<Path> templates = templateFiles();

        // then
        assertThat(templates).as("шаблоны в " + MAIL_DIR).isNotEmpty();
        for (Path file : templates) {
            String html = read(file);
            assertThat(html).as("%s: шапка".formatted(file)).contains(":: mailHeader}");
            assertThat(html).as("%s: подвал".formatted(file)).contains(":: mailFooter}");
        }
    }

    @Test
    @DisplayName("В шаблонах нет JS, th:utext и внешних src — письмо не становится вектором атаки")
    void htmlFiles_unsafeMarkup_isAbsent() {

        // given
        // when
        List<Path> templates = templateFiles();

        // then
        for (Path file : templates) {
            String html = read(file);
            assertThat(html)
                    .as("%s: скрипты, сырой HTML и внешние ресурсы".formatted(file))
                    .doesNotContain("<script")
                    .doesNotContain("th:utext")
                    .doesNotContain("src=");
        }
    }

    @Test
    @DisplayName("Фрагменты существуют и объявляют именованные части — шаблоны на них ссылаются не впустую")
    void fragments_namedParts_areDeclared() {

        // given
        // when
        String header = read(FRAGMENTS_DIR.resolve("header.html"));
        String footer = read(FRAGMENTS_DIR.resolve("footer.html"));

        // then
        assertThat(header).contains("th:fragment=\"mailHeader\"");
        assertThat(footer).contains("th:fragment=\"mailFooter\"");
    }

    /**
     * Имя файла шаблона на диске: {@code templatePath} содержит префикс {@code mail/}, а каталог уже является
     * {@code mail/}.
     */
    private static String fileName(EmailTemplateId template) {
        return Path.of(template.templatePath()).getFileName() + ".html";
    }

    /** Имена файлов {@code *.html} в каталоге без рекурсии в {@code fragments/}. */
    private static Set<String> htmlFileNames(Path dir) {
        try (Stream<Path> paths = Files.list(dir)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".html"))
                    .map(path -> path.getFileName().toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Файлы писем: {@code fragments/} — общая рамка, она проверяется отдельно. */
    private static List<Path> templateFiles() {
        try (Stream<Path> paths = Files.list(MAIL_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".html"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Не удалось прочитать " + path, e);
        }
    }
}
