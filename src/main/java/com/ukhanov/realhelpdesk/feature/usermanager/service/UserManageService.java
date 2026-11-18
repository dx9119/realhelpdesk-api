package com.ukhanov.realhelpdesk.feature.usermanager.service;

import java.util.Objects;

import jakarta.transaction.Transactional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import com.ukhanov.realhelpdesk.core.http.EntityTag;
import com.ukhanov.realhelpdesk.core.security.user.CurrentUserProvider;
import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.service.UserDomainService;
import com.ukhanov.realhelpdesk.feature.usermanager.dto.PatchUserInfoRequest;
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

    private final CurrentUserProvider currentUserProvider;
    private final UserDomainService userDomainService;
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
}
