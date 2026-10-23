package com.ukhanov.realhelpdesk.core.observability;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.stereotype.Component;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.metrics.micrometer.MicrometerMetricsTrackerFactory;

import io.micrometer.core.instrument.MeterRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry;
import io.opentelemetry.instrumentation.jdbc.datasource.OpenTelemetryDataSource;

/**
 * Трассировка SQL через opentelemetry-jdbc: соединения из пула Hikari оборачиваются в {@link OpenTelemetryDataSource},
 * поэтому каждый JDBC-запрос получает спан {@code db.client.query} с таймингом и санитизированным SQL (параметры в спан
 * не попадают: captureQueryParameters выключен по умолчанию).
 *
 * <p>
 * Обёртка возвращает {@link javax.sql.DataSource}, а не {@link HikariDataSource}, поэтому штатный биндовщик Boot
 * (HikariDataSourceMeterBinder) на обёрнутом бине метрики пула не видит. Компенсируем: в момент пост-обработки на
 * делегат ставится {@link MicrometerMetricsTrackerFactory} — метрики hikaricp.connections.* с тегом pool сохраняются.
 * Порядок с биндовщиком Boot не важен: он либо уже привязал метрики (тогда trackerFactory не null и мы не трогаем пул),
 * либо привязывает после нас и получает обёртку, которую игнорирует. Без бина {@link OpenTelemetry} (старые окружения,
 * юнит-контексты) бин не оборачивается вовсе.
 * </p>
 */
@Component
@ConditionalOnClass({HikariDataSource.class, OpenTelemetryDataSource.class})
public class DataSourceTracingBeanPostProcessor implements BeanPostProcessor {

    private final ObjectProvider<OpenTelemetry> openTelemetry;

    private final ObjectProvider<MeterRegistry> meterRegistries;

    public DataSourceTracingBeanPostProcessor(ObjectProvider<OpenTelemetry> openTelemetry,
            ObjectProvider<MeterRegistry> meterRegistries) {
        this.openTelemetry = openTelemetry;
        this.meterRegistries = meterRegistries;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if (bean instanceof OpenTelemetryDataSource || !(bean instanceof HikariDataSource hikari)) {
            return bean;
        }
        keepPoolMetrics(hikari);
        OpenTelemetry otel = openTelemetry.getIfAvailable();
        if (otel == null) {
            return bean;
        }
        return JdbcTelemetry.create(otel).wrap(hikari);
    }

    private void keepPoolMetrics(HikariDataSource hikari) {
        if (hikari.getMetricsTrackerFactory() != null) {
            return;
        }
        meterRegistries.orderedStream()
                .findFirst()
                .ifPresent(registry -> hikari.setMetricsTrackerFactory(new MicrometerMetricsTrackerFactory(registry)));
    }
}
