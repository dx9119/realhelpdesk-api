package com.ukhanov.realhelpdesk.domain.portal.service;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.ukhanov.realhelpdesk.domain.portal.model.PortalModel;
import com.ukhanov.realhelpdesk.domain.portal.repository.PortalRepository;
import com.ukhanov.realhelpdesk.feature.portalmanager.exception.PortalException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Тесты сервиса PortalDomainService")
class PortalDomainServiceTest {

    private static final Long OWNER_ID = 11L;
    private static final Long USER_ID = 22L;
    private static final Long PORTAL_ID = 42L;

    @Mock
    private PortalRepository mockPortalRepository;

    private PortalDomainService service;

    @BeforeEach
    void setUp() {
        service = new PortalDomainService(mockPortalRepository);
    }

    // ────────────────────────────────────────────────
    // getPortalsByOwnerId
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getPortalsByOwnerId → возвращает отсортированный список порталов владельца")
    void getPortalsByOwnerId_existingPortals_returnsSortedList() {

        // given
        PortalModel first = createPortal(100L, "Portal A");
        PortalModel second = createPortal(50L, "Portal B");
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(OWNER_ID)).thenReturn(List.of(first, second));

        // when
        List<PortalModel> result = service.getPortalsByOwnerId(OWNER_ID);

        // then
        assertThat(result).hasSize(2).extracting(PortalModel::getName).containsExactly("Portal A", "Portal B");
        verify(mockPortalRepository).findAllByOwnerIdOrderByCreatedAtDesc(OWNER_ID);
    }

    @Test
    @DisplayName("getPortalsByOwnerId → пустой список когда ничего не найдено")
    void getPortalsByOwnerId_noPortals_returnsEmptyList() {

        // given
        when(mockPortalRepository.findAllByOwnerIdOrderByCreatedAtDesc(OWNER_ID)).thenReturn(Collections.emptyList());

        // when
        List<PortalModel> result = service.getPortalsByOwnerId(OWNER_ID);

        // then
        assertThat(result).isEmpty();
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   "})
    @DisplayName("getPortalsByOwnerId → кидает NPE при null ownerId")
    void getPortalsByOwnerId_nullOwnerId_throwsNPE(String dummy) {

        // given / when / then
        assertThatThrownBy(() -> service.getPortalsByOwnerId(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ownerId не должен быть null");
    }

    // ────────────────────────────────────────────────
    // getPortalById
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("getPortalById → успешно возвращает найденный портал")
    void getPortalById_existingPortal_returnsIt() throws PortalException {

        // given
        PortalModel expected = createPortal(PORTAL_ID, "Test Portal");
        when(mockPortalRepository.findById(PORTAL_ID)).thenReturn(Optional.of(expected));

        // when
        PortalModel result = service.getPortalById(PORTAL_ID);

        // then
        assertThat(result).usingRecursiveComparison().isEqualTo(expected);
    }

    @Test
    @DisplayName("getPortalById → кидает PortalException если портал не найден")
    void getPortalById_missingPortal_throwsPortalException() {

        // given
        when(mockPortalRepository.findById(PORTAL_ID)).thenReturn(Optional.empty());

        // when / then
        assertThatThrownBy(() -> service.getPortalById(PORTAL_ID)).isInstanceOf(PortalException.class)
                .hasMessageContaining("Портал с ID " + PORTAL_ID + " не найден");
    }

    @Test
    @DisplayName("getPortalById → проверка на null portalId")
    void getPortalById_nullId_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.getPortalById(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("portalId must not be null");
    }

    // ────────────────────────────────────────────────
    // savePortal
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("savePortal → сохраняет и возвращает портал")
    void savePortal_validPortal_returnsSaved() {

        // given
        PortalModel toSave = createPortal(null, "New Portal");
        PortalModel saved = createPortal(777L, "New Portal");
        when(mockPortalRepository.save(any(PortalModel.class))).thenReturn(saved);

        // when
        PortalModel result = service.savePortal(toSave);

        // then
        assertThat(result).usingRecursiveComparison().isEqualTo(saved);
        verify(mockPortalRepository).save(toSave);
    }

    @Test
    @DisplayName("savePortal → кидает NPE при null")
    void savePortal_nullPortal_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.savePortal(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("portal не должен быть null");
    }

    // ────────────────────────────────────────────────
    // isPortalExistByName
    // ────────────────────────────────────────────────

    @ParameterizedTest(name = "Имя \"{0}\" → существует = {1}")
    @CsvSource({"'',          false", "' ',         false", "Portal One,  true", "Портал №2,   true"})
    @DisplayName("isPortalExistByName → пустые имена не существуют, занятые — существуют")
    void isPortalExistByName_variousNames_returnsRepositoryAnswer(String name, boolean exists) {

        // given
        when(mockPortalRepository.existsByOwnerIdAndNameAndIsDeletedFalse(OWNER_ID, name)).thenReturn(exists);

        // when
        boolean result = service.isPortalExistByName(OWNER_ID, name);

        // then
        assertThat(result).isEqualTo(exists);
    }

    @Test
    @DisplayName("isPortalExistByName → null имя → NPE")
    void isPortalExistByName_nullName_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.isPortalExistByName(OWNER_ID, null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("portalName не должен быть null");
    }

    // ────────────────────────────────────────────────
    // deletePortalById
    // ────────────────────────────────────────────────

    @Test
    @DisplayName("deletePortalById → вызывает удаление и возвращает true")
    void deletePortalById_existingPortal_delegatesToRepository() {

        // given
        // when
        boolean result = service.deletePortalById(PORTAL_ID);

        // then
        assertThat(result).isTrue();
        verify(mockPortalRepository).deleteById(PORTAL_ID);
    }

    @Test
    @DisplayName("deletePortalById → null id → NPE")
    void deletePortalById_nullId_throwsNPE() {

        // given / when / then
        assertThatThrownBy(() -> service.deletePortalById(null)).isInstanceOf(NullPointerException.class)
                .hasMessageContaining("portalId не должен быть null");
    }

    // ────────────────────────────────────────────────
    // Вспомогательные методы
    // ────────────────────────────────────────────────

    private static PortalModel createPortal(Long id, String name) {
        PortalModel portal = new PortalModel();
        portal.setId(id);
        portal.setName(name);
        portal.setCreatedAt(Instant.now());
        portal.setDeleted(false);
        portal.setPublic(false);
        return portal;
    }
}
