package com.ukhanov.realhelpdesk.core.log;

import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Маскирование ПДн и секретных сегментов пути перед записью в лог. Код восстановления пароля и токен подтверждения
 * email передаются в пути запроса — в логах они должны заменяться на ***.
 *
 * <p>
 * {@link #value(String)} готовит к логу значение из внешних данных (заголовок, метаданные вложения): перевод строки в
 * логе ломает разбор по колонкам, а неограниченная длина раздувает строку.
 * </p>
 *
 * <p>
 * {@link #email(String)} и {@link #emails(String)} маскируют адреса (первый символ локальной части + *** + домен):
 * полный email в логах — ПДн.
 * </p>
 */
public final class LogSanitizer {

    private static final Pattern SECRET_SEGMENT = Pattern.compile("(/password-resets/|/email/confirmations/)[^/]+");
    private static final Pattern EMAIL = Pattern
            .compile("[\\p{L}\\p{N}.!#$%&'*+/=?^_`{|}~-]+@[\\p{L}\\p{N}.-]+\\.[\\p{L}]{2,}");
    private static final int MAX_VALUE_LENGTH = 120;
    private static final int PHONE_VISIBLE_PREFIX = 4;

    private LogSanitizer() {
    }

    public static String uri(String uri) {
        if (uri == null) {
            return "";
        }
        return SECRET_SEGMENT.matcher(uri).replaceAll("$1***");
    }

    /** Значение из внешних данных для лога: управляющие символы заменяются, длинное значение обрезается. */
    public static String value(String value) {
        if (value == null) {
            return "";
        }
        String flat = value.replaceAll("[\\r\\n\\t]", "_");
        return flat.length() <= MAX_VALUE_LENGTH ? flat : flat.substring(0, MAX_VALUE_LENGTH) + "…";
    }

    /**
     * Email для лога: {@code john.doe@example.com} → {@code j***@example.com}; не разобранный адрес маскируется
     * целиком.
     */
    public static String email(String email) {
        if (email == null || email.isBlank()) {
            return "";
        }
        String flat = value(email);
        Matcher matcher = EMAIL.matcher(flat);
        return matcher.find() ? matcher.replaceAll(LogSanitizer::maskEmail) : "***";
    }

    /** Все адреса в произвольном тексте (сообщения исключений) маскируются, остальной текст сохраняется. */
    public static String emails(String text) {
        if (text == null) {
            return "";
        }
        return EMAIL.matcher(value(text)).replaceAll(LogSanitizer::maskEmail);
    }

    /** Имя файла для лога: базовое имя скрывается (в нём бывают ПДн), остаётся расширение — {@code ***.pdf}. */
    public static String fileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "";
        }
        String flat = value(fileName);
        int dot = flat.lastIndexOf('.');
        return dot > 0 ? "***" + flat.substring(dot) : "***";
    }

    /** Телефон для лога: первые 4 символа видимы, остальное — {@code ***}. */
    public static String phone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        String flat = value(phone);
        return flat.length() <= PHONE_VISIBLE_PREFIX
                ? "***"
                : flat.substring(0, PHONE_VISIBLE_PREFIX) + "***";
    }

    private static String maskEmail(MatchResult match) {
        String email = match.group();
        int at = email.lastIndexOf('@');
        return email.charAt(0) + "***" + email.substring(at);
    }
}
