package com.ukhanov.realhelpdesk.core.security.user.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "users")
@Getter
@Setter
public class UserModel {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private Long externalId = 0L; // ID пользователя от внешних интеграций (телеграмм бот, например).

    @Column(nullable = false)
    private String firstName;

    @Column(nullable = false)
    private String lastName;

    @Column(nullable = true)
    private String middleName; // Отчество пользователя

    @Column(nullable = true, length = 5000)
    String additionalInfo;

    @Column(unique = true, nullable = false)
    private String email;

    // Код подтверждения email: выдаётся при запросе кода (/api/v1/email/codes),
    // сверяется в /api/v1/email/confirmations/{token}. К паролям и токенам доступа отношения не имеет.
    private Long verifyEmailToken;

    private boolean isEmailVerified = false;

    @Enumerated(EnumType.STRING)
    private UserRole userRole = UserRole.ROLE_USER;

    @Enumerated(EnumType.STRING)
    private UserStatus userStatus = UserStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    private UserPlatformSource userPlatformSource = UserPlatformSource.KEYCLOAK;

    // createdAt выставляет только @PrePersist: сеттера снаружи нет (см. CreatedAtPrePersistTest)
    @Column(nullable = false, updatable = false)
    @Setter(AccessLevel.NONE)
    private Instant createdAt;

    // Лимиты
    private Integer portalCuntLimit = 2;
    private Integer portalSharedUsersCountLimit = 2;

    @PrePersist
    public void setCreatedAt() {
        if (this.createdAt == null) {
            this.createdAt = Instant.now();
        }
    }

    @Version
    private Integer version;

    // Логи не должны раскрывать чувствительные поля — поэтому ручной toString, а не сгенерированный
    @Override
    public String toString() {
        return "UserModel{" + "createdAt=" + createdAt + ", userRole=" + userRole + ", id=" + id + ", firstName='"
                + firstName + '\''
                + ", lastName='" + lastName + '\'' + ", middleName='" + middleName + '\'' + ", email='" + email + '\''
                + ", userStatus="
                + userStatus + '}';
    }

    public String getFullName() {
        return this.firstName + " " + this.lastName + " " + this.middleName;
    }
}
