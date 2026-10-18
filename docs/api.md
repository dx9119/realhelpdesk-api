# API

Базовый URL — `/api/v1`. Машинная спека — OpenAPI: `GET /v3/api-docs`, UI — `/swagger-ui.html`
(см. `make openapi`, `make openapi-types`).

## Общие правила

| Правило | Как именно |
|---|---|
| Ошибки | `application/problem+json`: `{ type, title, status, detail, instance, timestamp }`; ошибки валидации добавляют `errors: { поле: сообщение }`; провал капчи добавляет машинный `code` (см. «Служебные»). `429` несёт `Retry-After` |
| Пагинация | Единый стиль `?page=&size=&sortBy=&order=` на всех списках. **`page` считается с нуля** (0 — первая страница), `size` ≤ 100, `sortBy` — поле из allow-list эндпоинта, `order` — `asc`/`desc` |
| Пагинированный ответ | `PageResponse`: `{ content, page, size, totalElements, totalPages, last }` |
| Коллекции без пагинации | Обёртка `ItemsResponse`: `{ items: [...], total }` (массивы верхнего уровня не используются) |
| Конкурентность | Ресурсы с изменяемыми полями отдают `ETag` и принимают `If-Match`; устаревшая версия — `412`. Без `If-Match` конфликт ловится `@Version` → `409` |
| Частичное обновление | `PATCH` меняет только переданные поля (`null` — «не трогать»); `PUT` — полная замена ресурса |
| CSRF | Все изменяющие запросы (`POST`, `PUT`, `PATCH`, `DELETE`) обязаны нести заголовок `X-Requested-With` (любое непустое значение), иначе `403` problem+json |
| CORS | Клиент шлёт `credentials: 'include'`; из ответа доступны `Location`, `Content-Disposition`, `Content-Range`, `Accept-Ranges`, `ETag`, `Retry-After` и маркеры 401 (`X-Access-Token-Expired`, `X-Auth-Token-Missing`, `X-Malformed-Token`, `X-Invalid-Token`, `X-Verify-Token-Failed`) |
| Коды | `200` + ресурс — изменение ресурса; `201` + `Location` — создание; `204` — команда/флаг; `400` — валидация; `401` — нет/истёк токен; `403` — нет прав или нет CSRF-заголовка; `404` — нет ресурса; `409` — конфликт (`@Version`, повторная передача портала); `412` — устаревший `If-Match`; `416` — некорректный `Range`; `429` — лимит |

Cookie-аутентификация (внутренний JWT-контур, профиль без `keycloak`): токены в
`HttpOnly; Secure` cookie (`accessToken`, refresh ограничен путём
`/api/v1/auth`). Клиент не хранит токены сам, но обязан слать `credentials: 'include'`.

Контур Keycloak (профиль `keycloak`, включён по умолчанию): токен выпускает
Keycloak, приложение ожидает `Authorization: Bearer <access_token>` и проверяет
подпись по JWK. Эндпоинты `/auth/*` в этом профиле не создаются, cookie не
используются, `credentials: 'include'` не нужен — сравнение контуров в
[`authentication.md`](authentication.md).

## Служебные

| Метод и путь | Назначение |
|---|---|
| `GET /health` | Проверка живости (`{ status, timestamp }`) |
| `POST /captcha/challenge` | Тело `{ action: "register" \| "email-code" }` → `{ challengeId, type: "pow" \| "emoji", params, expiresAt }`. `challengeId` — подписанный challenge (JWT). Уровень выбирает сервер: `pow` — `params: { salt, difficulty }`; `emoji` (эскалация при провалах разных IP одной подсети, `CAPTCHA_EMOJI_ENABLED=true`) — `params: { prompt, cells[9] }`. Провалы одного IP повышают `difficulty`, но остаются в `pow`. Лимит 30/60 c |
| `POST /captcha/solve` | Тело `{ challengeId, nonce }` для pow (ведущие нулевые биты `SHA-256("salt:nonce")` ≥ `difficulty`) или `{ challengeId, answer: [индексы ячеек] }` для emoji → `{ captchaToken }`. Лимит 30/60 c |
| `GET /captcha/widget.js` | Клиентский виджет: `await window.RealHelpCaptcha.getToken(action)` → `captchaToken` (PoW в Web Worker; эмодзи-оверлей при эскалации, отмена — reject `code='cancelled'`) |

