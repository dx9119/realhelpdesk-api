# Оповещения: SSE и long polling

In-app оповещения доходят по двум каналам, у обоих общий курсор `afterId`
(id последнего полученного оповещения), `size` ≤ 50 и
`Cache-Control: no-store`. Основной канал — SSE, long polling оставлен
фолбэком.

## Основной канал — SSE

`GET /api/v1/notifications/stream?afterId=0&size=10`

Клиент держит одно долгоживущее соединение через `EventSource`:

- первым пакетом идёт комментарий `: connected` — клиент и прокси сразу
  видят, что поток живой; без него заголовки уходят только с первым
  оповещением и запрос выглядит висящим;
- события приходят как `event: notification` с телом оповещения в JSON;
- при подключении сразу отдаётся backfill — всё, что новее курсора, в
  порядке возрастания id; дальше — только новое, повторных выдач нет:
  курсор ведёт подписка и растёт с каждым отправленным оповещением;
- канал живёт 30 минут, дальше `EventSource` переподключается сам; разрыв,
  таймаут и ошибка снимают подписку из реестра ожидающих.

## Фолбэк — long polling

`GET /api/v1/notifications/wait?afterId=0&timeoutSec=25&size=10`

- отвечает сразу, если есть оповещения новее курсора; иначе держит запрос до
  `timeoutSec` (25 с по умолчанию, 1..30) и возвращает пустую страницу —
  клиент тут же повторяет запрос;
- у эндпоинта свой рейт-лимит `notifications-wait` (делится на IP между
  вкладками), у SSE отдельного лимита нет — соединение открывается один раз;
- канал нужен, когда `EventSource` недоступен (не-браузерные клиенты) или
  прокси рвёт долгоживущие соединения: оба канала отдают одни и те же
  строки.

## Как устроено пробуждение

Оба канала регистрируются в одном in-memory `NotificationWaitRegistry` —
список «пользователь → ожидающие колбэки» на инстанс приложения.
`NotificationPublisher` сохраняет оповещение в БД и будит ожидающих
пользователя **после коммита** (`wakeAfterCommit`): раньше перечитывать БД
нельзя — запись ещё не видна. Пробуждение одноразовое, реестр очищается:

- long-poll колбэк перечитывает БД и резолвит запрос, только если строки
  появились; иначе клиент продолжает ждать до таймаута;
- SSE-подписка отправляет пакеты и, пока канал открыт, заново встаёт в
  реестр;

колбэки переносят снимок MDC контекста запроса — их будят поток публикации
и планировщик повторов, которым этот контекст не принадлежит.

## Email-оповещения и повторы

Помимо in-app каналов, о заявках уходят письма на адрес `MAIL_NOTIFY` и
настроенные подписки. Повторы непрочитанных `NEW_TICKET`/`NEW_MESSAGE`
доставляются **и** in-app (напоминания-строки в `notification`), **и** по email
(каждые `repeatIntervalMinutes` минут, пока оповещение не прочитано). Триггеры
и интервалы повторов настраиваются через API оповещений (эндпоинты и поля — в
[`api.md`](api.md)).

## Как добавить новое письмо

Шаблоны лежат в `src/main/resources/templates/mail/` — не в `static/`: содержимое
`static/` раздаётся наружу по HTTP.

Каждое письмо — комбинация из трёх артефактов.

### 1. Значение `EmailTemplateId`

`src/main/java/.../core/mail/model/EmailTemplateId.java` — enum, каждое значение:

| Поле | Описание |
|---|---|
| `templatePath` | Путь к HTML-шаблону без `.html` (например, `mail/my-custom`) |
| `subjectTemplate` | Шаблон темы с плейсхолдерами `{key}` — заменяются значениями модели |
| `requiredModelKeys` | Обязательные ключи модели, без которых рендер провалится |

Общие значения модели (`projectName`, `baseUrl`, `currentYear`) подставляет
`EmailTemplateRenderService` — вызывающий их не передаёт.

### 2. HTML-шаблон

`src/main/resources/templates/mail/my-custom.html` — HTML5 + инлайн CSS:

- `<!DOCTYPE html>`, `lang="ru"`, `<meta charset="UTF-8">`, `<meta name="viewport" ...>`;
- табчная вёрстка: `role="presentation"`, `width="600"`, все стили инлайновые;
- шапка и подвал подключаются фрагментами:
  `th:replace="~{mail/fragments/header :: mailHeader}"`,
  `th:replace="~{mail/fragments/footer :: mailFooter}"`;
- ссылки — absolute-URL через `${baseUrl}`, текст ссылки ≠ URL;
- запрет: `<script>`, `<style>`, `<link>`, внешние `src=`, `th:utext`.

Письму с кодом/токеном нужна plain-часть — добавить значение в `PLAIN_REQUIRED`
(`EmailTemplateId`), иначе e2e-скрипты не найдут код в `PlainText` smtp4dev.

### 3. Вызов в сервисе

```java
emailDeliveryService.sendEmail(
    recipient,
    EmailTemplateId.MY_CUSTOM,
    Map.of("ticketId", ticketId, "portalId", portalId),
    NotificationEvent.MY_EVENT
);
```

Рассылка по участникам портала — `initNotifyPortalUsers` / `sendPortalUsersNotification`,
письмо админу — `sendAdminNotification`.

### 4. Проверки

| Проверка | Что ловит |
|---|---|
| `EmailTemplateIdTest` | пути уникальны, тема непустая, плейсхолдеры темы объявлены в `requiredModelKeys` |
| `EmailTemplateGuardTest` | у enum-значения есть HTML-файл и наоборот, подключены шапка и подвал, нет запрещённой разметки |
| `EmailTemplateRenderingTest` | рендер из classpath: полная модель, минимальная модель, plain-части |
| `EmailSendingIT` | фактическая доставка через GreenMail: заголовки, `multipart/alternative`, кириллица |

## Покрытие тестами

`NotificationManageServiceTest` (SSE и общий wake с long polling),
`NotificationWaitRegistryTest` (реестр), `NotificationControllerTest`
(контракт `/stream`), `NotificationPublisherTest` (пробуждение после
коммита), `NotificationRepeatServiceTest` (доставка повторов),
`make e2e-notifications-kc` (SSE и long polling по HTTP).

Письма: `EmailTemplateIdTest`, `EmailTemplateGuardTest`,
`EmailTemplateRenderingTest`, `EmailTemplateRenderServiceTest`,
`EmailTemplateLinksTest`, `EmailDeliveryServiceTest`, `EmailSendingIT`.
Юниты — `make test`, доставка по SMTP — `make verify`.
