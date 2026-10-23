package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.utils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Proof-of-Work: решение — подобрать nonce, чтобы SHA-256 от строки {@code "salt:nonce"} начинался с N нулевых бит. Хэш
 * считается по UTF-8 строке (не по байтам соли), чтобы клиент (JS-виджет) и сервер считали одинаково — см.
 * {@code static/captcha/widget.js}.
 */
public final class PowHash {

    private static final int BYTE_MASK = 0xff;
    private static final int BITS_PER_BYTE = 8;
    private static final int LEADING_ZEROS_OFFSET = 24;

    private PowHash() {
    }

    /** SHA-256 от строки в UTF-8 (то, что хэширует клиент в виджете). */
    public static byte[] hash(String data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступна", e);
        }
    }

    /**
     * Число ведущих нулевых бит дайджеста: 0x0000… даёт ≥64, 0x0f… — ровно 4.
     */
    public static int leadingZeroBits(byte[] digest) {
        int bits = 0;
        for (byte b : digest) {
            int value = b & BYTE_MASK;
            if (value == 0) {
                bits += BITS_PER_BYTE;
            } else {
                bits += Integer.numberOfLeadingZeros(value) - LEADING_ZEROS_OFFSET;
                break;
            }
        }
        return bits;
    }

    /** Решение засчитано: набрано не меньше {@code difficulty} нулевых бит. */
    public static boolean meets(String salt, String nonce, int difficulty) {
        return leadingZeroBits(hash(salt + ":" + nonce)) >= difficulty;
    }
}
