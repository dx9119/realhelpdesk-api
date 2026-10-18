# Описание

Что сделано и почему. Ссылка на задачу/аудит.

## Definition of Done

- [ ] Тесты зелёные: `make check` и `make verify` проходят локально
- [ ] Миграции Liquibase добавлены/обновлены при изменении схемы
- [ ] Секреты и ПДн (email, пароли, токены) не попали в код, логи и коммиты

### Наблюдаемость (касается новой фичи / критичного пути)

- [ ] У новой фичи есть метрика (`BusinessMetrics`/`AuthMetrics`) или `Observation` — см. `docs/observability/overview.md`
- [ ] Логи с контекстом (uri, role, traceId/spanId из паттерна) без ПДн в теле
- [ ] Для критичного пути добавлен/обновлён алерт (`infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml` + runbook)
- [ ] Правила алертов (`mimir/rules/anonymous/realhelpdesk.yml`) и runbook обновлены при появлении новых показателей
- [ ] Тест на метрику/Observation (SimpleMeterRegistry или `TracingSmokeIT`-подход)
