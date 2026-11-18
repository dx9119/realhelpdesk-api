# Аутентификация

Токены выпускает Keycloak; приложение — OAuth2 Resource Server. Второго
контура нет: локальной регистрации, входа и refresh-потока в приложении не
существует, локального пароля в БД нет.

| | Значение |
|---|---|
| Кто выпускает токен | Keycloak, клиент `realhelpdesk-api` |
| Как передаётся | заголовок `Authorization: Bearer <access_token>` |
| Проверка подписи | Spring Security по JWK Keycloak (`KC_JWK_SET_URI`, `KC_ISSUER_URI`) |
| Пользователь и роль | из БД по email из claim `email`; realm-роли синхронизируются в `UserRole` |
| Обновление токена | refresh-поток Keycloak; cookie-ротации и refresh-таблицы в приложении нет |

Роль для проверки прав приложение всегда читает из БД — блокировка
пользователя и понижение роли действуют сразу, не дожидаясь истечения
токена. Токен без claim `email` отклоняется: связь запроса с учётной
записью идёт по адресу, а не по `sub` Keycloak.

Классы контура — `core/security/keycloak`:

| Класс | Роль |
|---|---|
| `KeycloakSecurityConfiguration` | `SecurityFilterChain`: bearer-провайдер, stateless, отключённый CSRF, problem+json на 401/403 |
| `KeycloakAuthenticationConverter` | JWT → аутентификация: поиск/создание пользователя, синхронизация роли, проверка статуса |
| `KeycloakWhiteUrlConfig` | Публичные пути (капча, health, OpenAPI) |
| `KeycloakAuthenticationRoleFilter` | Роль аутентифицированного пользователя в MDC и в тег `role` метрики |

## Пользователь и роль

Жизненный цикл учётной записи решает `KeycloakAuthenticationConverter` —
единственное место контура, которое читает и пишет пользователя:

1. email берётся из claim `email`; без него токен отклоняется (401);
2. пользователь ищется по email без учёта регистра. Отсутствующего создаёт
   при `keycloak.auto-create-users=true` (дефолт): источник —
   `UserPlatformSource.KEYCLOAK`, статус `ACTIVE`, email уже подтверждён
   Keycloak'ом. При `false` токен неизвестного пользователя отклоняется;
3. realm-роли (`realm_access.roles`) при `keycloak.sync-roles=true` (дефолт)
   повышают роль в БД. Имена realm-ролей совпадают с `UserRole`
   (`ROLE_USER`, `ROLE_OPERATOR`, `ROLE_NONE`), таблицы соответствия нет;
   служебные роли Keycloak (`offline_access`, `uma_authorization`)
   игнорируются;
4. учётная запись не в статусе `ACTIVE` (`BLOCKED`, `DISABLED`) отклоняется
   даже при валидном токене.

Синхронизация только повышает роль: адрес из `APP_OPERATOR_EMAILS` и роль,
назначенная вручную в БД, не снимаются. Понижение делается в БД осознанно.

`ROLE_OPERATOR` синхронизируется в БД, но собственных эндпоинтов у роли нет:
утилиты обслуживания, которые она открывала (очистка отозванных токенов),
удалены вместе с локальным JWT-контуром. Доступ к данным роль не даёт —
удалить пользователей, заявки или иные сущности она не может.

## Keycloak

Keycloak для локального стенда поднимается сервисом `keycloak` (профиль compose
`local-tools`, `quay.io/keycloak/keycloak:26.6`, хостовый порт
`127.0.0.1:8180`). Realm `realhelpdesk` импортируется из
`infrastructure/keycloak/realhelpdesk-realm.json` при первом старте
(`--import-realm`): клиент `realhelpdesk-api` (Authorization Code + PKCE,
direct access grant для отладки и e2e) и тестовые пользователи
`user@example.com` / `user-pass` (`ROLE_USER`), `operator@example.com` /
`operator-pass` (`ROLE_OPERATOR`). Схема Keycloak хранится в отдельном
кластере PostgreSQL (сервис `keycloak-postgres`, свой том
`keycloak_postgres_data` и креды `KEYCLOAK_DB_PASSWORD`), миграции Liquibase
приложения этого кластера не касаются.

Для внешнего провайдера укажите в `.env` его адреса (`KC_JWK_SET_URI` — адрес
для проверки подписи, `KC_ISSUER_URI` и `KC_HOSTNAME` — адрес, который видит
браузер) и запускайте приложение `make run-local` (профили `dev,host`).

