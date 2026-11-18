# Рейт-лимиты

Счётчик ведётся по IP и эндпоинту; при превышении — `429` с заголовком
`Retry-After`. Счётчики хранятся в памяти процесса и сбрасываются при
рестарте приложения.

Пороги заданы в схеме `application.properties` (`ratelimit.limits.*`) дефолтами
`${RATE_LIMIT_<KEY>_REQUESTS:…}` / `${RATE_LIMIT_<KEY>_WINDOW_SECONDS:…}`;
переопределение для среды — парами `RATE_LIMIT_<KEY>_REQUESTS` /
`RATE_LIMIT_<KEY>_WINDOW_SECONDS` в `infrastructure/.env`.

| Ключ | Эндпоинт | Лимит |
|---|---|---|
| `captcha-challenge` | `POST /captcha/challenge` | 30 / 60 c |
| `captcha-solve` | `POST /captcha/solve` | 30 / 60 c |
| `email-code` | `POST /email/codes` | 3 / 10 мин |
| `notifications-wait` | `GET /notifications/wait` | 30 / 60 c |
| `portal-transfer-request` | `POST /portals/{id}/owner-transfer` | 5 / 5 мин |
| `portal-transfer-confirm` | `POST /portals/{id}/owner-transfer/confirm` | 5 / 5 мин |
| `email-recovery` | письма с событием `RECOVERY_PASSWORD` (на адрес) | 3 / 24 ч |

`email-recovery` — лимит отправки писем внутри `EmailDeliveryService`, а не
лимит эндпоинта: восстановление пароля обслуживает Keycloak, поэтому в API
событие сейчас не порождается.

## Как добавить лимит

1. Поставьте `@RateLimit(key = "...")` на метод контроллера.
2. Добавьте порог в схему: `ratelimit.limits.<key>.requests` / `.window-seconds`
   с дефолтами `${RATE_LIMIT_<KEY>_…:…}` в `application.properties`
   (при необходимости — переопределение в `infrastructure/.env`).

## Работа за прокси

Ключ лимита строится по клиентскому IP. За прокси задайте
`TRUST_PROXY_HEADERS=true` в `infrastructure/.env` (см.
[`configuration.md`](configuration.md#клиентский-ip)), иначе все запросы
будут считаться с одного адреса прокси.

## Покрытие тестами

Логика бакетов, `429` и `Retry-After` целиком покрыта
`RateLimitServiceTest`. E2E-сценарий `make e2e-ratelimit` (56 последовательных
запросов) в `e2e-all` не входит — он оставлен для ручного прогона после
изменений в рейт-лимитах.
