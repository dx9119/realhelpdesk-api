# План: письма как Thymeleaf-шаблоны (уход от текстов в конфигурации)

## Цель

Тексты писем больше не хранятся в конфигурации. Каждое письмо — HTML5-шаблон
в `src/main/resources/templates/mail/` с inline CSS, общая шапка и футер
подключаются фрагментами Thymeleaf, тип письма обозначается enum.

## Решения (утверждены)

- Никаких текстов писем в `messages.properties` — ни тел, ни тем. Все
  ключи `email.*` удаляются.
- `EmailTemplates` удаляется целиком, его вызовы в сервисах переписываются.
- Формат письма — HTML5 + inline CSS, без отдельной text-альтернативы
  (но с plain-text деградацией — см. «e2e и smtp4dev»).
- Шаблоны в `src/main/resources/templates/mail/` (не `static/` — содержимое
  `static/` раздаётся наружу по HTTP).
- Темы писем живут в enum как шаблонные строки с плейсхолдерами.
- Метрики по типам писем — отдельный счётчик рядом с существующим `mail.sent`.
- Логирование — по конвенциям профилей: в prod ни токенов, ни адресов, ни
  содержимого писем.

## Текущее состояние

- ~35 ключей `email.*.subject` / `email.*.body` в `messages.properties`.
- `EmailTemplates` (`core/mail/model`) — обёртка над `MessageSource`,
  ~36 методов `xxxSubject()` / `xxxBody(...)`.
- `EmailDeliveryService.deliver()` отправляет plain text:
  `helper.setText(text, false)` + жёстко проставленные
  `Content-Type: text/plain`, `Content-Transfer-Encoding: 7bit`.
- 8 сервисов вызывают `EmailTemplates` и передают в доставку
  готовую строку темы и строку тела.
- `spring-boot-starter-thymeleaf` в `pom.xml` отсутствует.
- `mail.sent` — одна метрика с тегами `type` (NotificationEvent) и
  `result` (success/failure). У события `PORTAL_TRANSFER_REQUESTED` два
  разных письма — метрика смешивает их.
- Логи: `log.debug` с `LogSanitizer.email(recipient)` + `event`,
  `log.info` с `event` + `userId`. Содержимое письма в лог не попадает.
- `LogSanitizer` маскирует email, телефон, имя файла, секретные сегмента URI
  (`/password-resets/`, `/email/confirmations/`). Токен/код как значение —
  не маскируется.
- e2e (`scripts/e2e-common.sh`) читают письма smtp4dev через
  `/api/Messages/{id}/PlainText` и вытаскивают код регуляркой
  `-?[0-9]{16,}`.

## Целевое состояние

### Enum типов писем

`core/mail/model/EmailTemplateId.java`:

```java
public enum EmailTemplateId {
    REGISTRATION_LINK("mail/registration-link", "Подтверждение регистрации"),
    REGISTRATION_CODE("mail/registration-code", "Код подтверждения"),
    PASSWORD_RESET("mail/password-reset", "Сброс пароля"),
    TICKET_CREATED("mail/ticket-created", "Создана новая заявка #{ticketId}"),
    TICKET_REPLY("mail/ticket-reply", "[Заявка#{ticketId}] Новое сообщение"),
    TICKET_STATUS("mail/ticket-status", "Задан статус:{status} для заявки#{ticketId}"),
    TICKET_PRIORITY("mail/ticket-priority", "Задан приоритет:{priority} для заявки#{ticketId}"),
    TICKET_DELETED("mail/ticket-deleted", "Удалена заявка#{ticketId}"),
    PORTAL_CREATED("mail/portal-created", "Создан новый портал #{portalId}"),
    PORTAL_ADD_USER("mail/portal-add-user", "Вас добавили к порталу #{portalId}"),
    PORTAL_DELETED("mail/portal-deleted", "Удален портал#{portalId}"),
    PORTAL_TRANSFER_REQUESTED("mail/portal-transfer-requested", "Передача портала #{portalId}"),
    PORTAL_TRANSFER_PROPOSED("mail/portal-transfer-proposed", "Вам передают портал #{portalId}"),
    PORTAL_TRANSFER_ACCEPTED("mail/portal-transfer-accepted", "Портал #{portalId} сменил владельца"),
    PORTAL_TRANSFER_REJECTED("mail/portal-transfer-rejected", "Запрос на передачу портала #{portalId} отклонён"),
    PORTAL_TRANSFER_CANCELLED("mail/portal-transfer-cancelled", "Передача портала #{portalId} отменена"),
    PORTAL_TRANSFER_EXPIRED("mail/portal-transfer-expired", "Истёк срок передачи портала #{portalId}"),
    NOTIFICATION_REPEAT("mail/notification-repeat", "[Напоминание] {title}");

    private final String templatePath;   // без .html — расширение добавляет Thymeleaf
    private final String subjectTemplate;
}
```

