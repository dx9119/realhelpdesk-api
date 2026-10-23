package com.ukhanov.realhelpdesk.feature.usermanager.service;

import java.io.UnsupportedEncodingException;
import java.security.SecureRandom;
import java.util.Objects;

import jakarta.mail.MessagingException;
import jakarta.transaction.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.observability.AuthMetrics;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.exception.TokenException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.TokenStatus;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.NewPasswdRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.PatchUserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.RecoveryRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoRequest;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.UserInfoResponse;
import com.ukhanov.realhelpdesk.feature.usermanager.exception.UserManageException;
import com.ukhanov.realhelpdesk.feature.usermanager.mapper.UserMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class UserManageService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CurrentUserProvider currentUserProvider;
    private final UserDomainService userDomainService;
    private final EmailDeliveryService emailDeliveryService;
    private final PasswordEncoder passwordEncoder;
    private final AuthMetrics authMetrics;
    private final GetTokenService getTokenService;
    private final EmailTemplates emailTemplates;
    private final UserMapper userMapper;

    public UserInfoResponse getUserInfo() {
        UserModel user = currentUserProvider.getCurrentUserModel();
        return userMapper.toResponse(user);
    }

    @Transactional
    public UserInfoResponse updateUserInfo(UserInfoRequest request, String ifMatch) throws UserManageException {
        Objects.requireNonNull(request, "Request не должен быть null");

        UserModel currentUser = currentUserProvider.getCurrentUserModel();
        requireFreshVersion(currentUser, ifMatch);

        currentUser.setFirstName(request.firstName());
        currentUser.setLastName(request.lastName());
        currentUser.setMiddleName(request.middleName());
        currentUser.setAdditionalInfo(request.additionalInfo());

        UserModel updatedUser = userDomainService.saveUser(currentUser);
        logger.info("Профиль пользователя {} обновлён", updatedUser.getId());

        return userMapper.toResponse(updatedUser);
    }

    /** Частичное обновление профиля: меняются только переданные поля ({@code null} — «не трогать»). */
    @Transactional
    public UserInfoResponse patchUserInfo(PatchUserInfoRequest request, String ifMatch) throws UserManageException {
        Objects.requireNonNull(request, "Request не должен быть null");
        if (request.isEmpty()) {
            throw new UserManageException("Не передано ни одного поля для изменения", HttpStatus.BAD_REQUEST);
        }

        UserModel currentUser = currentUserProvider.getCurrentUserModel();
        requireFreshVersion(currentUser, ifMatch);

        if (request.firstName() != null) {
            currentUser.setFirstName(request.firstName());
        }
        if (request.lastName() != null) {
            currentUser.setLastName(request.lastName());
        }
        if (request.middleName() != null) {
            currentUser.setMiddleName(request.middleName());
        }
        if (request.additionalInfo() != null) {
            currentUser.setAdditionalInfo(request.additionalInfo());
        }

        UserModel updatedUser = userDomainService.saveUser(currentUser);
        logger.info("Профиль пользователя {} обновлён частично (PATCH)", updatedUser.getId());

        return userMapper.toResponse(updatedUser);
    }

    /** If-Match: клиент присылает версию профиля, которую видел; несовпадение — 412 до записи. */
    private void requireFreshVersion(UserModel user, String ifMatch) throws UserManageException {
        if (!EntityTag.satisfiedBy(ifMatch, EntityTag.of("user", user.getVersion()), user.getVersion())) {
            throw new UserManageException("Профиль уже изменён: данные устарели, обновите страницу и повторите",
                    HttpStatus.PRECONDITION_FAILED);
        }
    }

    public void sendResetLink(RecoveryRequest request) throws MessagingException, UnsupportedEncodingException {
        Objects.requireNonNull(request, "RecoveryRequest не должен быть null");

        logger.debug("Запрошено восстановление пароля, email={}", LogSanitizer.email(request.email()));

        UserModel user;
        try {
            user = userDomainService.getUserByEmail(request.email());
        } catch (UsernameNotFoundException ex) {
            authMetrics.record(AuthMetrics.TYPE_PASSWORD_RESET, AuthMetrics.RESULT_FAILURE);
            throw ex;
        }

        Long recoverPasswdToken = RANDOM.nextLong();
        user.setRecoveryPasswdToken(recoverPasswdToken);

        userDomainService.saveUser(user);

        emailDeliveryService.sendEmail(user.getEmail(), emailTemplates.passwordResetSubject(),
                emailTemplates.passwordResetBody(recoverPasswdToken.toString()), NotificationEvent.RECOVERY_PASSWORD,
                user.getId());
        logger.info("Отправлено письмо восстановления пароля, userId={}", user.getId());
        authMetrics.record(AuthMetrics.TYPE_PASSWORD_RESET, AuthMetrics.RESULT_SUCCESS);
    }

    @Transactional
    public void setNewPasswd(Long code, NewPasswdRequest request) throws TokenException {
        Objects.requireNonNull(code, "Код не должен быть null");
        Objects.requireNonNull(request, "Запрос не должен быть null");

        UserModel user;
        try {
            user = userDomainService.getUserByRecoveryPasswdToken(code);
        } catch (UsernameNotFoundException ex) {
            authMetrics.record(AuthMetrics.TYPE_PASSWORD_RESET, AuthMetrics.RESULT_FAILURE);
            throw ex;
        }

        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRecoveryPasswdToken(RANDOM.nextLong());
        // Отзываем access-токены, выданные до смены пароля
        user.incrementTokenVersion();

        userDomainService.saveUser(user);

        // Отзываем все ранее выданные refresh-токены; новый выдастся при следующем входе.
        // Bulk-UPDATE одним запросом вместо поштучного сохранения
        int revoked = getTokenService.revokeActiveRefreshTokens(user, TokenStatus.PASSWD_CHANGE);
        logger.info("Пароль изменён, отозвано refresh-токенов: {}, userId={}", revoked, user.getId());
        authMetrics.record(AuthMetrics.TYPE_PASSWORD_RESET, AuthMetrics.RESULT_SUCCESS);
    }

}
