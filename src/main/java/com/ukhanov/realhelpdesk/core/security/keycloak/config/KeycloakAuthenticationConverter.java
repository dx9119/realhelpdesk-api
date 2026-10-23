package com.ukhanov.realhelpdesk.core.security.keycloak.config;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.context.annotation.Profile;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.model.UserPlatformSource;
import com.ukhanov.realhelpdesk.core.security.user.model.UserRole;
import com.ukhanov.realhelpdesk.core.security.user.model.UserStatus;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Превращает проверенный JWT Keycloak в аутентификацию приложения: {@code principal} — id пользователя из БД, роли — из
 * БД, где они синхронизированы с realm-ролями токена.
 *
 * <p>
 * Конвертер — единственное место keycloak-контура, которое читает пользователя и пишет в БД, поэтому здесь же решается
 * весь жизненный цикл учётной записи:
 * </p>
 * <ol>
 * <li>email берётся из claim {@code email} — без него токен бесполезен: приложение связывает вход с учётной записью по
 * адресу;</li>
 * <li>пользователь ищется по email без учёта регистра; отсутствующего создаёт, если
 * {@code keycloak.auto-create-users=true}, иначе отклоняет токен (401) — так «случайный» пользователь Keycloak не
 * получает доступ к данным;</li>
 * <li>realm-роли ({@code realm_access.roles}) повышают роль в БД, если {@code keycloak.sync-roles=true};</li>
 * <li>неактивная учётная запись (BLOCKED/DISABLED) отклоняется, даже если токен валиден.</li>
 * </ol>
 *
 * <p>
 * Роли в authority берутся из БД, а не из токена — так же, как в локальном контуре ({@code JwtAuthFilter}): блокировка
 * пользователя и понижение роли действуют сразу, без ожидания истечения токена. Имена realm-ролей в Keycloak должны
 * совпадать с именами {@link UserRole} ({@code ROLE_USER}, {@code ROLE_OPERATOR}, {@code ROLE_NONE}) — тогда
 * синхронизация не требует таблицы соответствия.
 * </p>
 *
 * <p>
 * Синхронизация только повышает роль: адрес из {@code APP_OPERATOR_EMAILS} (оператор-бутстрап) и роль, назначенная
 * вручную в БД, не снимаются — та же логика, что и у бутстрапа операторов. Понижение роли делается в БД осознанно.
 * </p>
 */