`EmailTemplateId` ≠ `NotificationEvent`: у события `PORTAL_TRANSFER_REQUESTED`
два разных письма (для участников портала и для кандидата), поэтому сопоставление
«событие → письмо» не одно к одному. `NotificationEvent` остаётся как есть —
он отвечает за стоп-листы, mute и `mail.sent`.

### Структура шаблонов

```
src/main/resources/templates/mail/
├── fragments/
│   ├── layout.html          # обёртка: <html>, <body>, инлайн-стили, header + footer
│   ├── header.html          # th:fragment="mailHeader"
│   └── footer.html          # th:fragment="mailFooter"
├── registration-link.html
├── registration-code.html
├── password-reset.html
├── ticket-created.html
├── ticket-reply.html
├── ticket-status.html
├── ticket-priority.html
├── ticket-deleted.html
├── portal-created.html
├── portal-add-user.html
├── portal-deleted.html
├── portal-transfer-requested.html
├── portal-transfer-proposed.html
├── portal-transfer-accepted.html
├── portal-transfer-rejected.html
├── portal-transfer-cancelled.html
├── portal-transfer-expired.html
└── notification-repeat.html
```

`fragments/layout.html` — общая рамка письма; конкретный шаблон либо
использует `th:replace` на header/footer напрямую, либо оборачивается через
`th:insert`/`th:replace` с `th:fragment="content"`. Итог один: шапка и подвал
не дублируются в 18 файлах.

Требования к вёрстке:
- `<!DOCTYPE html>`, `lang="ru"`, `<meta charset="UTF-8">`,
  `<meta name="viewport" ...>`.
- Все стили инлайновые (почтовые клиенты вырезают `<style>` и внешние CSS),
  табчная разметка там, где нужен layout, `max-width: 600px`.
- Ссылки — полные absolute-URL, текст ссылки ≠ URL (для читаемости).
- Никакого JS, никаких внешних изображений без fallback-текста.

### Резолвер письма

`core/mail/service/EmailTemplateRenderService.java`:

```java
public record EmailContent(String subject, String html) {}

public EmailContent render(EmailTemplateId template, Map<String, Object> params)
```

- Дополняет модель общими значениями (`projectName`, `frontDomain`,
  `baseUrl`, `currentYear`) — их не передаёт каждый вызывающий.
- Рендерит тему подстановкой `{key}` из модели; длина темы ограничивается
  (обрезка с `…`), чтобы длинные заголовки уведомлений не ломали заголовок
  письма.
- Рендерит тело через `TemplateEngine.process(templatePath, context)`.
- Ошибку рендера оборачивает в своё исключение **без** содержимого модели
  в сообщении (Thymeleaf в текст исключения кладёт имя шаблона и контекст).

### Доставка

`EmailDeliveryService`:
- сигнатуры принимают `EmailTemplateId` + модель вместо `subject` + `text`;
- `MimeMessageHelper(mimeMessage, true, "UTF-8")` + `setText(html, true)`;
- заголовок `Content-Type: text/html; charset=UTF-8`,
  `Content-Transfer-Encoding: quoted-printable` (7bit для кириллицы в HTML
  негоден);
- лимит восстановления пароля, стоп-листы, mute, журнал `EmailLog`,
  метрики и `Observation` — без изменений по логике.

### Общие ссылки

Ссылки на заявку/портал сейчас собираются из `domain()` конкатенацией в
каждом тексте. Вынести в один резолвер ссылок и отдавать в модель шаблона
готовыми значениями (`ticketUrl`, `portalManagerUrl`, `notifySettingsUrl`,
`passResetUrl`, `portalTransferUrl`) — иначе 18 шаблонов разойдутся в
формате URL. Существующий тест «ссылки на заявку одинаковы во всех
шаблонах» сохраняется как проверка на резолвере.

