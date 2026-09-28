package com.sawwik.pingshield;

/**
 * Все права PingShield в одном месте.
 *
 * <p>Схема построена так, чтобы с ней было удобно работать в LuckPerms:</p>
 * <ul>
 *   <li>{@link #ADMIN} — «зонтик»: даёт доступ ко всем подкомандам. Раздавайте его группе staff.</li>
 *   <li>{@link #command(String)} — отдельные права на каждую подкоманду, если нужно разделить
 *       обязанности (например, модераторы видят {@code status}/{@code check}, но не {@code reload}).</li>
 *   <li>{@link #THRESHOLD_PREFIX} + число — персональный порог входа: {@code pingshield.threshold.4500}.
 *       Специально сделано правом, а не настройкой в конфиге: LuckPerms считает права с контекстами,
 *       поэтому один и тот же игрок может иметь разные пороги в разных мирах/на разных серверах сети.</li>
 * </ul>
 */
public final class Permissions {

    /** Все команды /pingshield. */
    public static final String ADMIN = "pingshield.admin";

    /** Игрок никогда не попадает под защиту (обход). */
    public static final String BYPASS = "pingshield.bypass";

    /** Уведомления о входах других игроков в защиту. */
    public static final String NOTIFY = "pingshield.notify";

    /** Видеть только свои уведомления (личное сообщение при входе в защиту дублируется в чат). */
    public static final String NOTIFY_SELF = "pingshield.notify.self";

    /** Префикс права-порога: {@code pingshield.threshold.<миллисекунды>}. */
    public static final String THRESHOLD_PREFIX = "pingshield.threshold.";

    /** Доступ к интеграции CoreProtect (встроенный поиск) — можно выдать отдельно. */
    public static final String COREPROTECT = "pingshield.coreprotect";

    /** Право на конкретную подкоманду: {@code pingshield.command.<имя>}. */
    public static String command(String subcommand) {
        return "pingshield.command." + subcommand.toLowerCase(java.util.Locale.ROOT);
    }

    private Permissions() {
    }
}
