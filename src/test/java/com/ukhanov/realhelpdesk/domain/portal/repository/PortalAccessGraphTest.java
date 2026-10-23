package com.ukhanov.realhelpdesk.domain.portal.repository;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.ukhanov.realhelpdesk.core.security.user.model.UserModel;
import com.ukhanov.realhelpdesk.core.security.user.repository.UserRepository;
import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Состав участников портала — LAZY @ElementCollection при выключенном OSIV: сущность, загруженная вне транзакции,
 * отдаёт доступ только если коллекция прочитана тем же запросом (EntityGraph). Здесь фиксируется контракт
 * findWithAccessById, на котором держится getPortalById.
 */
@DataJpaTest(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
@DisplayName("Граф доступа портала (findWithAccessById)")
class PortalAccessGraphTest {

    @Autowired
    private PortalRepository portalRepository;

    @Autowired
    private UserRepository userRepository;

    @BeforeEach
    void clean() {
        portalRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Портал с несколькими участниками → одна запись, состав доступен без сессии")
    void findWithAccessById_multipleMembers_returnsSinglePortalWithAccess() {

        // given
        UserModel owner = saveUser("graph-owner@example.com");
        UserModel first = saveUser("graph-first@example.com");
        UserModel second = saveUser("graph-second@example.com");
        PortalModel portal = savePortal(owner, Set.of(first.getId(), second.getId()));

        // when
        Optional<PortalModel> loaded = portalRepository.findWithAccessById(portal.getId());

        // then
        assertThat(loaded).isPresent();
        assertThat(loaded.get().getAllowedUserIds()).containsExactlyInAnyOrder(first.getId(), second.getId());
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("Загрузка без графа → состав участников недоступен после закрытия транзакции")
    void findById_withoutGraph_throwsLazyInitializationOnAccess() {

        // given
        UserModel owner = saveUser("plain-owner@example.com");
        UserModel member = saveUser("plain-member@example.com");
        PortalModel portal = savePortal(owner, Set.of(member.getId()));

        // when
        Optional<PortalModel> loaded = portalRepository.findById(portal.getId());

        // then
        assertThat(loaded).isPresent();
        assertThatThrownBy(() -> loaded.get().getAllowedUserIds().contains(member.getId()))
                .isInstanceOf(LazyInitializationException.class);
    }

    private UserModel saveUser(String email) {
        UserModel user = new UserModel();
        user.setFirstName("Имя");
        user.setLastName("Фамилия");
        user.setEmail(email);
        user.setPasswordHash("hash");
        return userRepository.save(user);
    }

    private PortalModel savePortal(UserModel owner, Set<Long> allowedUserIds) {
        PortalModel portal = new PortalModel();
        portal.setOwner(owner);
        portal.setName("Граф " + owner.getEmail());
        portal.setAllowedUserIds(new HashSet<>(allowedUserIds));
        return portalRepository.save(portal);
    }
}