### Метрики (наблюдаемость)

**Новая метрика `email.template.sent`:**

```
email.template.sent_total{type="REGISTRATION_LINK",result="success"}
email.template.sent_total{type="PASSWORD_RESET",result="failure"}
```

- `type` — значение `EmailTemplateId` (конечный набор, не высококардинальный).
- `result` — `success` / `failure` (те же константы, что в `auth.attempts`).
- Описание: «Отправленные письма по типу шаблона и результату».
- Считается ровно там же, где `mail.sent` — в `deliver()`: стоп-лист, mute и
  лимит восстановления письмо не отправляют и счётчик не трогают.

**`mail.sent` остаётся как есть** — `type` = `NotificationEvent`,
`result` = success/failure. Не удаляется: по ней уже построены запросы в
`docs/observability/metrics.md` и дашборды.

`BusinessMetrics`:

```java
public void emailTemplateSent(EmailTemplateId template, boolean success)
```

Правила класса сохраняются: теги — только значения конечных enum'ов,
email/userId/ticketId в теги не попадают, null-параметр пропускается.

**Алерты** (`infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml`):
добавить правило на долю неудач по `email.template.sent_total{result="failure"}`
с группировкой по `type` — видно, какой именно шаблон падает. Существующие
правила по `mail.sent` не трогаются.

### Логирование

Формат задаёт `logback-spring.xml`: в `dev` — читаемый `DEBUG_PATTERN` с
MDC-колонками, в `!dev` — JSON по ECS. Новый код вписывается в это, своих
аппендеров не появляется.

Уровни и содержание:

| Событие | Уровень | Что в строке | Чего нет |
|---|---|---|---|
| Перед отправкой | `DEBUG` | `template`, `event`, `LogSanitizer.email(recipient)` | токена, кода, темы, HTML |
| Отправлено | `INFO` | `template`, `event`, `userId` | email, темы, HTML |
| Получатель не найден в БД | `WARN` | `userId` | email |
| Стоп-лист / mute | `DEBUG` | `template`, `event`, `userId` | email |
| Ошибка SMTP | как сейчас (`MailException` летит вызывающему, лог наблюдает) | — | — |
| Ошибка рендера шаблона | `ERROR` | `template` | модель целиком, стек в ответе |

Правила, которые надо выдержать:

- **Токены и коды в лог не пишутся.** Модель шаблона (`Map`) в лог не
  передаётся целиком — ни в `toString`, ни как аргумент. В `EmailDeliveryService`
  логируется только `template.name()`.
- **Тело письма в лог не пишется** ни на одном уровне, включая `TRACE`:
  в письмах есть код подтверждения, ссылка сброса, причина передачи портала.
- **Email — только через `LogSanitizer.email(...)`.** Строка `Письмо
  отправлено, event=…` остаётся без адреса (PII-конвенция уже описана в
  javadoc `sendEmail`).
- **Диагностика ошибки шаблона.** `TemplateEngine` при отсутствии/битом
  шаблоне бросает `TemplateProcessingException` с именем шаблона — имя
  безопасного enum-пути, его логируем; сообщение Thymeleaf целиком не
  пробрасываем наружу (в prod `server.error.include-message=never`, но и в
  лог лишнее не тащим).
- **Профильные уровни.** В `application-dev.properties` — `DEBUG` на
  `com.ukhanov.realhelpdesk` уже есть, поэтому сообщения `DEBUG` видны локально
  без правок. Для отладки самих шаблонов в dev добавляется
  `logging.level.org.thymeleaf=DEBUG` (в prod — нет).
- **Prod-профиль.** Уровней Thymeleaf в `application-prod.properties` быть не
  должно: там всё `WARN`, и JSON-конвертер сам уберёт `clientIp` из MDC.
  Отдельных ключей `logging.*` для почты в prod не заводим.
- **`spring.thymeleaf.cache`** — `false` в `application-dev.properties`
  (править шаблон без рестарта), `true` в `application-prod.properties`
  и в `application-test.properties`.

### e2e и smtp4dev (не сломать)

`scripts/e2e-common.sh` берёт код подтверждения из
`/api/Messages/{id}/PlainText` регуляркой `-?[0-9]{16,}`. Письмо станет
HTML, поэтому:

- письма с кодом/токеном (`REGISTRATION_LINK`, `REGISTRATION_CODE`,
  `PASSWORD_RESET`) отправлять **multipart/alternative**: `text/plain` —
  короткая деградация (тот же код открытым текстом), `text/html` — вёрстка.
  Это же повышает читаемость в клиентах с выключенным HTML;
  `MimeMessageHelper(mime, true, "UTF-8")` для этого уже нужен;
- остальные письма — только `text/html`;
- скрипты править нельзя, если регулярка продолжает находить код в plain-части
  — проверять до коммита (`make dev-up` + `scripts/e2e-jwt-flow.sh`).

### Покрытие тестами

Стиль проекта: юнит-тесты идут через Surefire (`mvn test`, быстрые, без
контейнеров), интеграционные — `**/*IT.java` через Failsafe (`mvn verify`,
Testcontainers Postgres + GreenMail). Везде JUnit 5, AssertJ, `@DisplayName`
по-русски, блоки `// given` / `// when` / `// then`, фикстуры в `support`-
пакетах. Стражи лежат в корне `src/test/java/com/ukhanov/realhelpdesk/`.

**Новые юнит-тесты:**

| Тест | Проверяет |
|---|---|
| `EmailTemplateRenderServiceTest` | тема рендерится из шаблона с параметрами; длинные темы обрезаются; общие значения (`projectName`, `currentYear`) подставляются без передачи вызывающим; absent-параметр → предсказуемая ошибка, а не `null` в письме |
| `EmailTemplateLinksTest` | ссылки на заявку идентичны во всех шаблонах (`/portal/{portalId}/ticket/{ticketId}`); URL собирается из `frontDomain`, `http://`/`https://` не дублируется |
| `EmailTemplateIdTest` | пути уникальны, тема непустая, плейсхолдеры темы имеют соответствующий ключ в модели |
| `EmailTemplateGuardTest` (страж, корень `src/test/java`) | у каждого `EmailTemplateId` есть `templates/mail/**.html`; ни один HTML в `templates/mail/` не остался без enum-значения; каждый шаблон подключает `mailHeader` и `mailFooter`; в шаблонах нет `<script>`, `th:utext` и внешних `src=` |
| `EmailDeliveryServiceTest` (обновить) | HTML-доставка: `setText(html, true)`, `Content-Type: text/html`; метрика считается по `template` и по `event`; стоп-лист/mute/лимит счётчик не двигают |
| `BusinessMetricsTest` (дописать) | `emailTemplateSent` → теги `type`+`result`, `success`/`failure` считаются раздельно, `null`-шаблон метрику не создаёт |

**Интеграционные (`*IT`, GreenMail):**

| Тест | Проверяет |
|---|---|
| `EmailSendingIT` (обновить) | письмо доходит с `Content-Type: text/html`; `isHtml()` истина; HTML содержит шапку, футер и ссылку; кириллица в теме и теле не испорчена (декодирование `quoted-printable`); для `PASSWORD_RESET` есть `multipart/alternative` с кодом в plain-части |
| `EmailTemplateRenderingIT` | полный рендер из реального classpath: все 18 шаблонов рендерятся с минимальной моделью и содержат `mailHeader`/`mailFooter` — ловит битую вёрстку до отправки |
| `EmailMetricsIT` (или блок в `HttpObservabilityIT`) | `email.template.sent_total` появляется в `/actuator/prometheus` с ожидаемыми тегами после реальной отправки |

**Сервисные тесты (обновить моки):** `RegistrationServiceTest`,
`UserManageServiceTest`, `TicketManageServiceTest`, `MessageManageServiceTest`,
`PortalManageServiceTest`, `PortalDeletionServiceTest`,
`PortalTransferNotificationServiceTest`, `NotificationRepeatServiceTest` —
мокают `EmailDeliveryService` и проверяют, что ушёл нужный `EmailTemplateId`
и нужные параметры модели (ArgumentCaptor), а не строка темы.

**Удалить:** `EmailTemplatesTest`, `EmailTemplatesWiringTest`,
`EmailTemplatesFixture` (заменяются резолвер-тестами и `EmailContentFixture`).

