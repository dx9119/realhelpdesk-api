package com.ukhanov.realhelpdesk.core.mail.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.BindResult;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Конфигурация email: EmailProperties")
class EmailPropertiesTest {

    @Test
    @DisplayName("email.from и email.notify биндятся из application.properties")
    void bind_fromAndNotifyProperties_returnsBoundValues() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("email.from", "noreply@front.example.ru");
        environment.setProperty("email.notify", "admin@front.example.ru");

        EmailProperties properties = Binder.get(environment).bind("email", Bindable.of(EmailProperties.class)).get();

        // then
        assertThat(properties.getFrom()).isEqualTo("noreply@front.example.ru");
        assertThat(properties.getNotify()).isEqualTo("admin@front.example.ru");
    }

    @Test
    @DisplayName("Без свойств email.* биндинг не срабатывает")
    void bind_emptyEnvironment_returnsUnboundResult() {

        // given
        // when
        BindResult<EmailProperties> result = Binder.get(new MockEnvironment())
                .bind("email", Bindable.of(EmailProperties.class));

        // then
        assertThat(result.isBound()).isFalse();
    }

    @Test
    @DisplayName("email.domain и email.project-name биндятся из конфига")
    void bind_domainAndProjectName_returnsBoundValues() {

        // given
        // when
        MockEnvironment environment = new MockEnvironment();
        environment.setProperty("email.domain", "front.example.ru");
        environment.setProperty("email.project-name", "real help desk");

        EmailProperties properties = Binder.get(environment).bind("email", Bindable.of(EmailProperties.class)).get();

        // then
        assertThat(properties.getDomain()).isEqualTo("front.example.ru");
        assertThat(properties.getProjectName()).isEqualTo("real help desk");
    }
}