@Slf4j
@Component
@Profile("keycloak")
@RequiredArgsConstructor
public class KeycloakAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    /** Claim с адресом пользователя: по нему вход связывается с учётной записью. */
    static final String EMAIL_CLAIM = "email";

    /** Claim с именем пользователя в Keycloak. */
    static final String GIVEN_NAME_CLAIM = "given_name";

    /** Claim с фамилией пользователя в Keycloak. */
    static final String FAMILY_NAME_CLAIM = "family_name";

    /** Контейнер realm-ролей в токене Keycloak: {@code realm_access: { roles: [...] }}. */
    static final String REALM_ACCESS_CLAIM = "realm_access";

    /** Ключ списка ролей внутри {@code realm_access}. */
    static final String ROLES_KEY = "roles";

    /**
     * Приоритет ролей при синхронизации: набор realm-ролей сводится к одной — самой полной. Порядок задан явно, а не
     * порядком объявления enum: {@code ROLE_USER} объявлен после {@code ROLE_OPERATOR}, и по ordinal «пользователь»
     * оказался бы старше «оператора».
     */
    private static final List<UserRole> ROLE_PRIORITY = List.of(UserRole.ROLE_OPERATOR, UserRole.ROLE_USER,
            UserRole.ROLE_NONE);

    /**
     * Признак пароля, заведённого Keycloak'ом: вход по паролю в keycloak-профиле отключён (AuthController не
     * создаётся), а {@code password_hash} объявлен {@code NOT NULL} — значение не должно быть валидным хешем BCrypt,
     * чтобы случайно не совпасть с введённым паролем.
     */
    private static final String NO_LOCAL_PASSWORD_PREFIX = "keycloak-no-local-password:";

    private final UserDomainService userDomainService;
    private final KeycloakProperties keycloakProperties;

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        String email = jwt.getClaimAsString(EMAIL_CLAIM);
        if (email == null || email.isBlank()) {
            throw reject("invalid_token", "В токене Keycloak отсутствует claim email");
        }

        UserModel user = userDomainService.findUserByEmail(email).orElseGet(() -> registerUser(jwt, email));
        requireActive(user);
        syncRole(user, realmRoles(jwt));

        // Principal — id пользователя, а не sub токена: CurrentUserProvider читает id из имени аутентификации,
        // и вся проверка доступа (владелец портала, автор заявки) опирается на него.
        Collection<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority(user.getUserRole().name()));
        return new JwtAuthenticationToken(jwt, authorities, user.getId().toString());
    }

    /** Создание учётной записи при первом входе: только если это разрешено настройкой. */
    private UserModel registerUser(Jwt jwt, String email) {
        if (!keycloakProperties.isAutoCreateUsers()) {
            throw reject("invalid_token", "Учётная запись для этого email не заведена, а автосоздание выключено");
        }

        UserModel user = new UserModel();
        user.setEmail(email);
        // Имя/фамилия обязательны в схеме: без claim'ов берём локальную часть адреса, чтобы создание не упало на NOT
        // NULL.
        String fallback = localPart(email);
        user.setFirstName(orDefault(jwt.getClaimAsString(GIVEN_NAME_CLAIM), fallback));
        user.setLastName(orDefault(jwt.getClaimAsString(FAMILY_NAME_CLAIM), fallback));
        user.setPasswordHash(NO_LOCAL_PASSWORD_PREFIX + UUID.randomUUID());
        user.setUserPlatformSource(UserPlatformSource.KEYCLOAK);
        user.setUserStatus(UserStatus.ACTIVE);
        // Email уже подтверждён Keycloak'ом: письмо с кодом от приложения пользователю не нужно.
        user.setEmailVerified(true);

        UserModel saved = userDomainService.saveUser(user);
        logger.info("Keycloak: создан пользователь id={} (email {})", saved.getId(), LogSanitizer.email(email));
        return saved;
    }

    /** Неактивную учётную запись не спасает даже валидный токен: блокировка действует до истечения срока жизни JWT. */
    private void requireActive(UserModel user) {
        if (user.getUserStatus() != UserStatus.ACTIVE) {
            logger.warn("Keycloak: вход отклонён, учётная запись id={} не активна", user.getId());
            throw reject("invalid_token", "Учётная запись неактивна");
        }
    }

    /**
     * Повышение роли по realm-ролям токена. Роль в токене, которой нет среди {@link UserRole}, игнорируется: в realm
     * живут и служебные роли Keycloak ({@code offline_access}, {@code uma_authorization}), и они не должны влиять на
     * права в приложении.
     */
    private void syncRole(UserModel user, List<String> realmRoles) {
        if (!keycloakProperties.isSyncRoles() || realmRoles.isEmpty()) {
            return;
        }

        Optional<UserRole> tokenRole = ROLE_PRIORITY.stream()
                .filter(role -> realmRoles.contains(role.name()))
                .findFirst();
        if (tokenRole.isEmpty()) {
            logger.debug("Keycloak: в realm-ролях нет ролей приложения, роль пользователя id={} не меняется",
                    user.getId());
            return;
        }

        UserRole resolved = tokenRole.get();
        if (roleWeight(resolved) <= roleWeight(user.getUserRole())) {
            return;
        }

        user.setUserRole(resolved);
        userDomainService.saveUser(user);
        logger.info("Keycloak: пользователю id={} назначена роль {}", user.getId(), resolved);
    }

    /** Вес роли для сравнения «повышение/понижение»: больше — полнее права. */
    private static int roleWeight(UserRole role) {
        int index = ROLE_PRIORITY.indexOf(role);
        return index < 0 ? -1 : ROLE_PRIORITY.size() - index;
    }

    /**
     * Realm-роли из {@code realm_access.roles}: вложенный объект, поэтому читается из карты claim'ов, а не как список
     * строк.
     */
    static List<String> realmRoles(Jwt jwt) {
        Object realmAccess = jwt.getClaim(REALM_ACCESS_CLAIM);
        if (!(realmAccess instanceof Map<?, ?> access)) {
            return List.of();
        }
        Object roles = access.get(ROLES_KEY);
        if (!(roles instanceof Collection<?> collection)) {
            return List.of();
        }
        return collection.stream().filter(Objects::nonNull).map(String::valueOf).toList();
    }

    private static String localPart(String email) {
        int at = email.indexOf('@');
        String local = at > 0 ? email.substring(0, at) : email;
        return local.toLowerCase(Locale.ROOT);
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /** Отказ аутентификации: {@code invalid_token} превращается в 401 (WWW-Authenticate), а не в 403 без объяснений. */
    private static OAuth2AuthenticationException reject(String errorCode, String description) {
        return new OAuth2AuthenticationException(new OAuth2Error(errorCode, description, null), description);
    }
}