Провал капчи — `400` problem+json с полем `code`: `missing_token` (токен не
передан), `invalid` (подпись/формат), `expired` (истёк challenge или токен),
`replayed` (уже использован), `wrong_type` (предъявлен не тот тип токена),
`wrong_action` (токен другого действия), `too_fast` (форма отправлена раньше
`CAPTCHA_MIN_FILL_SECONDS`), `pow_failed` (PoW не решён), `wrong_answer`
(эмодзи-задание решено неверно), `honeypot` (заполнено невидимое поле
`website`).

## Аутентификация (`/auth`)

Раздел актуален для внутреннего JWT-контура (профиль без `keycloak`); в
профиле `keycloak` этих эндпоинтов нет — токен выпускает Keycloak.

| Метод и путь | Тело / параметры | Назначение |
|---|---|---|
| `POST /register` | `RegisterRequest` | Регистрация → `201` + `Location: /users/profile`, cookies |
| `POST /login` | `{ email, password }` | Вход → `200`, cookies |
| `POST /tokens/access` | refresh-cookie | Новый access-токен и ротация refresh |
| `GET /tokens/refresh` | — | Статус refresh-токена (`{ tokenStatus, createdAt }`) |
| `GET /session` | — | Текущая сессия: `{ authenticated, user: UserInfoResponse }` (id пользователя сразу, без отдельного `GET /users/profile`) |
| `DELETE /session` | — | Логаут, отзыв токенов → `204` |

`RegisterRequest`: `firstName`, `lastName`, `email`, `password`, опц.
`captchaToken` (токен `POST /captcha/solve`; при включённой капче обязателен),
honeypot-поле `website` (должно быть пустым/отсутствовать), `externalId`,
`userPlatformSource`.

## Пользователи (`/users`)

| Метод и путь | Тело | Назначение |
|---|---|---|
| `GET /profile` | — | Профиль (`UserInfoResponse`), `ETag` |
| `PUT /profile` | `{ firstName, lastName, middleName, additionalInfo }` | Полная замена профиля → `200` + профиль, `ETag`, `If-Match` |
| `PATCH /profile` | любое подмножество полей профиля | Частичное обновление → `200` + профиль, `If-Match` |
| `POST /password-resets` | `{ email }` | Запрос сброса пароля → `204` (письмо уходит синхронно; ошибка SMTP — `503`) |
| `PUT /password-resets/{code}` | `{ password }` | Установка нового пароля → `204` |

## Операторские операции (`/operator`)

Доступ только у роли `ROLE_OPERATOR` (иначе `403`); запрос изменяющий, поэтому
обязателен заголовок `X-Requested-With`.

| Метод и путь | Тело | Назначение |
|---|---|---|
| `POST /operator/tokens/cleanup` | — | Принудительная очистка отозванных refresh-токенов старше `JWT_REVOKED_TOKEN_RETENTION_SECONDS` → `200` + `{ revokedDeleted, batches }`. Тот же механизм, что у ночного расписания: оценка объёма и удаление батчами с адаптивным размером и паузами |

## Email (`/email`)

| Метод и путь | Тело / параметры | Назначение |
|---|---|---|
| `POST /confirmations/{token}` | — | Подтверждение email по коду → `204` |
| `GET /info` | — | Текущий уровень email-оповещений (`{ muteLevel }`) |
| `DELETE /notifications/{event}` | — | Отписка от события (`NotificationEvent`) → `204` |
| `POST /codes` | заголовок опц. `X-Captcha-Token` (токен действия `email-code`) | Отправка кода подтверждения email |

## Порталы (`/portals`)

