package com.ukhanov.realhelpdesk.core.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ETag ресурса и проверка If-Match")
class EntityTagTest {

    private static final String PORTAL_ETAG = EntityTag.of("portal", 7);
    private static final String ATTACHMENT_ETAG = EntityTag.ofImmutable("attachment", 15L);

    @Test
    @DisplayName("ETag версионируемого ресурса содержит имя ресурса и версию в кавычках")
    void of_versionedResource_returnsQuotedEtag() {
        // given / when / then
        assertThat(PORTAL_ETAG).isEqualTo("\"portal-7\"");
    }

    @Test
    @DisplayName("ETag неизменяемого ресурса строится по идентификатору")
    void ofImmutable_immutableResource_returnsQuotedEtag() {
        // given / when / then
        assertThat(ATTACHMENT_ETAG).isEqualTo("\"attachment-15\"");
    }

    @Test
    @DisplayName("If-Match отсутствует → предусловие выполнено")
    void satisfiedBy_noPrecondition_isTrue() {
        // given / when / then
        assertThat(EntityTag.satisfiedBy(null, PORTAL_ETAG, 7)).isTrue();
        assertThat(EntityTag.satisfiedBy("   ", PORTAL_ETAG, 7)).isTrue();
    }

    @Test
    @DisplayName("If-Match совпадает с ETag, в том числе без кавычек")
    void satisfiedBy_matchingEtag_isTrue() {
        // given / when / then
        assertThat(EntityTag.satisfiedBy("\"portal-7\"", PORTAL_ETAG, 7)).isTrue();
        assertThat(EntityTag.satisfiedBy("portal-7", PORTAL_ETAG, 7)).isTrue();
    }

    @Test
    @DisplayName("If-Match присылают «сырой» версией из DTO → тоже совпадает")
    void satisfiedBy_rawVersion_isTrue() {
        // given / when / then
        assertThat(EntityTag.satisfiedBy("7", PORTAL_ETAG, 7)).isTrue();
    }

    @Test
    @DisplayName("If-Match с устаревшей версией → предусловие не выполнено (412)")
    void satisfiedBy_staleVersion_isFalse() {
        // given / when / then
        assertThat(EntityTag.satisfiedBy("\"portal-6\"", PORTAL_ETAG, 7)).isFalse();
        assertThat(EntityTag.satisfiedBy("6", PORTAL_ETAG, 7)).isFalse();
    }

    @Test
    @DisplayName("matches: пустой If-Range означает «диапазон разрешён», чужой ETag — нет")
    void matches_emptyOrForeignIfRange_returnsExpected() {
        // given / when / then
        assertThat(EntityTag.matches(null, ATTACHMENT_ETAG)).isTrue();
        assertThat(EntityTag.matches("\"attachment-15\"", ATTACHMENT_ETAG)).isTrue();
        assertThat(EntityTag.matches("attachment-15", ATTACHMENT_ETAG)).isTrue();
        assertThat(EntityTag.matches("\"attachment-14\"", ATTACHMENT_ETAG)).isFalse();
    }
}
