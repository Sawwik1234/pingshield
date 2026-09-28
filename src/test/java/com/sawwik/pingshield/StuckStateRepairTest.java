package com.sawwik.pingshield;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Порядок восстановления состояния: маркер о заморозке удаляется <b>только после</b> успешного
 * применения. Если применение упало — расписка (единственная копия «как было») обязана остаться.
 */
class StuckStateRepairTest {

    private static final String VALID =
            StuckStateMarker.fromSnapshot(true, 0.2F, 0.1F, false, false, true, 20, 5.0F).encode();

    @DisplayName("Расписки нет — ничего не делаем и маркер не трогаем")
    @Test
    void noMarker() {
        for (String raw : new String[]{null, "", "   "}) {
            StuckStateRepair.Result result = StuckStateRepair.run(raw, marker -> {
                throw new AssertionError("применять нечего — applier не должен вызываться");
            });

            assertEquals(StuckStateRepair.Outcome.NO_MARKER, result.outcome());
            assertFalse(result.mayClearMarker(), "удалять нечего");
            assertFalse(result.mustKeepMarker());
            assertNull(result.marker());
        }
    }

    @DisplayName("Битый маркер: удаляем (он бесполезен), состояние не трогаем")
    @Test
    void brokenMarkerIsCleared() {
        StuckStateRepair.Result result = StuckStateRepair.run("v1|мусор|сюда|попал",
                marker -> {
                    throw new AssertionError("битый маркер применять нельзя");
                });

        assertEquals(StuckStateRepair.Outcome.BROKEN, result.outcome());
        assertTrue(result.mayClearMarker(), "битый маркер можно убрать, чтобы не мешал");
        assertNull(result.marker(), "разобрать не удалось");
        assertNull(result.error(), "это не ошибка применения");
    }

    @DisplayName("Успешное применение: значения доходят целиком, маркер можно удалять")
    @Test
    void applied() {
        List<StuckStateMarker> seen = new ArrayList<>();

        StuckStateRepair.Result result = StuckStateRepair.run(VALID, seen::add);

        assertEquals(StuckStateRepair.Outcome.APPLIED, result.outcome());
        assertTrue(result.mayClearMarker(), "состояние применено — маркер больше не нужен");
        assertFalse(result.mustKeepMarker());
        assertEquals(1, seen.size(), "применяем ровно один раз");
        StuckStateMarker marker = seen.get(0);
        assertTrue(marker.invulnerable());
        assertEquals(0.2F, marker.walkSpeed(), 0.0F);
        assertEquals(0.1F, marker.flySpeed(), 0.0F);
        assertEquals(20, marker.foodLevel());
        assertSame(marker, result.marker(), "тот же маркер, что применён");
    }

    @DisplayName("Провал применения: маркер ОСТАЁТСЯ — иначе состояние нечем восстановить")
    @Test
    void failedKeepsMarker() {
        IllegalStateException broken = new IllegalStateException("чужой плагин упал в обработчике события");

        StuckStateRepair.Result result = StuckStateRepair.run(VALID, marker -> {
            throw broken;
        });

        assertEquals(StuckStateRepair.Outcome.FAILED, result.outcome());
        assertTrue(result.mustKeepMarker(), "это главное правило: без маркера состояние потеряно");
        assertFalse(result.mayClearMarker(), "удалять нельзя ни при каких условиях");
        assertSame(broken, result.error(), "ошибка сохранена для лога");
        assertNotNull(result.marker(), "маркер разобран и известен");
    }

    @DisplayName("Падение ошибкой (Error), а не исключением, тоже не теряет состояние")
    @Test
    void failedOnError() {
        StuckStateRepair.Result result = StuckStateRepair.run(VALID, marker -> {
            throw new OutOfMemoryError("условный отказ JVM");
        });

        assertEquals(StuckStateRepair.Outcome.FAILED, result.outcome());
        assertTrue(result.mustKeepMarker());
        assertNotNull(result.error());
    }

    @DisplayName("Частичный отказ: если применение упало на середине, маркер всё равно остаётся")
    @Test
    void partialApplicationStillKeepsMarker() {
        AtomicInteger steps = new AtomicInteger();
        StuckStateRepair.Result result = StuckStateRepair.run(VALID, marker -> {
            steps.incrementAndGet();                       // первое свойство применили
            if (steps.get() == 1) {
                throw new IllegalArgumentException("второе свойство не принято");
            }
        });

        assertEquals(StuckStateRepair.Outcome.FAILED, result.outcome());
        assertTrue(result.mustKeepMarker(),
                "именно этот случай портил состояние: маркер удалялся до применения");
        assertEquals(1, steps.get(), "попытка была одна — без повторов внутри одного вызова");
    }
}
