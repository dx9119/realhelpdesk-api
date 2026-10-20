package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service.EmojiTaskFactory.EmojiTask;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Генератор эмодзи-заданий: сетка 3×3, без повторов, корректный ответ")
class EmojiTaskFactoryTest {

    private final EmojiTaskFactory factory = new EmojiTaskFactory(new Random(42));

    @Test
    @DisplayName("Задание: сетка из 9 разных глифов, prompt не пуст, индексы ответа в пределах сетки")
    void create_repeatedGeneration_keepsGridInvariants() {

        // given / when / then
        for (int i = 0; i < 200; i++) {
            EmojiTask task = factory.create();

            assertThat(task.prompt()).isNotBlank();
            assertThat(task.cells()).hasSize(9);
            assertThat(task.cells()).doesNotHaveDuplicates();
            assertThat(task.answer()).isNotEmpty();
            assertThat(task.answer()).allMatch(index -> index >= 0 && index < 9);
            assertThat(task.answer()).doesNotHaveDuplicates();
        }
    }

    @Test
    @DisplayName("Оба вида заданий встречаются: категорийное (несколько ячеек) и «только этот глиф» (одна)")
    void create_twoHundredTasks_producesBothTaskKinds() {

        // given
        int multiAnswer = 0;
        int singleAnswer = 0;

        // when
        for (int i = 0; i < 200; i++) {
            EmojiTask task = factory.create();
            if (task.answer().size() == 1) {
                singleAnswer++;
            } else if (task.answer().size() > 1) {
                multiAnswer++;
            }
        }

        // then
        assertThat(multiAnswer).as("категорийные задания встречаются").isPositive();
        assertThat(singleAnswer).as("задания «только этот глиф» встречаются").isPositive();
    }

    @Test
    @DisplayName("Задание с одним выбранным глифом: prompt повторяет ровно один глиф из сетки, он же и есть ответ")
    void create_glyphTask_promptMatchesSingleCell() {

        // given / when / then
        for (int i = 0; i < 200; i++) {
            EmojiTask task = factory.create();
            if (task.answer().size() != 1) {
                continue;
            }
            String glyph = task.cells().get(task.answer().getFirst());

            assertThat(task.prompt()).contains(glyph);
            assertThat(task.cells().stream().filter(glyph::equals).count()).as("глиф ровно один в сетке").isEqualTo(1);
        }
    }

    @Test
    @DisplayName("Детерминированность: одинаковый Random даёт одинаковые задания")
    void create_seededRandom_reproducible() {

        // given
        // when
        EmojiTask first = new EmojiTaskFactory(new Random(7)).create();
        EmojiTask second = new EmojiTaskFactory(new Random(7)).create();

        // then
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("Все глифы сетки уникальны в пределах задания")
    void create_hundredTasks_keepsCellsUnique() {

        // given / when / then
        for (int i = 0; i < 100; i++) {
            EmojiTask task = factory.create();
            Set<String> unique = new HashSet<>(task.cells());

            assertThat(unique).hasSize(task.cells().size());
            assertThat(List.copyOf(task.cells())).hasSize(9);
        }
    }
}
