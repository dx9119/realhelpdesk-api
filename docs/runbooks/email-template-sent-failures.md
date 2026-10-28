# Runbook: EmailTemplateSentFailures

**Алерт**: `rate(email_template_sent_total{result="failure"}[5m]) / clamp_min(rate(email_template_sent_total[5m]), 0.001) > 0.01`
(доля неудач > 1% в течение 5 минут, группировка по `type`).

## Что это значит

Письма по конкретному шаблону (`type`) не доставляются: проблема SMTP, битый HTML-шаблон,
несуществующие ключи в модели или невалидные данные (например, `ticketId` = null).

## Диагностика

1. **Метрики Mimir**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=rate(email_template_sent_total[5m])'`
   — посмотреть `type`, где доля ошибок максимальна.
2. **Логи**: `{...="app"} |= "Не удалось отрендерить шаблон письма"` — проверяем имя шаблона в ERROR-сообщении.
3. **SMTP**: `curl -s -G localhost:9090/prometheus/api/v1/query --data-urlencode 'query=rate(mail_sent_total{result="failure"}[5m])'`
   — если `mail_sent` тоже растёт — проблема на уровне SMTP; если только `email.template.sent` — проблема в конкретном шаблоне.
4. **Шаблоны**: проверить `src/main/resources/templates/mail/` на наличие файла, соответствующего `type` из алерта.

## Митигация

- **SMTP/сеть**: проверить соединение с SMTP-сервером, аутентификацию, лимиты отправителя.
- **Битый шаблон**: восстановить сломанный HTML-файл, проверить `th:replace` на фрагменты.
- **Невалидная модель**: проверить вызывающий сервис — передаёт ли он все обязательные ключи модели
  (`EmailTemplateId.requiredModelKeys()`).
- **Массовая проблема**: если падают все шаблоны — проверить SMTP-сервер целиком.
