package com.sawwik.pingshield;

import org.bukkit.event.player.PlayerKickEvent;

/**
 * Страховка от серверного кика «Flying is not enabled on this server».
 *
 * <p><b>В чём была проблема.</b> Folia (как и Paper/Spigot) каждый тик проверяет, не «висит» ли
 * игрок в воздухе без права полёта — {@code ServerGamePacketListenerImpl#tickPlayer()}:</p>
 *
 * <pre>
 * clientIsFloating = oyDist &gt;= -0.03125 &amp;&amp; !playerStandsOnSomething &amp;&amp; !isSpectator()
 *         &amp;&amp; !server.allowFlight() &amp;&amp; !getAbilities().mayfly &amp;&amp; !hasEffect(LEVITATION)
 *         &amp;&amp; !isFallFlying &amp;&amp; !isAutoSpinAttack &amp;&amp; noBlocksAround(player);
 * if (clientIsFloating &amp;&amp; ++aboveGroundTickCount &gt; getMaximumFlyingTicks(player)) kick(FLYING_PLAYER);
 * </pre>
 *
 * <p>Признак {@code clientIsFloating} пересчитывается <b>только</b> когда сервер доходит до конца
 * обработки пакета движения. Отменённое событие {@code PlayerMoveEvent} (наша заморозка) выходит
 * из метода раньше — флаг остаётся «залипшим» на последнем значении, а счётчик тиков продолжает
 * расти: {@code getMaximumFlyingTicks} для игрока = {@code ceil(80 * max(0.08/gravity, 1))} =
 * <b>80 тиков = 4 секунды</b>. Дополнительно наша же заморозка сворачивала элитры
 * ({@code setGliding(false)}), то есть сама убирала единственное условие {@code !isFallFlying},
 * которое спасало летящего на элитрах игрока. Итог: любого замороженного в воздухе — падающего
 * или планирующего — сервер кикал через 4 секунды.</p>
 *
 * <p><b>Как лечим (только публичный API, без NMS):</b></p>
 * <ol>
 *   <li>пока игрок заморожен и находится в воздухе — держим {@code mayfly}
 *       ({@code Player#setAllowFlight(true)}): это та самая ветка {@code !mayfly} условия.
 *       Полёт игроку это не выдаёт: движение всё равно отменяется, а смену режима полёта
 *       (двойной пробел) слушатель блокирует. На разморозке значение возвращается как было;</li>
 *   <li>не сворачиваем элитры ({@code freeze.keep-gliding: true} по умолчанию) — глайд остаётся
 *       как был, и после снятия защиты игрок продолжает планировать, а не падает;</li>
 *   <li>последняя линия обороны: отменяем сам {@code PlayerKickEvent} с причиной FLYING_PLAYER /
 *       FLYING_VEHICLE, пока игрок под защитой. Сервер уважает отмену —
 *       {@code ServerCommonPacketListenerImpl#disconnect(...)} проверяет {@code event.isCancelled()}
 *       и просто выходит, игрок остаётся на сервере.</li>
 * </ol>
 */
public final class FlightGuard {

    private FlightGuard() {
    }

    /**
     * Сколько тиков «зависания» терпит сервер. Формула скопирована из
     * {@code ServerGamePacketListenerImpl#getMaximumFlyingTicks} (Folia 26.1.2, build 8):
     * {@code ceil(80 * max(0.08 / gravity, 1))}. Гравитация игрока 0.08 → ровно 80 тиков (4 с);
     * при нулевой гравитации игрока не кикают вообще ({@code Integer.MAX_VALUE}).
     */
    public static int maxFloatingTicks(double gravity) {
        if (gravity < 1.0E-5) {
            return Integer.MAX_VALUE;
        }
        double modifier = 0.08 / gravity;
        return (int) Math.ceil(80.0 * Math.max(modifier, 1.0));
    }

    /** Причина кика, от которой страхует режим защиты. */
    public static boolean isFlyingKick(PlayerKickEvent.Cause cause) {
        return cause == PlayerKickEvent.Cause.FLYING_PLAYER
                || cause == PlayerKickEvent.Cause.FLYING_VEHICLE;
    }

    /**
     * Нужно ли держать mayfly, чтобы серверная проверка зависания не досчитала до кика.
     *
     * @param preventFlyingKick настройка {@code freeze.prevent-flying-kick}
     * @param spectator         игрок в режиме наблюдателя: у него проверка отключена веткой {@code isSpectator}
     * @param airborneOrGliding игрок не касается земли (или ещё в глайде) — только для таких и есть риск
     * @param alreadyAllowFlight полёт уже разрешён (креатив, /fly, право на полёт) — ничего не меняем
     */
    public static boolean needsAllowFlight(boolean preventFlyingKick, boolean spectator,
                                           boolean airborneOrGliding, boolean alreadyAllowFlight) {
        return preventFlyingKick && !spectator && airborneOrGliding && !alreadyAllowFlight;
    }

    /**
     * Нужно ли свернуть элитры при заморозке.
     *
     * <p>Сворачивать нельзя: игрок потеряет глайд и после снятия защиты начнёт падать, а сервер
     * начнёт считать его «висящим» (условие {@code !isFallFlying} перестаёт спасать).
     * Исключение — режим {@code FLY}, где игрока намеренно переводят в полёт.</p>
     */
    public static boolean shouldClearGliding(boolean keepGliding, boolean freezeModeUsesFlight, boolean gliding) {
        return gliding && (!keepGliding || freezeModeUsesFlight);
    }
}