**Метрики покрытия:** проект порога по строкам не настроивает, поэтому
критерий — каждый новый класс имеет тест, и каждый `EmailTemplateId` покрыт
стражем + минимум одним рендер-тестом. Проверка: `make test` (юниты) и
`make verify` (IT).

## Шаги

1. **`pom.xml`** — добавить `spring-boot-starter-thymeleaf` (версию ведёт
   Spring Boot BOM). Секции build/quality-plugins не трогать.
2. **Фрагменты** — `layout.html`, `header.html`, `footer.html`.
3. **Резолвер ссылок** + общие значения модели.
4. **`EmailTemplateId`** — 18 значений, темы-шаблоны.
5. **`EmailTemplateRenderService`** — рендер темы и тела, безопасная ошибка.
6. **18 HTML-шаблонов** — перенос содержания из `messages.properties`
   в разметку с сохранением формулировок и всех переменных.
7. **`BusinessMetrics`** — `emailTemplateSent(EmailTemplateId, boolean)`.
8. **`EmailDeliveryService`** — HTML-доставка (`multipart/alternative` для
   писем с кодом), новые сигнатуры, обе метрики и конвенция логов.
9. **Конфигурация** — `spring.thymeleaf.cache` (dev `false`, prod/test `true`),
   `logging.level.org.thymeleaf=DEBUG` в dev.
10. **8 сервисов-отправителей** — переход на `EmailTemplateId` + модель:
    `RegistrationService`, `UserManageService`, `TicketManageService`,
    `MessageManageService`, `PortalManageService`, `PortalDeletionService`,
    `PortalTransferNotificationService`, `NotificationRepeatService`.
11. **Удалить `EmailTemplates`** и все ключи `email.*` из
    `messages.properties`.
12. **Тесты** — по таблице раздела «Покрытие тестами».
13. **e2e** — прогнать `scripts/e2e-jwt-flow.sh`,
    `scripts/e2e-portal-transfer.sh`, `scripts/e2e-notifications.sh`;
    при необходимости — только чтение писем в `scripts/e2e-common.sh`
    (согласовать с пользователем).
14. **Алерты** — правило на `email.template.sent_total{result="failure"}`
    в `infrastructure/observability/mimir/rules/anonymous/realhelpdesk.yml`.
15. **Документация**:
    - `docs/notifications.md` — как добавить новое письмо (enum + шаблон +
      вызов), где лежат шаблоны, почему не `static/`;
    - `docs/observability/metrics.md` — строка `email.template.sent` в
      таблице метрик и в списке grep-запросов;
    - `docs/logging.md`/`docs/debugging.md` — только если появляется новый
      уровень/ключ (иначе не трогать).
16. **Проверки** — `mvn spotless:apply`, `make check-ci`.

## На что смотреть

- `spring.thymeleaf.cache`: `false` в dev, `true` в prod.
- Кириллица: `MimeMessageHelper(mime, true, "UTF-8")` +
  `Content-Transfer-Encoding: quoted-printable`.
- `static/cache-control`, `static.base-url` и отдача вложений не затрагиваются
  — папка `static/` остаётся только под раздаточным контентом (`captcha/`).
- Архитектура: `EmailTemplateId` и резолвер живут в `core.mail`,
  feature-сервисы уже зависят от `core.mail` — новых связей нет;
  `ArchitectureTest` проверяет, что `..service..` не зависит от
  `..controller..`.
- `ApplicationConfigSchemaTest` сверяет схему конфигурации с
  `infrastructure/.env.dev.example`: `email.domain/from/notify/project-name`
  остаются (из них берутся значения для модели шаблона), удаляются только
  ключи из `messages.properties`.
- `ObservabilityRunbookGuardTest` следит за согласованностью наблюдаемости и
  runbook'ов — новую метрику добавлять в `metrics.md` сразу, иначе страж
  рассинхронизируется.

## Открытые вопросы

1. Темы `TICKET_STATUS`/`TICKET_PRIORITY` печатают enum-значение напрямую
   (`Задан статус:OPEN`). Нужны ли русские названия статусов/приоритетов в
   темах и в теле письма, или оставляем как есть?
2. Письма с кодом отправляем `multipart/alternative` (нужно, чтобы e2e
   продолжали читать `PlainText`) — подтверждай, что это приемлемо вместо
   правки e2e-скриптов.
