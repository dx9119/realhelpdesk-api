package com.ukhanov.realhelpdesk;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Полная документированность реагирования: каждому алерту Mimir соответствует runbook в {@code docs/runbooks/} и
 * наоборот, {@code overview.md} ссылается на все runbooks.
 *
 * <p>
 * Диагностические команды обязаны быть исполняемыми: raw query-string в curl ломается на глобах ({@code [5m]},
 * {@code {...}} → curl exit 3), поэтому PromQL/LogQL/TraceQL — только через {@code -G --data-urlencode}; устаревший
 * эндпоинт Tempo {@code /traceql/search} отвечает 404.
 * </p>
 */
@DisplayName("Алерты Mimir ↔ runbooks: полнота документации и рабочие команды")
class ObservabilityRunbookGuardTest {

    private static final Path RULES = Path.of("infrastructure", "observability", "mimir", "rules", "anonymous",
            "realhelpdesk.yml");

    private static final Path RUNBOOKS_DIR = Path.of("docs", "runbooks");

    private static final Path OVERVIEW = Path.of("docs", "observability", "overview.md");

    private static final Pattern ALERT_NAME = Pattern.compile("(?m)^\\s*- alert:\\s*(\\S+)");

    /** Именно строка {@code runbook_url:} — упоминание пути в description алерта не считается. */
    private static final Pattern RUNBOOK_URL = Pattern.compile("(?m)^\\s*runbook_url:.*runbooks/([\\w-]+\\.md)");

    private static final Pattern RUNBOOK_FILE = Pattern.compile("runbooks/([\\w-]+\\.md)");

    private static final Pattern RUNBOOK_HEADER = Pattern.compile("(?m)^# Runbook: (\\S+)");

    /**
     * Паттерны, ломающие диагностику в runbooks и overview: 404-эндпоинт Tempo, неверное имя счётчика, raw query-string
     * в curl.
     */
    private static final List<String> FORBIDDEN = List.of("traceql/search", "query=tickets_created_total",
            "rate(tickets_created_total",
            "api/v1/query?query=");

    @Test
    @DisplayName("Биекция: у каждого алерта свой runbook с тем же именем в заголовке")
    void runbooksAndAlerts_have_oneToOneMapping() throws IOException {

        // given
        // when
        String rules = read(RULES);
        List<String> alerts = matches(rules, ALERT_NAME);
        List<String> runbookUrls = matches(rules, RUNBOOK_URL);
        List<String> files = runbookFiles();

        // then
        assertThat(alerts).as("алерты в %s".formatted(RULES)).isNotEmpty();
        assertThat(runbookUrls).as("у каждого алерта ровно один runbook_url").hasSameSizeAs(alerts);
        assertThat(new TreeSet<>(runbookUrls)).as("runbook_url каждого алерта существует на диске")
                .containsExactlyInAnyOrderElementsOf(files);

        Set<String> headers = new TreeSet<>();
        for (String file : files) {
            headers.addAll(matches(read(RUNBOOKS_DIR.resolve(file)), RUNBOOK_HEADER));
        }
        assertThat(headers).as("заголовок '# Runbook: <Alert>' совпадает с именем алерта")
                .containsExactlyInAnyOrderElementsOf(alerts);
    }

    @Test
    @DisplayName("overview.md ссылается на каждый runbook — таблица алертов полна")
    void overviewDoc_links_coverAllRunbooks() throws IOException {

        // given
        // when
        Set<String> links = new TreeSet<>(matches(read(OVERVIEW), RUNBOOK_FILE));

        // then
        assertThat(links).containsExactlyInAnyOrderElementsOf(runbookFiles());
    }

    @Test
    @DisplayName("Диагностические команды исполняемы: без 404-Tempo, неверных имён и raw-глоб curl")
    void diagnosticCommands_docsContain_noForbiddenPatterns() throws IOException {

        // given
        // when
        List<Path> docs = new ArrayList<>(runbookPaths());
        docs.add(OVERVIEW);

        // then
        for (Path file : docs) {
            String text = read(file);
            for (String forbidden : FORBIDDEN) {
                assertThat(text).as("%s не должен содержать %s".formatted(file, forbidden)).doesNotContain(forbidden);
            }
            if (text.contains("3200/api/search")) {
                assertThat(text)
                        .as("%s: поиск Tempo обязан задавать окно start/end — без него виден только живой ingester"
                                .formatted(file))
                        .contains("start=");
            }
        }
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static List<String> matches(String text, Pattern pattern) {
        Matcher matcher = pattern.matcher(text);
        List<String> found = new ArrayList<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static List<Path> runbookPaths() throws IOException {
        try (Stream<Path> paths = Files.list(RUNBOOKS_DIR)) {
            return paths.filter(path -> path.getFileName().toString().endsWith(".md")).sorted().toList();
        }
    }

    private static List<String> runbookFiles() throws IOException {
        return runbookPaths().stream().map(path -> path.getFileName().toString()).toList();
    }
}
