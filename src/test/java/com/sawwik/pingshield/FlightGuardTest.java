package com.sawwik.pingshield;

import org.bukkit.event.player.PlayerKickEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Тесты политики удержания игрока в воздухе (кик «Flying is not enabled on this server»).
 *
 * <p>Условие сервера скопировано из декомпилированного
 * {@code net.minecraft.server.network.ServerGamePacketListenerImpl} (Folia 26.1.2 build 8) —
 * того самого класса, что лежит в jar, который стоит на сервере.</p>
 */
class FlightGuardTest {

    // ---------------------------------------------------------------- порог сервера

    @Test
    @DisplayName("порог зависания для игрока — ровно 80 тиков (4 секунды)")
    void playerFloatingLimit() {
        // gravity игрока = 0.08 → modifier = 1.0 → ceil(80 * 1.0) = 80
        assertEquals(80, FlightGuard.maxFloatingTicks(0.08D));
    }

    @Test
    @DisplayName("формула сервера: ceil(80 * max(0.08/gravity, 1)), нулевая гравитация — без кика")
    void floatingLimitFormula() {
        assertEquals(80, FlightGuard.maxFloatingTicks(0.16D), "меньшая гравитация не сокращает порог ниже 80");
        assertEquals(160, FlightGuard.maxFloatingTicks(0.04D), "вдвое меньшая гравитация — вдвое больше времени");
        assertEquals(Integer.MAX_VALUE, FlightGuard.maxFloatingTicks(0.0D), "нулевая гравитация — проверка отключена");
    }

    // ---------------------------------------------------------------- причины кика

    @Test
    @DisplayName("страховка срабатывает на причинах зависания и не трогает другие кики")
    void kickCauses() {
        assertTrue(FlightGuard.isFlyingKick(PlayerKickEvent.Cause.FLYING_PLAYER));
        assertTrue(FlightGuard.isFlyingKick(PlayerKickEvent.Cause.FLYING_VEHICLE));
        assertFalse(FlightGuard.isFlyingKick(PlayerKickEvent.Cause.KICK_COMMAND), "ручной /kick не перехватываем");
        assertFalse(FlightGuard.isFlyingKick(PlayerKickEvent.Cause.PLUGIN));
        assertFalse(FlightGuard.isFlyingKick(PlayerKickEvent.Cause.TIMEOUT));
    }

    // ---------------------------------------------------------------- политика удержания

    @Test
    @DisplayName("mayfly включается только в воздухе и только если полёт не был разрешён")
    void needsAllowFlight() {
        assertTrue(FlightGuard.needsAllowFlight(true, false, true, false), "игрок в воздухе без права полёта");
        assertFalse(FlightGuard.needsAllowFlight(true, false, false, false), "игрок на земле — риска нет");
        assertFalse(FlightGuard.needsAllowFlight(true, false, true, true), "полёт уже разрешён — не трогаем");
        assertFalse(FlightGuard.needsAllowFlight(true, true, true, false), "наблюдателя не трогаем");
        assertFalse(FlightGuard.needsAllowFlight(false, false, true, false), "страховка выключена в конфиге");
    }

    @Test
    @DisplayName("элитры при заморозке не сворачиваются (кроме режима FLY)")
    void glidingPolicy() {
        assertFalse(FlightGuard.shouldClearGliding(true, false, true),
                "keep-gliding=true: планирующий игрок остаётся в глайде — и не попадает под проверку зависания");
        assertTrue(FlightGuard.shouldClearGliding(true, true, true),
                "режим FLY: игрока переводят в полёт, глайд не нужен");
        assertTrue(FlightGuard.shouldClearGliding(false, false, true),
                "keep-gliding=false: старое поведение (именно оно и приводило к кику)");
        assertFalse(FlightGuard.shouldClearGliding(true, false, false), "игрок не в глайде — нечего трогать");
    }

    // ---------------------------------------------------------------- модель условия сервера

    /**
     * Модель условия, которое решает судьбу игрока (декомпилированный
     * {@code ServerGamePacketListenerImpl}: присваивание {@code clientIsFloating} в
     * {@code handleMovePlayer} и проверка в {@code tickPlayer}). Возвращает true, если сервер
     * считает игрока «зависшим» — а значит, через {@code getMaximumFlyingTicks} = 80 тиков (4 с)
     * отключит его с причиной FLYING_PLAYER.
     *
     * <p>Модель нужна как документация: видно, какая именно ветка снимает кик и почему
     * сворачивание элитр его, наоборот, включает.</p>
     */
    private static boolean serverCountsAsFloating(boolean hoveringInAir, boolean standsOnBlock, boolean spectator,
                                                  boolean serverAllowsFlight, boolean mayfly,
                                                  boolean levitation, boolean fallFlying, boolean autoSpinAttack,
                                                  boolean blocksAround) {
        return hoveringInAir && !standsOnBlock && !spectator && !serverAllowsFlight && !mayfly
                && !levitation && !fallFlying && !autoSpinAttack && !blocksAround;
    }

    @Test
    @DisplayName("падение: было к кику через 4 с, с mayfly — нет")
    void fallingPlayerKickModel() {
        boolean before = serverCountsAsFloating(true, false, false, false, false, false, false, false, false);
        boolean after = serverCountsAsFloating(true, false, false, false, true, false, false, false, false);
        assertTrue(before, "замороженный в падении игрок: сервер считает его зависшим");
        assertFalse(after, "mayfly (Player#setAllowFlight) снимает условие !mayfly — кика нет");
    }

    @Test
    @DisplayName("элитры: сворачивать глайд — значит самому включить проверку зависания")
    void elytraKickModel() {
        boolean glidingKept = serverCountsAsFloating(true, false, false, false, false, false, true, false, false);
        boolean glidingCleared = serverCountsAsFloating(true, false, false, false, false, false, false, false, false);
        assertFalse(glidingKept, "пока isFallFlying=true, условие !isFallFlying ложно — проверки нет");
        assertTrue(glidingCleared, "после setGliding(false) игрок в воздухе становится «зависшим»");
    }

    @Test
    @DisplayName("обычные игроки вне заморозки моделью не затрагиваются")
    void untouchedCases() {
        assertFalse(serverCountsAsFloating(false, true, false, false, false, false, false, false, false),
                "стоит на блоке — не зависает");
        assertFalse(serverCountsAsFloating(true, false, false, true, false, false, false, false, false),
                "на сервере включён allow-flight — проверки нет");
        assertFalse(serverCountsAsFloating(true, false, true, false, false, false, false, false, false),
                "наблюдатель");
    }
}
