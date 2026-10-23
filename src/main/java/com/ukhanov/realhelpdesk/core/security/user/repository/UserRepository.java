package com.ukhanov.realhelpdesk.core.security.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;

@Repository
public interface UserRepository extends JpaRepository<UserModel, Long> {
    Optional<UserModel> findByEmail(String email);
    /**
     * Поиск без учёта регистра: email хранится как ввёл пользователь, а оператор-бутстрап задаёт адреса в своём
     * регистре.
     */
    Optional<UserModel> findByEmailIgnoreCase(String email);
    boolean existsByEmail(String email);
    Optional<UserDetailsProjection> findProjectedById(Long id);

    /** Батч-загрузка имён/контактов по списку ID: один запрос вместо N поисков по одному. */
    List<UserDetailsProjection> findProjectedByIdIn(Collection<Long> ids);

    Optional<UserModel> findByRecoveryPasswdToken(Long recoveryPasswdToken);
}
