package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.utils;

import java.util.HexFormat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Proof-of-Work: SHA-256 соли+nonce и подсчёт ведущих нулевых бит")
class PowHashTest {

    @Test
    @DisplayName("hash считает SHA-256 от UTF-8 строки — как JS-виджет")
    void hash_knownInput_returnsSha256Hex() {

        // given
        // sha256("hello") из RFC/констант

        // when
        String actual = HexFormat.of().formatHex(PowHash.hash("hello"));

        // then
        assertThat(actual).isEqualTo("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824");
    }

    @Test
    @DisplayName("Ведущие нулевые биты: пусто, один бит, целый нулевой байт, 4 бита")
    void leadingZeroBits_sampleBytes_returnsExpectedBitCounts() {

        // given / when / then
        assertThat(PowHash.leadingZeroBits(bytes("ffffffff"))).isZero();
        assertThat(PowHash.leadingZeroBits(bytes("7f"))).isEqualTo(1);
        assertThat(PowHash.leadingZeroBits(bytes("01"))).isEqualTo(7);
        assertThat(PowHash.leadingZeroBits(bytes("0f"))).isEqualTo(4);
        assertThat(PowHash.leadingZeroBits(bytes("00ff"))).isEqualTo(8);
        assertThat(PowHash.leadingZeroBits(bytes("000f"))).isEqualTo(12);
        assertThat(PowHash.leadingZeroBits(bytes("00000000"))).isEqualTo(32);
    }

    @Test
    @DisplayName("meets: nonce проходит при набранной сложности и проваливается при бо́льшей")
    void meets_nonceAtFourBits_returnsTrueAndFalseByDifficulty() {

        // given
        String salt = "cafebabe";
        // подбираем nonce под 4 нуля — на это уходит миллисекунды
        // when
        String nonce = bruteForce(salt, 4);

        // then
        assertThat(PowHash.meets(salt, nonce, 4)).isTrue();
        assertThat(PowHash.meets(salt, nonce, 20)).isFalse();
        assertThat(PowHash.meets(salt, "0", 4)).isFalse();
    }

    @Test
    @DisplayName("Формат строки: hash(salt:nonce) — с двоеточием, порядок аргументов важен")
    void meets_saltNonceJoinedWithColon_passesOnlyWithSeparator() {

        // given
        // when
        String nonce = bruteForce("s", 4);

        // then
        assertThat(PowHash.meets("s", nonce, 4)).isTrue();
        // та же пара, но без двоеточия не решит
        assertThat(PowHash.leadingZeroBits(PowHash.hash("s" + nonce))).isLessThan(4);
    }

    private String bruteForce(String salt, int difficulty) {
        for (long i = 0; i < 1_000_000L; i++) {
            String nonce = Long.toString(i);
            if (PowHash.meets(salt, nonce, difficulty)) {
                return nonce;
            }
        }
        throw new IllegalStateException("nonce для difficulty=" + difficulty + " не найден");
    }

    private byte[] bytes(String hex) {
        return HexFormat.of().parseHex(hex);
    }
}
