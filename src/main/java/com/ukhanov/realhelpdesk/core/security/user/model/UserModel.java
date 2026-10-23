package com.ukhanov.realhelpdesk.core.security.user.model;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;

import com.ukhanov.realhelpdesk.core.security.localjwt.auth.tokens.model.RefreshTokenModel;

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

    private Long verifyEmailToken;

    private Long recoveryPasswdToken;

    private boolean isEmailVerified = false;

    @Column(nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    private UserRole userRole = UserRole.ROLE_USER;

    @Enumerated(EnumType.STRING)
    private UserStatus userStatus = UserStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    private UserPlatformSource userPlatformSource = UserPlatformSource.LOCAL;

    // Версия access-токенов: инкремент отзывает все ранее выданные access-токены
    @Column(nullable = false)
    private int tokenVersion = 0;

    // Связь с JWT Refresh-токенами: LAZY + батч, чтобы загрузка пользователя не
    // тянула токены (N+1 на каждый findById / findByEmail) и не разрасталась в e-мейл-фолдауте.
    // Каскад только PERSIST/MERGE: отзыв и очистка токенов идут через JwtRefreshTokenRepository
    // (@Modifying UPDATE / DELETE), каскадное удаление отсюда не используется.
    @OneToMany(mappedBy = "user", cascade = {CascadeType.PERSIST,
            CascadeType.MERGE}, fetch = FetchType.LAZY)
    @BatchSize(size = 50)
    private Set<RefreshTokenModel> jwtTokenRefresh = new HashSet<>();

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

    // Логи не должны раскрывать passwordHash/токены — поэтому ручной toString, а не сгенерированный
    @Override
    public String toString() {
        return "UserModel{" + "createdAt=" + createdAt + ", userRole=" + userRole + ", id=" + id + ", firstName='"
                + firstName + '\''
                + ", lastName='" + lastName + '\'' + ", middleName='" + middleName + '\'' + ", email='" + email + '\''
                + ", userStatus="
                + userStatus + '}';
    }

    /** Алиас для userPlatformSource: под этим именем поле знает AuthMapper (mapping target = "userExternalSource"). */
    public UserPlatformSource getUserExternalSource() {
        return userPlatformSource;
    }

    public void setUserExternalSource(UserPlatformSource source) {
        this.userPlatformSource = source;
    }

    public String getFullName() {
        return this.firstName + " " + this.lastName + " " + this.middleName;
    }

    // Отзывает все ранее выданные access-токены пользователя
    public void incrementTokenVersion() {
        this.tokenVersion++;
    }
}
