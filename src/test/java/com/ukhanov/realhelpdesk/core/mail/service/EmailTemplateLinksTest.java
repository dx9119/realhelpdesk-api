package com.ukhanov.realhelpdesk.core.mail.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplateId;
import com.ukhanov.realhelpdesk.core.mail.service.EmailTemplateRenderService.EmailContent;
import com.ukhanov.realhelpdesk.core.mail.support.EmailContentFixture;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Ссылки в письмах: единый резолвер URL")
class EmailTemplateLinksTest {

    private static final Pattern HREF = Pattern.compile("href=\"([^\"]+)\"");

    private static final List<EmailTemplateId> TICKET_TEMPLATES = List.of(
            EmailTemplateId.TICKET_CREATED,
            EmailTemplateId.TICKET_REPLY,
            EmailTemplateId.TICKET_STATUS,
            EmailTemplateId.TICKET_PRIORITY,
            EmailTemplateId.NOTIFICATION_REPEAT);

    private final EmailTemplateRenderService renderer = EmailContentFixture.renderer();

    @Test
    @DisplayName("Ссылка на заявку идентична во всех шаблонах: /ticket-show/portal/{portalId}/ticket/{ticketId}")
    void render_ticketTemplates_shareOneTicketUrl() {

        // given
        String expected = "http://" + EmailContentFixture.DOMAIN + "/ticket-show/portal/"
                + EmailContentFixture.PORTAL_ID + "/ticket/" + EmailContentFixture.TICKET_ID;

        // when
        List<String> links = TICKET_TEMPLATES.stream()
                .map(template -> ticketUrl(renderer.render(template, EmailContentFixture.fullModel()).html()))
                .toList();

        // then
        assertThat(links).isNotEmpty();
        assertThat(links).as("ссылка на заявку во всех шаблонах").containsOnly(expected);
    }

    @ParameterizedTest
    @EnumSource(EmailTemplateId.class)
    @DisplayName("Схема не дублируется, все ссылки абсолютные и собраны из frontDomain")
    void render_anyTemplate_hasNoDuplicatedScheme(EmailTemplateId template) {

        // given
        EmailContent content = renderer.render(template, EmailContentFixture.fullModel());

        // when
        List<String> links = hrefs(content.html());

        // then
        assertThat(content.html())
                .doesNotContain("http://http")
                .doesNotContain("https://http")
                .doesNotContain("http://https");
        for (String link : links) {
            assertThat(link).startsWith("http://" + EmailContentFixture.DOMAIN + "/");
            assertThat(link.replaceFirst("https?://[^/]+", "")).startsWith("/");
        }
    }

    @Test
    @DisplayName("Ссылка сброса пароля ведёт на форму с кодом, ссылка передачи портала — на срок действия")
    void render_resetAndTransferTemplates_linkToRightPlace() {

        // given
        // when
        String reset = firstLinkWith(renderer.render(EmailTemplateId.PASSWORD_RESET,
                EmailContentFixture.fullModel()).html(), "/pass-reset");
        String transfer = firstLinkWith(renderer.render(EmailTemplateId.PORTAL_TRANSFER_PROPOSED,
                EmailContentFixture.fullModel()).html(), "/portal-transfer");

        // then
        assertThat(reset)
                .startsWith("http://" + EmailContentFixture.DOMAIN + "/pass-reset?code=")
                .contains(EmailContentFixture.CODE);
        assertThat(transfer).isEqualTo("http://" + EmailContentFixture.DOMAIN + "/portal-transfer/"
                + EmailContentFixture.PORTAL_ID);
    }

    @Test
    @DisplayName("Ссылки на список порталов одинаковы в письмах о портале")
    void render_portalTemplates_sharePortalManagerUrl() {

        // given
        String expected = "http://" + EmailContentFixture.DOMAIN + "/portal-manager";

        // when
        String created = firstLinkWith(
                renderer.render(EmailTemplateId.PORTAL_CREATED, EmailContentFixture.fullModel()).html(),
                "/portal-manager");
        String added = firstLinkWith(
                renderer.render(EmailTemplateId.PORTAL_ADD_USER, EmailContentFixture.fullModel()).html(),
                "/portal-manager");

        // then
        assertThat(created).isEqualTo(expected);
        assertThat(added).isEqualTo(expected);
    }

    @Test
    @DisplayName("Большие ID не группируются пробелами и не теряются в URL")
    void render_largeIds_urlKeepsDigits() {

        // given
        Map<String, Object> model = new LinkedHashMap<>(EmailContentFixture.fullModel());
        model.put("ticketId", 12345L);
        model.put("portalId", 67890L);

        // when
        String link = ticketUrl(renderer.render(EmailTemplateId.TICKET_CREATED, model).html());

        // then
        assertThat(link).isEqualTo("http://" + EmailContentFixture.DOMAIN + "/ticket-show/portal/67890/ticket/12345");
    }

    private static String ticketUrl(String html) {
        return firstLinkWith(html, "/ticket-show/");
    }

    private static String firstLinkWith(String html, String pathPart) {
        return hrefs(html).stream()
                .filter(link -> link.contains(pathPart))
                .findFirst()
                .orElseThrow(() -> new AssertionError("В письме нет ссылки с фрагментом " + pathPart + ": " + html));
    }

    private static List<String> hrefs(String html) {
        Matcher matcher = HREF.matcher(html);
        List<String> links = new ArrayList<>();
        while (matcher.find()) {
            links.add(matcher.group(1));
        }
        return links;
    }
}
