package com.ukhanov.realhelpdesk.core.security.localjwt.captcha.service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * Генератор эмодзи-заданий для эскалационного уровня: сетка из девяти глифов и одно из двух заданий — «выберите все
 * глифы категории» или «выберите только этот глиф». Списки категорий и ответ сервер не отдаёт (кроме видимых ячеек):
 * правильные индексы превращаются в HMAC в {@link CaptchaTokenService#answerHash}, маппинг глиф→категория живёт только
 * здесь.
 */
@Component
public class EmojiTaskFactory {

    private static final int GRID_SIZE = 9;
    private static final int CATEGORY_ANSWER_SIZE = 3;
    private static final int DISTRACTORS_SIZE = GRID_SIZE - CATEGORY_ANSWER_SIZE;
    private static final int GLYPH_DISTRACTORS_SIZE = GRID_SIZE - 1;

    private static final Map<String, List<String>> CATEGORIES = categories();

    private final Random random;

    public EmojiTaskFactory() {
        this(new SecureRandom());
    }

    EmojiTaskFactory(Random random) {
        this.random = random;
    }

    /** Новое задание: равновероятно категорийное или «только этот глиф». */
    public EmojiTask create() {
        if (random.nextBoolean()) {
            return categoryTask();
        }
        return glyphTask();
    }

    /**
     * Выбрать в сетке все глифы одной категории: правильных ячеек CATEGORY_ANSWER_SIZE, остальные — из чужих категорий.
     */
    private EmojiTask categoryTask() {
        String name = pick(new ArrayList<>(CATEGORIES.keySet()));
        List<String> category = CATEGORIES.get(name);
        List<String> correct = sample(category, CATEGORY_ANSWER_SIZE);
        List<String> others = otherGlyphs(Set.copyOf(correct));
        List<String> cells = new ArrayList<>(correct);
        cells.addAll(sample(others, DISTRACTORS_SIZE));
        Collections.shuffle(cells, random);
        Set<String> correctSet = Set.copyOf(correct);
        List<Integer> answer = indexesOf(cells, glyph -> correctSet.contains(glyph));
        String prompt = "Выберите все эмодзи категории «" + name + "»";
        return new EmojiTask(prompt, List.copyOf(cells), answer);
    }

    /** Выбрать в сетке ровно один глиф, указанный в задании: остальные восемь — из любых категорий, но не он сам. */
    private EmojiTask glyphTask() {
        String name = pick(new ArrayList<>(CATEGORIES.keySet()));
        String glyph = pick(CATEGORIES.get(name));
        List<String> cells = new ArrayList<>(List.of(glyph));
        cells.addAll(sample(otherGlyphs(Set.of(glyph)), GLYPH_DISTRACTORS_SIZE));
        Collections.shuffle(cells, random);
        String prompt = "Выберите только этот эмодзи: " + glyph;
        return new EmojiTask(prompt, List.copyOf(cells), List.of(cells.indexOf(glyph)));
    }

    /** Все глифы всех категорий, кроме уже занятых (используются как дистракторы). */
    private List<String> otherGlyphs(Set<String> excluded) {
        List<String> glyphs = new ArrayList<>();
        for (List<String> category : CATEGORIES.values()) {
            for (String glyph : category) {
                if (!excluded.contains(glyph)) {
                    glyphs.add(glyph);
                }
            }
        }
        return glyphs;
    }

    /** n разных случайных элементов списка (порядок не важен). */
    private List<String> sample(List<String> source, int n) {
        List<String> copy = new ArrayList<>(source);
        Collections.shuffle(copy, random);
        return new ArrayList<>(copy.subList(0, n));
    }

    private <T> T pick(List<T> source) {
        return source.get(random.nextInt(source.size()));
    }

    private List<Integer> indexesOf(List<String> cells, java.util.function.Predicate<String> matches) {
        List<Integer> indexes = new ArrayList<>();
        for (int i = 0; i < cells.size(); i++) {
            if (matches.test(cells.get(i))) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private static Map<String, List<String>> categories() {
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("Транспорт", List.of("🚗", "🚕", "🚌", "🚋", "✈️", "🚲", "🛵", "⛵", "🚇", "🚆", "🚁"));
        categories.put("Животные", List.of("🐶", "🐱", "🐭", "🐹", "🐰", "🦊", "🐻", "🐼", "🐨", "🐯", "🦁"));
        categories.put("Фрукты", List.of("🍎", "🍐", "🍊", "🍌", "🍉", "🍇", "🍓", "🍒", "🍑", "🥝", "🍍"));
        categories.put("Природа", List.of("🌳", "🌲", "🌵", "🌴", "🍁", "🍂", "🌻", "🌹", "🌷", "🌺", "🍀"));
        categories.put("Еда", List.of("🍕", "🍔", "🍟", "🌮", "🌯", "🍣", "🍜", "🍲", "🍝", "🥧", "🍿"));
        categories.put("Предметы", List.of("💡", "🔑", "🔒", "🎁", "📦", "📚", "✏️", "🎧", "🎮", "📱", "⌚"));
        return Collections.unmodifiableMap(categories);
    }

    /** Готовое эмодзи-задание: видимое клиенту (prompt и cells) и скрытый правильный ответ — индексы ячеек. */
    public record EmojiTask(String prompt, List<String> cells, List<Integer> answer) {
    }
}
