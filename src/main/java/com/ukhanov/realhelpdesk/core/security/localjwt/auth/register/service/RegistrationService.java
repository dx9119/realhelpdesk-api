package com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.service;

import java.io.UnsupportedEncodingException;
import java.util.Objects;

import jakarta.mail.MessagingException;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.log.LogSanitizer;
import com.ukhanov.realhelpdesk.core.mail.exception.EmailAccessDeniedException;
import com.ukhanov.realhelpdesk.core.mail.model.EmailTemplates;
import com.ukhanov.realhelpdesk.core.mail.model.NotificationEvent;
import com.ukhanov.realhelpdesk.core.mail.service.EmailDeliveryService;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.mapper.AuthMapper;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.dto.RegisterRequest;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.register.exception.RegistrationException;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.dto.TokensResponse;
import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.service.GetTokenService;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.exception.CaptchaException;
import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.CaptchaService;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
@Service
public class RegistrationService {

    private static final int MIN_PASSWORD_LENGTH = 8;

    private final PasswordEncoder passwordEncoder;
    private final UserDomainService userDomainService;
    private final GetTokenService getTokenService;
    private final EmailDeliveryService emailDeliveryService;
    private final CaptchaService captchaService;
    private final EmailTemplates emailTemplates;
    private final AuthMapper authMapper;

    public UserModel addUser(RegisterRequest registerRequest)
            throws RegistrationException, MessagingException, EmailAccessDeniedException, UnsupportedEncodingException {
        Objects.requireNonNull(registerRequest, "getTokensRequest cannot be null");
        logger.debug("Начало регистрации, email={}", LogSanitizer.email(registerRequest.email()));

        // Проверяем наличие прошлой регистрации
        if (userDomainService.isUserExistsByEmail(registerRequest.email())) {
            throw new RegistrationException("Почта уже используется.",
                    new Throwable("Пользователь может занимать только один аккаунт."));
        }

        // Проверяем длину пароля(перестраховка)
        if (registerRequest.password().length() < MIN_PASSWORD_LENGTH) {
            throw new RegistrationException("Минимальная длина пароля - 8 символов.",
                    new Throwable("Слишком короткий пароль."));
        }

        // создаем пользователя
        UserModel newUser = authMapper.toEntity(registerRequest, passwordEncoder.encode(registerRequest.password()));

        // Сохраняем пользователя
        newUser = userDomainService.saveUser(newUser);
        logger.info("Зарегистрирован пользователь {}", newUser.getId());

        // Оповещаем админа о регистрации
        emailDeliveryService.sendAdminNotification("Новая регистрация:" + newUser.getEmail(),
                "Кто,что:" + newUser.toString(),
                NotificationEvent.NEW_SYSTEM_MESSAGE);

        emailDeliveryService.sendUserNotification(newUser.getEmail(), emailTemplates.registrationLinkSubject(),
                emailTemplates.registrationLinkBody(newUser.getVerifyEmailToken().toString()),
                NotificationEvent.NEW_SYSTEM_MESSAGE,
                newUser.getId());

        return newUser;
    }

    public TokensResponse processRegistration(RegisterRequest registerRequest, String clientIp)
            throws RegistrationException, MessagingException, EmailAccessDeniedException, CaptchaException,
            UnsupportedEncodingException {

        captchaService.verifyRegistration(registerRequest.captchaToken(), registerRequest.website(), clientIp);
        UserModel user = addUser(registerRequest);

        return getTokenService.getNewTokens(user);
    }

}
