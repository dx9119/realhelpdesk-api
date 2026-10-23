package com.ukhanov.realhelpdesk.core.http;

/**
 * ETag ресурса и проверка предусловия {@code If-Match}.
 *
 * <p>
 * Источник версии — колонка {@code @Version}: она меняется на каждое изменение строки, поэтому ETag честно отражает
 * состояние ресурса. Клиент сравнивает ETag из GET с текущим перед отправкой изменения, а сервер проверяет
 * {@code If-Match} до записи — конфликт виден как 412, а не как потерянное обновление или 409 после факта.
 */
public final class EntityTag {

    private EntityTag() {
    }

    /** Сильный ETag вида {@code "portal-7"}; имя ресурса отделяет ETag разных сущностей. */
    public static String of(String resource, Integer version) {
        return "\"" + resource + "-" + version + "\"";
    }

    /** ETag неизменяемого ресурса без {@code @Version} (например, файла): достаточно идентификатора. */
    public static String ofImmutable(String resource, Long id) {
        return "\"" + resource + "-" + id + "\"";
    }

    /**
     * Выполнено ли предусловие {@code If-Match}: пустой заголовок означает «предусловия нет» (true). Принимаем и точный
     * ETag, и «сырую» версию из DTO — клиенту не нужно помнить формат кавычек.
     */
    public static boolean satisfiedBy(String ifMatch, String etag, Integer version) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return true;
        }
        String candidate = unquote(ifMatch.trim());
        return candidate.equals(unquote(etag)) || candidate.equals(String.valueOf(version));
    }

    /** Сравнение с ETag без версии (ресурсы без {@code @Version}): пустой заголовок — предусловия нет. */
    public static boolean matches(String ifMatch, String etag) {
        return ifMatch == null || ifMatch.isBlank() || unquote(ifMatch.trim()).equals(unquote(etag));
    }

    /** Кавычки вокруг ETag обязательны по RFC 9110, но клиент может их не прислать — терпим оба вида. */
    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }
}
