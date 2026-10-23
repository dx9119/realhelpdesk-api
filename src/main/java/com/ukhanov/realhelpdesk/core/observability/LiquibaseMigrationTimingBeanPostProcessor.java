package com.ukhanov.realhelpdesk.core.observability;

import java.time.Duration;
import java.time.Instant;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import liquibase.integration.spring.SpringLiquibase;

/**
 * Время миграций Liquibase в логах старта: Boot запускает миграции в afterPropertiesSet бина {@link SpringLiquibase},
 * поэтому обёртка ставится до инициализации и засекает длительность. Метрика не заводится — по плану, если нет простого
 * способа: миграции выполняются один раз при старте, их место в логах, а не в рантайм-метриках.
 */
@Component
@ConditionalOnClass(SpringLiquibase.class)
public class LiquibaseMigrationTimingBeanPostProcessor implements BeanPostProcessor {

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof SpringLiquibase liquibase && !(bean instanceof TimedSpringLiquibase)) {
            return new TimedSpringLiquibase(liquibase);
        }
        return bean;
    }

    /**
     * Прокси {@link SpringLiquibase}: миграция выполняется на исходном бине (Boot создаёт
     * DataSourceClosingSpringLiquibase), а контейнер инициализирует уже обёртку; длительность пишется в INFO — строка
     * видна в логах старта рядом с сообщениями changeset'ов.
     */
    static final class TimedSpringLiquibase extends SpringLiquibase {

        private final SpringLiquibase delegate;

        TimedSpringLiquibase(SpringLiquibase delegate) {
            this.delegate = delegate;
        }

        @Override
        public void afterPropertiesSet() throws liquibase.exception.LiquibaseException {
            Instant startedAt = Instant.now();
            delegate.afterPropertiesSet();
            log.info("Liquibase: миграции выполнены за " + Duration.between(startedAt, Instant.now()).toMillis()
                    + " мс");
        }
    }
}