Проверка подписи идёт по JWK, поэтому приложение не хранит ключей Keycloak:
ротация ключей в realm'е подхватывается без перезапуска.

## Капча (Proof-of-Work + эмодзи-эскалация)

Локально выключена (дефолт `CAPTCHA_ENABLED:false` в схеме
`application.properties`; включается `CAPTCHA_ENABLED=true` в
`infrastructure/.env`). При выключенной капче проверка токена пропускается
(fail-open), поэтому разработка без PoW не встаёт.

Потребитель капчи в контуре один — выдача кода подтверждения email
(`POST /api/v1/email/codes`, действие `email-code`). Действие `register`
объявлено в `CaptchaTokenService` и принимается challenge'ем, но эндпоинта
регистрации в приложении нет.

1. `POST /api/v1/captcha/challenge { action }` отдаёт подписанное задание:
   базовая сложность PoW — `CAPTCHA_POW_DIFFICULTY` ведущих нулевых бит;
2. клиент ищет `nonce`, при котором `SHA-256("salt:nonce")` имеет не меньше
   нулевых бит, чем сложность, — виджет `GET /captcha/widget.js` делает это в
   Web Worker, `await window.RealHelpCaptcha.getToken(action)` возвращает
   `captchaToken`;
3. `POST /api/v1/captcha/solve` обменивает решение на подписанный токен —
   он предъявляется в заголовке `X-Captcha-Token` на `POST /email/codes`.

Эскалация по риску (локальный риск-движок, без внешних провайдеров)
разделяет сигналы, чтобы один NAT-адрес не блокировал всех своих клиентов
из-за провалов одного:

- провалы одного IP за окно (`CAPTCHA_RISK_FAILURE_THRESHOLD=5` за
  `CAPTCHA_RISK_FAILURE_WINDOW_SECONDS=600`) — мягкий риск: к сложности PoW
  добавляется `CAPTCHA_RISK_ELEVATED_DIFFICULTY_BONUS=3` бита (но не выше
  26 бит);
- провалы `CAPTCHA_RISK_SUBNET_OFFENDER_THRESHOLD=3` разных IP одной подсети
  (`CAPTCHA_RISK_SUBNET_PREFIX_V4=24`, `CAPTCHA_RISK_SUBNET_PREFIX_V6=64`) —
  признак распределённой атаки: вместо PoW выдаётся эмодзи-задание — сетка
  3×3 глифов и выбор ячеек в оверлее виджета. Выключается
  `CAPTCHA_EMOJI_ENABLED=false`; успех сбрасывает счётчики IP.

Защита от автоматики: подписи HMAC (`CAPTCHA_HMAC_SECRET` — секрет, минимум
32 символа), одноразовость challenge и токена, минимальное время заполнения
формы (`CAPTCHA_MIN_FILL_SECONDS`, дефолт 0; для публичной формы — 3) и
honeypot-поле `website` (его заполнение = `honeypot` в ответе; проверяется в
`CaptchaService.verifyRegistration`). При отказе в проблеме-JSON поле `code` —
`missing_token`, `pow_failed`, `wrong_answer`, `replayed`, `too_fast`,
`wrong_action`, `honeypot` (полный список — [`api.md`](api.md)).
Метрики: `captcha.challenge.issued{type}` и
`captcha.verification{type,result,reason}`. Сроки:
`CAPTCHA_CHALLENGE_TTL_SECONDS` / `CAPTCHA_TOKEN_TTL_SECONDS` (по умолчанию
300 c). Внешние провайдеры (Turnstile/SmartCaptcha/hCaptcha) из реализации
исключены — детали схемы в [`capcha.md`](capcha.md).

## CSRF

CSRF-защита выключена (`csrf.disable()`): cookie-аутентификации нет, токен
клиент передаёт заголовком `Authorization`, который браузер не подставляет
сам. Сессия stateless — `SessionCreationPolicy.STATELESS`, серверных сессий
нет.

## Метрики

`auth.attempts{type,result}` фиксирует попытки прохождения капчи
(`type=captcha`, `result=success|failure`). Значения тегов — конечные списки;
email, имя пользователя и текст ошибки в теги не попадают. Других значений
`type` в `AuthMetrics` нет: вход и обновление токена обслуживает Keycloak, а
отказы аутентификации видны в `http.server.requests` (401/403).
