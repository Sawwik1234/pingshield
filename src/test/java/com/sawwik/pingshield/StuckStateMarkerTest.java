package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Маркер «залипшего» состояния: игрок вышел замороженным, плагин не успел вернуть свойства —
 * при следующем входе маркер из данных игрока возвращает их.
 *
 * <p>Тесты чистые: ни одной registry-backed операции, поэтому работают без сервера
 * ({@code ./gradlew test}).</p>
 */
class StuckStateMarkerTest {

    @DisplayName("Снимок «как было до защиты» переживает запись и чтение без потерь")
    @Test
    void roundTrip() {
        StuckStateMarker original = StuckStateMarker.fromSnapshot(
                true, 0.2F, 0.1F, false, false, true, 17, 3.5F);

        StuckStateMarker restored = StuckStateMarker.decode(original.encode());

        assertNotNull(restored, "маркер должен разбираться");
        assertTrue(restored.invulnerable(), "неуязвимость до защиты");
        assertEquals(0.2F, restored.walkSpeed(), 0.0F, "скорость ходьбы");
        assertEquals(0.1F, restored.flySpeed(), 0.0F, "скорость полёта");
        assertFalse(restored.allowFlight(), "права полёта не было");
        assertFalse(restored.flying(), "в полёте не был");
        assertTrue(restored.collidable(), "коллизия была включена");
        assertEquals(17, restored.foodLevel(), "уровень еды");
        assertEquals(3.5F, restored.saturation(), 0.0F, "насыщение");
    }

    @DisplayName("Формат строки стабилен и читаем человеком (он лежит в данных игрока)")
    @Test
    void formatIsStable() {
        StuckStateMarker marker = StuckStateMarker.fromSnapshot(false, 0.2F, 0.1F, false, false, true, 20, 5.0F);

        assertEquals("v1|inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0", marker.encode());
        assertEquals("stuck-state", StuckStateMarker.PDC_KEY,
                "имя ключа менять нельзя: у игроков на серверах уже могут лежать маркеры");
    }

    @DisplayName("Полёт восстанавливается корректно: был в полёте и было право полёта")
    @Test
    void flyingRestored() {
        StuckStateMarker marker = StuckStateMarker.decode(
                StuckStateMarker.fromSnapshot(false, 0.1F, 0.05F, true, true, true, 20, 5.0F).encode());

        assertNotNull(marker);
        assertTrue(marker.allowFlight());
        assertTrue(marker.flying());
    }

    @DisplayName("Битый или чужой маркер не разбирается — лучше ничего не трогать, чем испортить состояние")
    @Test
    void brokenMarkersAreRejected() {
        assertNull(StuckStateMarker.decode(null), "пусто — нет маркера");
        assertNull(StuckStateMarker.decode(""), "пустая строка");
        assertNull(StuckStateMarker.decode("   "), "одни пробелы");
        assertNull(StuckStateMarker.decode("v1|inv=0|walk=0.2"), "обрезанная строка");
        assertNull(StuckStateMarker.decode("v2|inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0"),
                "маркер другой (будущей) версии");
        assertNull(StuckStateMarker.decode("inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0"),
                "нет версии формата");
        assertNull(StuckStateMarker.decode("v1|inv=2|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0"),
                "флаг не 0/1");
        assertNull(StuckStateMarker.decode("v1|inv=0|walk=NaN|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0"),
                "NaN в скорости");
        assertNull(StuckStateMarker.decode("v1|inv=0|walk=Infinity|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0"),
                "бесконечность в скорости");
        assertNull(StuckStateMarker.decode("v1|inv=0|walk=быстро|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0"),
                "не число");
        assertNull(StuckStateMarker.decode("v1|inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=99|sat=5.0"),
                "еда вне диапазона 0–20");
        assertNull(StuckStateMarker.decode("v1|inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|Food=20|sat=5.0"),
                "перепутано имя поля");
    }

    @DisplayName("Разделитель не ломается: лишние пробелы по краям допустимы, мусор внутри — нет")
    @Test
    void whitespaceTolerated() {
        StuckStateMarker marker = StuckStateMarker.decode(
                "  v1|inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0  ");

        assertNotNull(marker, "пробелы по краям — не повод терять состояние игрока");
        assertEquals(0.2F, marker.walkSpeed(), 0.0F);
    }

    @DisplayName("Отрицательные и предельные скорости сохраняются как есть (их мог задать другой плагин)")
    @Test
    void edgeSpeedsPreserved() {
        StuckStateMarker marker = StuckStateMarker.decode(
                StuckStateMarker.fromSnapshot(false, -1.0F, 1.0F, false, false, false, 0, 0.0F).encode());

        assertNotNull(marker);
        assertEquals(-1.0F, marker.walkSpeed(), 0.0F);
        assertEquals(1.0F, marker.flySpeed(), 0.0F);
        assertEquals(0, marker.foodLevel(), "голодный игрок тоже имеет право на восстановление");
        assertFalse(marker.collidable(), "коллизия, выключенная другим плагином, остаётся выключенной");
    }
}
