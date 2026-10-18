# Аутентификация

Контур аутентификации выбирается профилем `keycloak` (см.
[`configuration.md`](configuration.md#профили-spring)):

| | Внутренний JWT (без `keycloak`) | Keycloak (профиль `keycloak`) |
|---|---|---|
| Кто выпускает токен | приложение (`/api/v1/auth/register`, `/login`) | Keycloak, клиент `realhelpdesk-api` |
| Как передаётся | http-only cookie `accessToken` | заголовок `Authorization: Bearer <access_token>` |
| Проверка подписи | `JwtAuthFilter` + `JwtConfig` (HS256, `JWT_SECRET`) | Spring Security по JWK Keycloak (`KC_JWK_SET_URI`) |
| Пользователь и роль | из БД по `sub` токена | из БД по email из токена; realm-роли синхронизируются в `UserRole` |
| Регистрация/вход/логаут приложения | есть | нет — эндпоинты не создаются (`@Profile("!keycloak")`), вход и пароли обслуживает Keycloak |
| Обновление access-токена | `POST /api/v1/auth/tokens/access` по refresh-cookie | refresh-поток Keycloak (cookie-ротации в БД приложения нет) |

Профиль `keycloak` включён по умолчанию (`dev,keycloak`): приложение только
проверяет подпись по JWK и читает из токена email и realm-роли. Роль для
проверки прав приложение всегда читает из БД — блокировка пользователя и
понижение роли действуют сразу, не дожидаясь истечения токена. Переключение
контура: `make profile-local` / `make profile-keycloak` (передают
`SPRING_PROFILES_ACTIVE` инлайном и пересоздают контейнер `app`, ничего не
записывая в файлы — см. [`configuration.md`](configuration.md#профили-spring)).

## Внутренний JWT-контур

JWT передаётся в http-only cookie (`accessToken`), refresh-токен — в cookie с
`path=/api/v1/auth`. Каждый запрос проходит через `JwtAuthFilter`: проверяются
подпись, срок, issuer/audience, тип токена (`typ=access`), статус пользователя
и версия токенов (`ver`). Логаут и смена пароля немедленно отзывают
access-токены.

Поток:

| Шаг | Запрос | Что происходит |
|---|---|---|
| 1 | `POST /api/v1/auth/register` или `POST /api/v1/auth/login` | Сервер ставит cookies, тела нет |
| 2 | `POST /api/v1/auth/tokens/access` (по refresh-cookie) | Новый access и ротация refresh; старые refresh отзываются |
| 3 | `DELETE /api/v1/auth/session` | Логаут, отзыв access и refresh |

Настройки cookie: `jwt.cookie.same-site` (`JWT_COOKIE_SAMESITE`) и
`jwt.cookie.domain` (`JWT_COOKIE_DOMAIN`).

### Refresh-токены и их очистка

Refresh-токены лежат в БД хешами SHA-256. Ротация при обновлении отзывает
предыдущий refresh; отозванные и протухшие строки удаляет фоновая задача по
`jwt.token-cleanup-cron` (`JWT_TOKEN_CLEANUP_CRON`), храня отозванные
`jwt.revoked-token-retention-seconds` (`JWT_REVOKED_TOKEN_RETENTION_SECONDS`).

Удаление идёт батчами с адаптивным размером и паузой
(`JWT_TOKEN_CLEANUP_BATCH_MIN_SIZE`, `_BATCH_MAX_SIZE`, `_TARGET_BATCH_MS`,
`_BATCH_PAUSE_MS`): сначала оценивается объём, затем строки удаляются
порциями в отдельных коротких транзакциях — без пиковой нагрузки на БД.
Ручной запуск очистки отозванных: `POST /api/v1/operator/tokens/cleanup`
(роль `ROLE_OPERATOR`) — тот же механизм, ответ `200` +
`{ revokedDeleted, batches }`.

### Роли

`ROLE_OPERATOR` назначается бутстрапом: `APP_OPERATOR_EMAILS` — список email
через запятую; при старте такие пользователи (уже зарегистрированные и с
подтверждённым email) повышаются до `ROLE_OPERATOR`. Список только повышает
роль и идемпотентен — удаление адреса роль не снимает, чтобы опечатка в
конфиге не оставила систему без оператора.

`ROLE_OPERATOR` даёт доступ только к утилитам обслуживания (сейчас —
досрочная очистка токенов), а не к управлению данными: удалить пользователей,
заявки или иные сущности эта роль не может.

## Keycloak

Локальный контур поднимается сервисом `keycloak` (профиль compose
`local-tools`, `quay.io/keycloak/keycloak:26.6`, хостовый порт
`127.0.0.1:8180`). Realm `realhelpdesk` импортируется из
`infrastructure/keycloak/realhelpdesk-realm.json` при первом старте
(`--import-realm`): клиент `realhelpdesk-api` (Authorization Code + PKCE,
direct access grant для отладки и e2e) и тестовые пользователи
`user@example.com` / `user-pass` (`ROLE_USER`), `operator@example.com` /
`operator-pass` (`ROLE_OPERATOR`). Схема Keycloak хранится в отдельной базе
`keycloak` того же PostgreSQL — её создаёт сервис `keycloak-db-init`,
миграции Liquibase приложения этой базы не касаются.

При `keycloak.auto-create-users=true` (дефолт) неизвестный email из токена
заводит пользователя с источником `KEYCLOAK`; при `keycloak.sync-roles=true`
realm-роли `ROLE_USER`/`ROLE_OPERATOR` повышают роль в БД.

В рабочем контуре Keycloak разворачивается отдельно: сервис `keycloak` входит
только в профиль `local-tools`. Укажите в `.env` адреса внешнего Keycloak
(`KC_JWK_SET_URI` — внутренний адрес для проверки подписи, `KC_ISSUER_URI` и
`KC_HOSTNAME` — адрес, который видит браузер) и включите профиль
`prod,keycloak` — например, строкой `SPRING_PROFILES_ACTIVE=prod,keycloak`
в `.env` сервера или инлайном при запуске стека.

## Защита от CSRF

Проверку выполняет `CsrfProtectionFilter`:

- cookie уходят в том числе кросс-сайтово (`SameSite=None` по умолчанию),
  поэтому изменяющие запросы обязаны нести заголовок `X-Requested-With`
  (любое непустое значение) — иначе `403` в формате problem+json;
- браузер не добавит кастомный заголовок без preflight, а preflight чужого
  origin отклоняет CORS (allow-list `CORS_ALLOWED_ORIGINS`);
- для односайтового фронта дополнительно достаточно `JWT_COOKIE_SAMESITE=Lax`.

## Капча (Proof-of-Work + эмодзи-эскалация)

Локально выключена (`CAPTCHA_ENABLED=false` — дефолт шаблона), в рабочем
контуре включена обязательно (prod-preflight валит старт без неё).
Двухуровневая схема вместо текстовой картинки (её обходят OCR/LLM):

1. `POST /api/v1/captcha/challenge { action }` отдаёт подписанное задание:
   базовая сложность PoW — `CAPTCHA_POW_DIFFICULTY` ведущих нулевых бит;
2. клиент ищет `nonce`, при котором `SHA-256("salt:nonce")` имеет не меньше
   нулевых бит, чем сложность, — виджет `GET /captcha/widget.js` делает это в
   Web Worker, `await window.RealHelpCaptcha.getToken(action)` возвращает
   `captchaToken`;
3. `POST /api/v1/captcha/solve` обменивает решение на подписанный токен —
   он предъявляется на регистрацию (поле `captchaToken`) или в заголовке
   `X-Captcha-Token` на `POST /email/codes`.

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
формы (`CAPTCHA_MIN_FILL_SECONDS`, 0 локально / 3 в рабочем контуре) и
honeypot-поле `website` (его заполнение = `honeypot` в ответе). При отказе в
проблеме-JSON поле `code` — `pow_failed`, `wrong_answer`, `replayed`,
`too_fast`, `honeypot` и т.д. (полный список — [`api.md`](api.md)).
Метрики: `captcha.challenge.issued{type}` и
`captcha.verification{type,result,reason}`. Сроки:
`CAPTCHA_CHALLENGE_TTL_SECONDS` / `CAPTCHA_TOKEN_TTL_SECONDS` (по умолчанию
300 c). Внешние провайдеры (Turnstile/SmartCaptcha/hCaptcha) из реализации
исключены окончательно — детали схемы в [`capcha.md`](capcha.md).
