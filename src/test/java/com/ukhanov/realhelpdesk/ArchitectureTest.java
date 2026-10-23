package com.ukhanov.realhelpdesk;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * Архитектурные инварианты проекта: слои не пересекают границы.
 *
 * <p>
 * Направление зависимостей (снаружи внутрь): controller → feature (менеджерские сервисы) → domain (доменные сервисы) →
 * domain (репозитории). core — инфраструктура общего уровня.
 * </p>
 *
 * <p>
 * Падение этого теста на {@code mvn test} осмысленно для нарушителя: ArchUnit печатает конкретный класс, метод и
 * правило, поэтому код переписывается под правильный слой, а не «в лоб».
 * </p>
 *
 * <p>
 * Осознанные исключения из «идеальной» схемы: доменные сервисы бросают доменные исключения, объявленные в
 * {@code feature.*.exception} — обратный вызов в feature-слой при этом запрещён; менеджерские сервисы {@code feature.*}
 * координируют несколько репозиториев и доменных сервисов напрямую, это их назначение.
 * </p>
 */
@AnalyzeClasses(packages = "com.ukhanov.realhelpdesk", importOptions = ImportOption.DoNotIncludeTests.class)
@DisplayName("Архитектура: слои не пересекают границы")
final class ArchitectureTest {

    private ArchitectureTest() {
    }

    /** Репозиторий — деталь персистентности: контроллер обязан идти через сервис. */
    @ArchTest
    static final ArchRule CONTROLLERS_NO_REPOSITORIES = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..repository..")
            .because("контроллер читает и пишет только через сервис: репозиторий — деталь персистентности");

    /**
     * Сущность в HTTP-контракте связывает API с формой таблицы: любое изменение схемы ломает внешний контракт. Ответы —
     * только DTO/record'ы.
     */
    @ArchTest
    static final ArchRule CONTROLLERS_NO_ENTITIES = noClasses()
            .that()
            .resideInAPackage("..controller..")
            .should()
            .dependOnClassesThat()
            .areMetaAnnotatedWith("jakarta.persistence.Entity")
            .because("в HTTP-контракте — DTO: сущность наружу связывает API со схемой БД");

    @ArchTest
    static final ArchRule SERVICES_NO_CONTROLLERS = noClasses()
            .that()
            .resideInAPackage("..service..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..controller..")
            .because("зависимость направлена вниз: контроллер вызывает сервис, а не наоборот");

    /**
     * Domain-слой не знает о фичах: менеджерские пакеты (сервисы, контроллеры, мапперы, DTO) — надстройка над доменом.
     * Пакет {@code feature.*.exception} — единственная легитимная точка касания: домен генерирует доменные исключения,
     * объявленные там.
     */
    @ArchTest
    static final ArchRule DOMAIN_NO_FEATURE = noClasses()
            .that()
            .resideInAPackage("com.ukhanov.realhelpdesk.domain..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "com.ukhanov.realhelpdesk.feature..service..",
                    "com.ukhanov.realhelpdesk.feature..controller..",
                    "com.ukhanov.realhelpdesk.feature..mapper..",
                    "com.ukhanov.realhelpdesk.feature..dto..")
            .because("домен не знает о фичах: менеджерский слой собирается поверх домена, а не наоборот");

    @ArchTest
    static final ArchRule REPOSITORIES_NO_SERVICES = noClasses()
            .that()
            .resideInAPackage("..repository..")
            .should()
            .dependOnClassesThat()
            .resideInAPackage("..service..")
            .because("репозиторий — самый внутренний слой: зависимость от сервиса создала бы цикл");

    /** Фичи не должны образовывать циклы: ticket → portal → ticket снова делает слои неразрезаемыми. */
    @ArchTest
    static final ArchRule FEATURE_SLICES_NO_CYCLES = slices()
            .matching("..feature.(*)..")
            .should()
            .beFreeOfCycles()
            .because("фичи развиваются независимо; циклический импорт связывает их релизы");

    /** Транзакция на методе контроллера держит HTTP-поток и внешние вызовы внутри tx. */
    @ArchTest
    static final ArchRule CONTROLLERS_NO_TRANSACTIONAL = noMethods()
            .that()
            .areDeclaredInClassesThat()
            .resideInAPackage("..controller..")
            .should()
            .beAnnotatedWith(Transactional.class)
            .because("транзакционная граница — сервис, а не контроллер");

    /** JJWT API принимает java.util.Date; за его пределами — только java.time. */
    @ArchTest
    static final ArchRule NO_UTIL_DATE_OUTSIDE_JWT = noClasses()
            .that()
            .resideOutsideOfPackage("..security.localjwt..")
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.util.Date")
            .because("новый код использует Instant/OffsetDateTime; java.util.Date остался только на границе JJWT");

    @ArchTest
    static final ArchRule NO_SQL_DATE = noClasses()
            .should()
            .dependOnClassesThat()
            .haveFullyQualifiedName("java.sql.Date")
            .because("java.sql.Date — legacy JDBC-тип; для дат без времени есть LocalDate, для момента — Instant");

    /** Время без Clock не тестируется детерминированно; исключение — @PrePersist/аудит в core.mail. */
    @ArchTest
    static final ArchRule NO_LOCAL_DATE_TIME_NOW_OUTSIDE_MAIL = noClasses()
            .that()
            .resideOutsideOfPackage("..core.mail..")
            .should()
            .callMethod(LocalDateTime.class, "now")
            .because("LocalDateTime.now() без Clock делает тест недетерминированным");

    @ArchTest
    static final ArchRule REST_CONTROLLERS_IN_CONTROLLER_PACKAGE = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .resideInAPackage("..controller..")
            .because("@RestController — HTTP-адаптер; его место в ..controller..");

    @ArchTest
    static final ArchRule REST_CONTROLLERS_NAMED_CONTROLLER = classes()
            .that()
            .areAnnotatedWith(RestController.class)
            .should()
            .haveSimpleNameEndingWith("Controller")
            .because("по имени видно назначение класса и его слой");

    @ArchTest
    static final ArchRule SERVICES_IN_SERVICE_PACKAGE = classes()
            .that()
            .areAnnotatedWith(Service.class)
            .should()
            .resideInAPackage("..service..")
            .because("@Service — бизнес-логика; его место в ..service..");

    @ArchTest
    static final ArchRule SERVICES_NAMED_SERVICE = classes()
            .that()
            .areAnnotatedWith(Service.class)
            .should()
            .haveSimpleNameEndingWith("Service")
            .because("по имени видно назначение класса и его слой");
}