| Метод и путь | Тело / параметры | Назначение |
|---|---|---|
| `POST /` | `{ name, description }` | Создание портала → `201` + `Location`, `{ id }` |
| `GET /` | `page, size, sortBy, order` | Порталы владельца |
| `GET /accessible` | `page, size, sortBy, order` | Порталы, доступные пользователю |
| `GET /ids` | — | `ItemsResponse<Long>` — id доступных порталов |
| `GET /info` | — | `ItemsResponse<PortalInfoResponse>` |
| `GET /{portalId}` | — | Информация о портале + `ETag` |
| `PUT /{portalId}` | `{ name, description }` | Полная замена → `200` + ресурс, `If-Match` |
| `PATCH /{portalId}` | `{ name }` и/или `{ description }` | Частичное обновление → `200` + ресурс, `If-Match` |
| `GET /{portalId}/settings` | — | Настройки: участники + `public` |
| `PUT /{portalId}/settings/users` | `{ userIds: [...] }` | Замена списка участников → `200` + актуальные настройки |
| `GET /{portalId}/settings/visibility` | — | Текущая публичность (`{ isPublic }`) |
| `PUT /{portalId}/settings/visibility` | `{ isPublic }` | Смена публичности (флаг) → `204` |
| `POST /bulk-delete` | `{ ids: [...] }` | Массовое удаление → `200` `{ count, deletedIds }` |

`PortalResponse` (`id, name, description, createdAt`),
`PortalInfoResponse` (`id, namePortal, description, version`),
`PortalSettingsResponse` (`users: [{id, firstName, lastName, middleName, email}], public`).

Дополнительно:

- `GET /` отдаёт только порталы владельца; `/accessible`, `/ids`, `/info` —
  все доступные, включая публичные.
- Создать портал (`POST /`) может любой вошедший пользователь, но только с
  **подтверждённым email** — иначе `403` «Подтвердите ваш адрес электронной
  почты».
- Удаление только массовое (`POST /bulk-delete`): каждый портал удаляет свой
  владелец, чужие пропускаются; ответ — сколько удалено и какие id.

## Передача владения и история (`/portals/{id}/...`)

| Метод и путь | Тело | Назначение |
|---|---|---|
| `POST /{id}/owner-transfer` | `{ email, password, reason, keepOldOwnerAsMember }` | Инициировать передачу → `201` + `Location` |
| `GET /{id}/owner-transfer` | — | Активный запрос (владелец или предлагаемый); `404`, если запроса нет |
| `POST /{id}/owner-transfer/confirm` | `{ password, reason }` | Подтвердить передачу |
| `POST /{id}/owner-transfer/reject` | `{ reason }` | Отклонить |
| `POST /{id}/owner-transfer/cancel` | — | Отозвать до решения |
| `GET /{id}/history` | `page, size, sortBy, order` | История портала (`PageResponse<PortalHistoryResponse>`) |

Активный запрос живёт 72 часа и закрывается лениво как `EXPIRED`; одновременно
на портал может быть только один запрос (второй — `409`). Статусы:
`PENDING`, `ACCEPTED`, `REJECTED`, `CANCELLED`, `EXPIRED`. При подтверждении
владелец меняется, новый владелец убирается из участников, старый при
`keepOldOwnerAsMember=true` остаётся участником.

История (`portal_history`) хранит события передачи (`TRANSFER_*`) и изменения
портала (`NAME_CHANGED`, `DESCRIPTION_CHANGED`, `VISIBILITY_CHANGED`,
`USERS_CHANGED`, `PORTAL_DELETED`); доступ — участникам портала.

## Заявки

Портальные эндпоинты (`/portals/{portalId}/tickets`):

| Метод и путь | Тело / параметры | Назначение |
|---|---|---|
| `POST /portals/{portalId}/tickets` | `{ title, body, ticketPriority, ticketAccessStatus }` | Создание заявки → `201` + `Location`, `{ id }` |
| `GET /portals/{portalId}/tickets` | `status, page, size, sortBy, order` | Заявки портала |
| `GET /portals/{portalId}/tickets/ids` | `status, noAnswer` | `ItemsResponse<Long>` — id заявок |
| `GET /portals/{portalId}/tickets/{ticketId}` | — | Заявка по ID |
| `PUT /portals/{portalId}/tickets/{ticketId}/status` | `{ status }` | Смена статуса (команда) → `204` |
| `PUT /portals/{portalId}/tickets/{ticketId}/priority` | `{ priority }` | Смена приоритета (команда) → `204` |
| `DELETE /portals/{portalId}/tickets/{ticketId}` | — | Удаление → `204` |

Поиск (`/tickets`) — единый способ получить и все, и «мои» заявки:

| Метод и путь | Параметры | Назначение |
|---|---|---|
| `GET /tickets` | `search, startDate, endDate, status, priority, mine, page, size, sortBy, order` | Поиск/лента заявок (`PageResponse<TicketResponse>`) |

