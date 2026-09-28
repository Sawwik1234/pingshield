package com.sawwik.pingshield;

import org.bukkit.Material;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты защиты замороженного игрока от среды: воздух под водой и удушье в песке/гравии.
 *
 * <p>Колонка блоков здесь — обычное множество «твёрдых» высот, потому что настоящая проверка
 * блоков на сервере сводится к одному вопросу: твёрдый блок или нет.</p>
 */
class EnvironmentGuardTest {

    /** Твёрдые блоки (песок, гравий, камень) на заданных высотах; всё остальное — воздух. */
    private static EnvironmentGuard.Column columnOf(int... solidYs) {
        Set<Integer> solid = new HashSet<>();
        for (int y : solidYs) {
            solid.add(y);
        }
        return y -> !solid.contains(y) && !solid.contains(y + 1);
    }

    // ---------------------------------------------------------------- удушье в блоке

    @Test
    @DisplayName("засыпанный песком игрок не считается свободным, обычный — считается")
    void buriedDetection() {
        EnvironmentGuard.Column open = columnOf();
        EnvironmentGuard.Column sand = columnOf(64, 65);
        assertFalse(EnvironmentGuard.isBuried(open, 64), "в воздухе не задыхаются");
        assertTrue(EnvironmentGuard.isBuried(sand, 64), "ноги и голова в песке");
    }

    @Test
    @DisplayName("голова в блоке — уже удушье (достаточно одного твёрдого блока)")
    void headInsideBlock() {
        EnvironmentGuard.Column gravelAboveHead = columnOf(65);
        assertTrue(EnvironmentGuard.isBuried(gravelAboveHead, 64), "блок на уровне головы — это удушье");
    }

    @Test
    @DisplayName("выпускает вверх: из-под песка на первый свободный уровень")
    void escapeUp() {
        EnvironmentGuard.Column sand = columnOf(64, 65, 66);
        assertEquals(67, EnvironmentGuard.findEscapeY(64, 8, sand), "подъём на 3 блока вверх — свободно");
    }

    @Test
    @DisplayName("не засыпан — никуда не двигаем")
    void noEscapeNeeded() {
        EnvironmentGuard.Column open = columnOf();
        assertEquals(64, EnvironmentGuard.findEscapeY(64, 8, open));
    }

    @Test
    @DisplayName("если в пределах лимита места нет — NOT_FOUND (защита удерживается)")
    void escapeNotFound() {
        EnvironmentGuard.Column solidToSky = columnOf(64, 65, 66, 67, 68, 69, 70, 71, 72, 73);
        assertEquals(EnvironmentGuard.NOT_FOUND, EnvironmentGuard.findEscapeY(64, 8, solidToSky));
    }

    @Test
    @DisplayName("лимит поиска соблюдается ровно (последний блок включительно)")
    void escapeLimitBoundary() {
        EnvironmentGuard.Column sand = columnOf(64, 65, 66, 67, 68, 69, 70, 71);
        assertEquals(72, EnvironmentGuard.findEscapeY(64, 8, sand), "8 блоков вверх — это Y=72");
        EnvironmentGuard.Column tooDeep = columnOf(64, 65, 66, 67, 68, 69, 70, 71, 72);
        assertEquals(EnvironmentGuard.NOT_FOUND, EnvironmentGuard.findEscapeY(64, 8, tooDeep),
                "на 9-м блоке уже не ищем");
    }

    @Test
    @DisplayName("«задыхаются» только в полном кубе: песок и гравий — да, ковёр и вода — нет")
    void suffocatingPredicate() {
        assertTrue(EnvironmentGuard.suffocating(true, true, "SAND"), "полный куб — песок, гравий, камень");
        assertTrue(EnvironmentGuard.suffocating(true, true, "GRAVEL"));
        assertTrue(EnvironmentGuard.suffocating(true, true, "GLASS"), "в стекле тоже задыхаются");
        assertFalse(EnvironmentGuard.suffocating(true, false, "CARPET"), "ковёр: стоят НА нём, а не внутри");
        assertFalse(EnvironmentGuard.suffocating(true, false, "OAK_SLAB"), "плита");
        assertFalse(EnvironmentGuard.suffocating(false, true, "WATER"), "вода");
    }

    @Test
    @DisplayName("«мягкие» полные кубы: порошковый снег и паутина игрока не засыпают")
    void softFullCubes() {
        // В порошковом снеге тонут, в паутине вязнут, на строительных лесах стоят — поднимать
        // игрока оттуда при разморозке было бы лишним прыжком (а Material#isSolid() в 26.x
        // доступен только на живом сервере, поэтому список задан именами).
        for (String name : new String[]{"POWDER_SNOW", "COBWEB", "SCAFFOLDING", "SNOW", "MUD", "HONEY_BLOCK"}) {
            assertFalse(EnvironmentGuard.suffocating(true, true, name), name + " — не удушье");
        }
        assertFalse(EnvironmentGuard.suffocating(true, true, null), "неизвестный материал — не рискуем поднимать");
    }

    // ---------------------------------------------------------------- воздух

    @Test
    @DisplayName("воздух доливается, пока игрок заморожен")
    void airRefill() {
        assertTrue(EnvironmentGuard.shouldRefillAir(true, 40, 300), "под водой воздух кончается — доливаем");
        assertFalse(EnvironmentGuard.shouldRefillAir(true, 300, 300), "уже полный");
        assertFalse(EnvironmentGuard.shouldRefillAir(false, 0, 300), "настройка выключена (старое поведение)");
        assertFalse(EnvironmentGuard.shouldRefillAir(true, 0, 0), "нулевой максимум (эффект/атрибут) не трогаем");
    }

    @Test
    @DisplayName("на разморозке воздух полный — иначе игрок всплывёт уже мёртвым")
    void airAfterRelease() {
        assertEquals(300, EnvironmentGuard.airAfterFreeze(true, 0, 300), "было 0 — отдаём полный запас");
        assertEquals(300, EnvironmentGuard.airAfterFreeze(true, 300, 300), "был полный — так и остаётся");
        assertEquals(0, EnvironmentGuard.airAfterFreeze(false, 0, 300), "настройка выключена — не вмешиваемся");
    }

    @Test
    @DisplayName("под водой с полным воздухом игрок не считается в опасности (удержание не продлевается)")
    void fullAirIsNotDanger() {
        // В isInDanger() есть ветка «воздух меньше половины и глаза в жидкости».
        // Пока защита держит воздух полным, эта ветка не срабатывает — и заморозка
        // сама себя не продлевает, держа игрока под водой.
        int maxAir = 300;
        int airWithGuard = EnvironmentGuard.airAfterFreeze(true, 20, maxAir);
        assertFalse(airWithGuard < maxAir * 0.5F, "ветка опасности по воздуху выключена");
    }
}
