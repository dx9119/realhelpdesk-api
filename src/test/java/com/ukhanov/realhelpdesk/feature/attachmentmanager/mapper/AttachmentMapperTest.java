package com.ukhanov.realhelpdesk.feature.attachmentmanager.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mapstruct.factory.Mappers;

import com.ukhanov.realhelpdesk.core.config.StaticProperties;
import com.ukhanov.realhelpdesk.domain.attachment.model.AttachmentModel;
import com.ukhanov.realhelpdesk.feature.attachmentmanager.dto.AttachmentResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("Маппер вложений: downloadUrl и toResponse")
class AttachmentMapperTest {

    private static final Long PORTAL_ID = 5L;
    private static final Long TICKET_ID = 77L;
    private static final Long ATTACHMENT_ID = 15L;
    private static final String PATH = "/api/v1/portals/5/tickets/77/attachments/15";

    private final AttachmentMapper mapper = Mappers.getMapper(AttachmentMapper.class);

    @Test
    @DisplayName("static.base-url не задан — downloadUrl относительный, файлы отдаёт API, как до настройки")
    void downloadUrl_noBaseUrl_returnsRelativeUrl() {

        // given / when / then
        assertThat(mapper.downloadUrl(new StaticProperties(), PORTAL_ID, TICKET_ID, ATTACHMENT_ID)).isEqualTo(PATH);
    }

    @Test
    @DisplayName("static.base-url задан — downloadUrl абсолютный, путь API не меняется")
    void downloadUrl_baseUrlSet_returnsAbsoluteUrl() {

        // given
        // when
        StaticProperties properties = new StaticProperties();
        properties.setBaseUrl("https://cdn.example.com");

        // then
        assertThat(mapper.downloadUrl(properties, PORTAL_ID, TICKET_ID, ATTACHMENT_ID))
                .isEqualTo("https://cdn.example.com" + PATH);
    }

    @Test
    @DisplayName("Хвостовой слэш в base-url не даёт двойного слэша в URL")
    void downloadUrl_trailingSlash_returnsUrlWithoutDoubleSlash() {

        // given
        // when
        StaticProperties properties = new StaticProperties();
        properties.setBaseUrl("http://10.0.0.5:9100/");

        // then
        assertThat(mapper.downloadUrl(properties, PORTAL_ID, TICKET_ID, ATTACHMENT_ID))
                .isEqualTo("http://10.0.0.5:9100" + PATH);
    }

    @Test
    @DisplayName("Без настройки статики — ошибка сразу, а не NPE в момент сборки URL")
    void downloadUrl_nullProperties_throwsNullPointerException() {

        // given / when / then
        assertThatThrownBy(() -> mapper.downloadUrl(null, PORTAL_ID, TICKET_ID, ATTACHMENT_ID))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("staticProperties");
    }

    @Test
    @DisplayName("toResponse переносит downloadUrl в ответ API")
    void toResponse_validAttachment_carriesDownloadUrlToDto() {

        // given
        // when
        StaticProperties properties = new StaticProperties();
        properties.setBaseUrl("https://cdn.example.com");
        AttachmentModel attachment = new AttachmentModel();
        attachment.setId(ATTACHMENT_ID);
        attachment.setFileName("report.pdf");

        AttachmentResponse response = mapper.toResponse(attachment, PORTAL_ID, TICKET_ID, properties);

        // then
        assertThat(response.downloadUrl()).isEqualTo("https://cdn.example.com" + PATH);
        assertThat(response.ticketId()).isEqualTo(TICKET_ID);
        assertThat(response.messageId()).isNull();
        assertThat(response.uploadedByFullName()).isEqualTo("Неизвестный автор");
    }
}