`TicketResponse` — единый DTO для списка, детали и поиска:
`id, title, body, authorId, authorFullName, portalName, portalId, ticketPriority,
ticketStatus, ticketAccessStatus, createdAt, version`.

`TicketPriority`: `CRITICAL, HIGH, MEDIUM, LOW, NONE`.
`TicketStatus`: `OPEN, IN_PROGRESS, CLOSED`.
`TicketAccessStatus`: `ALL_USERS, CREATOR_AND_PORTAL_USERS`.

## Сообщения (`/portals/{portalId}/tickets/{ticketId}/messages`)

| Метод и путь | Тело | Назначение |
|---|---|---|
| `POST .../messages` | `{ messageText }` | Создать сообщение → `201` + `Location` на `GET .../messages/{id}`, `{ id }` |
| `GET .../messages` | — | `ItemsResponse<MessageResponse>` — сообщения заявки, по возрастанию `id` |
| `GET .../messages/{messageId}` | — | Одно сообщение (чужой заявки — `404`) |

`MessageResponse`: `id, messageText, authorId, authorFullName, ticketId, createdAt`.

## Вложения (`/portals/{portalId}/tickets/{ticketId}/attachments`)

| Метод и путь | Тело / параметры | Назначение |
|---|---|---|
| `POST .../attachments` | `multipart/form-data`: `file` + опц. `messageText` | Загрузка → `201` + `Location` = `downloadUrl` |
| `GET .../attachments` | — | `ItemsResponse<AttachmentResponse>` |
| `GET .../attachments/{attachmentId}` | `Range`, `If-Range` | Скачивание потоком: `200`/`206`/`416`, всегда с `ETag` |

`AttachmentResponse`: `id, messageId, ticketId, fileName, contentType,
sizeBytes, uploadedById, uploadedByFullName, createdAt, downloadUrl`.

Вложения неизменяемы, поэтому `ETag` — `"attachment-{id}"`. `If-Range` с этим
значением продолжает докачку (`206`), несовпавший валидатор — отдаёт файл целиком
(`200`). `downloadUrl` собирается с origin'ом из `static.base-url`, если файлы
вынесены на отдельный домен (тогда нужны CORS и `jwt.cookie.domain`).

## Оповещения (`/notifications`)

| Метод и путь | Параметры / тело | Назначение |
|---|---|---|
| `GET /notifications` | `page, size, sortBy, order, unreadOnly` | Список оповещений |
| `GET /notifications/unread-count` | — | Счётчик непрочитанных `{ count }`, `Cache-Control: no-store` |
| `GET /notifications/stream` | `afterId, size` | **SSE-канал**: `text/event-stream`, события `event: notification`; `Cache-Control: no-store` |
| `GET /notifications/wait` | `afterId, timeoutSec, size` | Long polling (фолбэк; лимит `notifications-wait`, `no-store`) |
| `PUT /notifications/{id}/read` | — | Прочитано (гасит группу повторов) → `204` |
| `PUT /notifications/read-all` | — | Прочитать всё → `204` |
| `GET /notifications/preferences` | — | Включённые события и повторы |
| `PUT /notifications/preferences` | `{ events, repeatEnabled, repeatIntervalMinutes }` | Настройка событий/повтора → `200` + ресурс |

Клиент предпочитает SSE: `new EventSource('/api/v1/notifications/stream?afterId=…', { withCredentials: true })`.
Один канал вместо висящего запроса каждые 25 секунд; лимит на IP при нескольких
вкладках перестаёт мешать. `wait` возвращает страницу оповещений новее `afterId`
либо пустую по таймауту (по умолчанию 25 c, максимум 30).

Получатели — владелец портала и участники (`allowedUserIds`); автор действия
уведомление о своём действии не получает. `NEW_TICKET` и `NEW_MESSAGE`
повторяются, пока не прочитаны (по умолчанию каждые 30 минут).

`NotificationEvent`: `NEW_TICKET, NEW_MESSAGE, NEW_SYSTEM_MESSAGE,
NEW_TICKET_OR_MESSAGE, NEW_PORTAL, CHANGE_TICKET, RECOVERY_PASSWORD,
TICKET_DELETED, PORTAL_DELETED, PORTAL_TRANSFER_*, NONE`.
