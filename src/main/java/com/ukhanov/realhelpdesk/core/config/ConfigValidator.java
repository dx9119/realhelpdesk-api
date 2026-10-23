package com.ukhanov.realhelpdesk.core.config;

/**
 * Проверки конфигурации для конструкторов сервисов: убирают длинные цепочки {@code null || < min || > max} из
 * конструкторов и дают единообразные сообщения об ошибках.
 */
public final class ConfigValidator {

    private ConfigValidator() {
    }

    public static int requireInRange(Integer value, int min, int max, String property) {
        if (value == null || value < min || value > max) {
            throw new IllegalStateException(
                    property + " должен быть в диапазоне " + min + ".." + max + ", получено " + value);
        }
        return value;
    }

    public static int requirePositive(Integer value, String property) {
        if (value == null || value < 1) {
            throw new IllegalStateException(property + " должен быть положительным, получено " + value);
        }
        return value;
    }

    public static int requireAtLeast(Integer value, int min, String property) {
        if (value == null || value < min) {
            throw new IllegalStateException(
                    property + " должен быть не меньше " + min + ", получено " + value);
        }
        return value;
    }

    public static String requireNonBlank(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(property + " не содержит значение");
        }
        return value;
    }
}
