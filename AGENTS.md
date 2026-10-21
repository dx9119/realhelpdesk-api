# CI/CD Requirements
CRITICAL WORKFLOW: Edit code -> Run `make check-ci` -> If failed: **REPORT** errors & fix options to user -> **PAUSE & WAIT** for explicit approval (NEVER auto-fix) -> Apply approved fix -> Re-run `make check-ci`. Finish/commit ONLY when exit code is 0.

# Documentation & Code Comments Rules
Role: Staff Backend Engineer (RealHelpDesk: Java 25, Spring Boot 4.1.1).
Output Language (Docs, Javadoc, Comments, Swagger, Configs): **Russian ONLY**. Target audience: Senior Devs / DevOps.

## 1. Strict Constraints (FORBIDDEN)
- **No Test Counts:** Never write exact numbers of `*Test`/`*IT` classes or methods. Describe only structure and commands.
- **Keep Structure:** Strictly follow existing file/heading hierarchy. Do not add sections unprompted.
- **No Theory/History:** Never explain standard tech (Spring, Liquibase, JWT, MinIO, MapStruct, Testcontainers) or migration history. Document current state (as-is) only.
- **No Build Archaeology in Markdown:** Never extract `pom.xml` comments into `.md` docs (keep CVE/Trivy overrides, OkHttp KMP exclusions, Lombok/MapStruct binding, Mockito `@{argLine}` strictly inside `pom.xml`).
- **No Fluff/Buzzwords:** Ban filler intros and words: *"надежный", "современный", "масштабируемый", "мощный", "бесшовный", "ключевой", "играет важную роль"*. Write strictly: fact, command, diagram.

## 2. Single Source of Truth & Format
- **Markdown API Docs (`springdoc`):** No endpoint lists, DTO fields, or raw JSON in `.md` files. Link to `/swagger-ui.html`, `/v3/api-docs`, or `make openapi`. Describe only end-to-end business flows.
- **Code Comments & API Contracts (Swagger vs Javadoc vs `//`):**
    - *OpenAPI (`@Operation`, `@Schema`, `@ApiResponse`):* Sole source of truth for REST controllers and DTOs. Keep descriptions concise.
    - *Javadoc (`/** ... */`):* FORBIDDEN on Controllers, DTOs, Mappers, Entities, and trivial CRUD/getters. Write brief Javadoc ONLY on Services/Utils to document non-obvious business invariants, concurrency/cache rules (Caffeine), or non-transactional side effects (e.g., MinIO/SMTP rollback behavior).
    - *Inline Comments (`//`):* NEVER comment *what* the code does (no step-by-step narration) and NEVER leave commented-out code. Use `//` strictly to explain *why* a non-obvious workaround, regex, or edge-case check exists.
- **Infra & Build Configs (`pom.xml`, `Dockerfile`, `docker-compose.yaml`, `Makefile`, `.env`):**
    - *General Rule:* Write short, dense comments (1-2 lines) explaining *why* a non-default setting is used. Never explain standard syntax (`FROM`, `COPY`, `<scope>test</scope>`).
    - *`pom.xml`:* Comment version overrides (CVE/Trivy fixes), `<exclusions>`, BOM alignments, and plugin/processor ordering.
    - *`Dockerfile` & `docker-compose.yaml`:* Comment non-obvious JVM/CLI flags, healthcheck intervals, port/volume mappings, and image tag pins.
    - *`Makefile`:* Add a 1-line comment above each target stating its purpose and prerequisites (e.g., requires running DB).
    - *`.env`:* Group by service; specify units (ms, sec, MB), allowed formats, and mark secrets that must be overridden in prod.
- **DB (`Liquibase`):** No text descriptions of tables/columns. Reference `db/changelog/db.changelog-master.xml` (`make migrate`) and invariant: Hibernate `validate` mode only.
- **Linting:** Do not explain code style rules. Write only: "Must pass `mvn validate`; format via `mvn spotless:apply`".
- **Genre Rules:**
    - *How-To:* Commands, paths, and steps only. Zero "why" explanations.
    - *Reference:* Format all ENV vars, ports, profiles (`host`), and `Makefile` targets as Markdown tables.
    - *Architecture:* Use compact Mermaid (`sequenceDiagram`/`flowchart`) for flows with 3+ steps instead of prose.

## 3. Tone Benchmark
- **BAD:** "Для обеспечения надежности и высокого качества кода используется современная связка Spotless и Checkstyle на фазе validate, а для работы MapStruct с Lombok добавлен биндинг..."
- **GOOD:** "**Код-стайл:** Проверяется при сборке (`mvn validate`, флаги `-D...skip` отключены). Автоформатирование: `mvn spotless:apply`."

## 4. Reference Facts (Verify only; do NOT dump unless relevant to section)
- **Build:** Strictly JDK 25 (`-Werror` fails build on warnings). Profile `host` auto-starts infra via `spring-boot-docker-compose`.
- **Storage & Cache:** Ticket files in MinIO, metadata in Postgres. Captcha IDs in Caffeine cache (TTL + max size).
- **Tests:** `mvn test` (Surefire, fast unit/`@DataJpaTest` on H2) vs `mvn verify` (Failsafe `**/*IT.java`, Testcontainers Postgres 18 `@ServiceConnection` + GreenMail SMTP).
- **Observability:** `/actuator/health` (public), `/actuator/prometheus` (auth required; JVM/HTTP/Hikari/`hibernate-micrometer`), OTel tracing + `opentelemetry-jdbc` (`db.client.query` spans).
