package com.ukhanov.realhelpdesk.core.http;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;

import lombok.extern.slf4j.Slf4j;

/**
 * Разбор заголовка Range (RFC 9110, единица «bytes») для отдачи файла фрагментами.
 *
 * <p>
 * Поддерживается один диапазон — ровно то, что нужно для возобновления загрузки на нестабильной сети (3G): клиент
 * повторяет запрос с {@code Range: bytes=<уже скачанное>-} и получает только хвост файла. Несколько диапазонов в одном
 * заголовке, неизвестная единица измерения и синтаксические ошибки игнорируются: по RFC сервер вправе ответить полным
 * файлом (200), что здесь и происходит.
 * </p>
 */
@Slf4j
public final class RangeHeader {

    /** Как ответить на запрос с заголовком Range. */
    public enum Kind {
        /** Весь файл (200): заголовка нет либо он игнорируется по RFC. */
        FULL,
        /** Фрагмент (206): диапазон разрешён в пределах файла. */
        PARTIAL,
        /** Диапазон начинается за концом файла → 416. */
        UNSATISFIABLE
    }

    /**
     * Разрешённый диапазон. {@code start}–{@code end} включительно имеют смысл только для {@link Kind#PARTIAL}; для
     * FULL и UNSATISFIABLE границы не используются (равны нулю).
     */
    public record Parsed(Kind kind, long start, long end) {

        /** Длина фрагмента в байтах (для Content-Length ответа 206). */
        public long length() {
            return end - start + 1;
        }

        /** Заголовок Content-Range: «bytes a-b/total» для 206 и «bytes &#42;/total» (границы неизвестны) для 416. */
        public String contentRange(long totalSize) {
            return kind == Kind.UNSATISFIABLE ? "bytes */" + totalSize : "bytes " + start + "-" + end + "/" + totalSize;
        }
    }

    private static final String BYTES_UNIT = "bytes=";

    private RangeHeader() {
    }

    /**
     * @param header
     *            значение заголовка Range (null, если клиент его не прислал)
     * @param totalSize
     *            полный размер файла в байтах (из метаданных, без обращения к хранилищу)
     * @return разрешённый результат: FULL — отдать файл целиком, PARTIAL — фрагмент, UNSATISFIABLE — ответить 416
     */
    public static Parsed parse(String header, long totalSize) {
        String spec = extractRangeSpec(header);
        if (spec == null) {
            return full();
        }
        int dash = spec.indexOf('-');
        if (dash < 0 || spec.indexOf('-', dash + 1) >= 0) {
            return full();
        }
        String first = spec.substring(0, dash).trim();
        String last = spec.substring(dash + 1).trim();
        if (first.isEmpty()) {
            return suffixRange(last, totalSize);
        }
        return parseBounds(first, last, totalSize);
    }

    /** Проверяет префикс {@code bytes=} и отсутствие multi-range; {@code null} — заголовок игнорируется. */
    private static String extractRangeSpec(String header) {
        if (header == null || header.isBlank()) {
            return null;
        }
        String value = header.trim();
        if (value.length() <= BYTES_UNIT.length()
                || !value.regionMatches(true, 0, BYTES_UNIT, 0, BYTES_UNIT.length())) {
            return null;
        }
        String spec = value.substring(BYTES_UNIT.length()).trim();
        if (spec.isEmpty() || spec.indexOf(',') >= 0) {
            return null;
        }
        return spec;
    }

    private static Parsed parseBounds(String first, String last, long totalSize) {
        long start = parseNumber(first);
        if (start < 0) {
            return full();
        }
        if (last.isEmpty()) {
            return start >= totalSize ? unsatisfiable() : new Parsed(Kind.PARTIAL, start, totalSize - 1);
        }
        long end = parseNumber(last);
        if (end < 0 || end < start || start >= totalSize) {
            return start >= totalSize ? unsatisfiable() : full();
        }
        return new Parsed(Kind.PARTIAL, start, Math.min(end, totalSize - 1));
    }

    /**
     * Хвостовой диапазон «bytes=-N»: последние N байт файла; N больше размера — весь файл, N = 0 — неудовлетворимый.
     */
    private static Parsed suffixRange(String suffix, long totalSize) {
        long length = parseNumber(suffix);
        if (length < 0) {
            return new Parsed(Kind.FULL, 0, 0);
        }
        if (length == 0 || totalSize == 0) {
            return unsatisfiable();
        }
        return new Parsed(Kind.PARTIAL, Math.max(0, totalSize - length), totalSize - 1);
    }

    /** Только цифры: всё остальное (включая переполнение long) — ошибка синтаксиса, заголовок игнорируется. */
    private static long parseNumber(String digits) {
        if (digits.isEmpty() || !digits.chars().allMatch(Character::isDigit)) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            logger.debug("Некорректный Range, отдан полный файл: {}", LogSanitizer.value(digits));
            return -1;
        }
    }

    private static Parsed full() {
        return new Parsed(Kind.FULL, 0, 0);
    }

    private static Parsed unsatisfiable() {
        return new Parsed(Kind.UNSATISFIABLE, 0, 0);
    }
}
