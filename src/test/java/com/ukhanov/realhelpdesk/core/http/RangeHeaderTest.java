package com.ukhanov.realhelpdesk.core.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import com.ukhanov.realhelpdesk.core.http.RangeHeader.Kind;
import com.ukhanov.realhelpdesk.core.http.RangeHeader.Parsed;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Тесты разбора заголовка Range (RangeHeader)")
class RangeHeaderTest {

    private static final long TOTAL = 10;

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"  ", "items=0-4", "bytes=", "bytes=abc", "bytes=1-2-3", "bytes=5-3", "bytes=0-1,5-6", "bytes=-",
            "bytes=99999999999999999999-"})
    @DisplayName("Нет/пустой/неподдерживаемый заголовок → FULL: отдаём весь файл (200)")
    void parse_unusableHeader_returnsFull(String header) {

        // given
        // when
        Parsed parsed = RangeHeader.parse(header, TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.FULL);
    }

    @Test
    @DisplayName("bytes=0-4 → PARTIAL с границами включительно и длиной 5")
    void parse_explicitRange_returnsPartial() {

        // given
        // when
        Parsed parsed = RangeHeader.parse("bytes=0-4", TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.PARTIAL);
        assertThat(parsed.start()).isZero();
        assertThat(parsed.end()).isEqualTo(4);
        assertThat(parsed.length()).isEqualTo(5);
        assertThat(parsed.contentRange(TOTAL)).isEqualTo("bytes 0-4/10");
    }

    @Test
    @DisplayName("bytes=5- (до конца) → PARTIAL с хвостом файла")
    void parse_openEndedRange_returnsPartialToEnd() {

        // given
        // when
        Parsed parsed = RangeHeader.parse("bytes=5-", TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.PARTIAL);
        assertThat(parsed.start()).isEqualTo(5);
        assertThat(parsed.end()).isEqualTo(TOTAL - 1);
    }

    @Test
    @DisplayName("bytes=-3 (последние 3 байта) → PARTIAL хвостом")
    void parse_suffixRange_returnsLastBytes() {

        // given
        // when
        Parsed parsed = RangeHeader.parse("bytes=-3", TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.PARTIAL);
        assertThat(parsed.start()).isEqualTo(7);
        assertThat(parsed.end()).isEqualTo(9);
    }

    @Test
    @DisplayName("суффикс длиннее файла → PARTIAL всем файлом (RFC: берём всё представление)")
    void parse_suffixLongerThanFile_returnsWholeFile() {

        // given
        // when
        Parsed parsed = RangeHeader.parse("bytes=-50", TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.PARTIAL);
        assertThat(parsed.start()).isZero();
        assertThat(parsed.end()).isEqualTo(TOTAL - 1);
    }

    @Test
    @DisplayName("правая граница дальше конца файла → обрезается по размеру")
    void parse_endBeyondFile_clampedToSize() {

        // given
        // when
        Parsed parsed = RangeHeader.parse("bytes=8-999", TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.PARTIAL);
        assertThat(parsed.end()).isEqualTo(TOTAL - 1);
        assertThat(parsed.length()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"bytes=10-", "bytes=99-200", "bytes=-0"})
    @DisplayName("Диапазон за концом файла → UNSATISFIABLE (416)")
    void parse_rangeBeyondFile_returnsUnsatisfiable(String header) {

        // given
        // when
        Parsed parsed = RangeHeader.parse(header, TOTAL);

        // then
        assertThat(parsed.kind()).isEqualTo(Kind.UNSATISFIABLE);
        assertThat(parsed.contentRange(TOTAL)).isEqualTo("bytes */10");
    }

    @Test
    @DisplayName("Range по нулевому файлу → UNSATISFIABLE, без Range — FULL")
    void parse_emptyFile_returnsUnsatisfiableAndFull() {

        // given / when / then
        assertThat(RangeHeader.parse("bytes=0-", 0).kind()).isEqualTo(Kind.UNSATISFIABLE);
        assertThat(RangeHeader.parse(null, 0).kind()).isEqualTo(Kind.FULL);
    }
}
