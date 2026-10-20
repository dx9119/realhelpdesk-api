package com.ukhanov.realhelpdesk.core.security.localjwt.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Enum SameSite: весь набор значений для auth-cookie")
class SameSiteTest {

    @Test
    @DisplayName("В наборе ровно три элемента: None, Lax, Strict")
    void values_enumDefinition_returnsExactlyThree() {
        // given / when / then
        assertThat(SameSite.values()).extracting(SameSite::name).containsExactly("NONE", "LAX", "STRICT");
    }

    @Test
    @DisplayName("В Set-Cookie уходит форма из getValue: None/Lax/Strict")
    void getValue_eachConstant_returnsCookieHeaderForm() {
        // given / when / then
        assertThat(SameSite.NONE.getValue()).isEqualTo("None");
        assertThat(SameSite.LAX.getValue()).isEqualTo("Lax");
        assertThat(SameSite.STRICT.getValue()).isEqualTo("Strict");
    }
}
