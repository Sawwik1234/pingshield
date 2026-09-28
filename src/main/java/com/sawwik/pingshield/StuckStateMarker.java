package com.sawwik.pingshield;

/**
 * Снимок «как было до защиты», пригодный для хранения в {@code PersistentDataContainer}
 * игрока (без NMS и без рефлексии).
 *
 * <h2>Зачем это нужно</h2>
 * <p>Заморозка меняет свойства <b>сущности игрока</b>: флаг неуязвимости, скорости ходьбы и полёта,
 * право полёта, коллизию, еду. Всё это сервер сохраняет в {@code playerdata} игрока. Если игрок
 * выйдет, его выкинет или сервер упадёт <b>до</b> снятия защиты, плагин не успеет вернуть прежние
 * значения — и игрок вернётся «залипшим»: не может ходить (walk speed 0) и/или бессмертный
 * (invulnerable). Память плагина при перезапуске пуста, поэтому единственное место, где такая
 * «расписка» выживает, — данные самого игрока.</p>
 *
 * <h2>Что лежит внутри</h2>
 * <p>Формат — одна строка, читаемая человеком (её видно в data-файле игрока, если открыть NBT):</p>
 * <pre>{@code v1|inv=0|walk=0.2|fly=0.1|af=0|fl=0|col=1|food=20|sat=5.0}</pre>
 * <p>Версия формата в начале позволяет менять состав полей, не ломая уже записанные маркеры:
 * маркер неизвестной версии или битый разбор возвращает {@code null}, и плагин не гадает,
 * а пишет предупреждение в лог (лучше ничего не трогать, чем испортить состояние игрока).</p>
 *
 * <p>Класс намеренно не зависит от Bukkit: его целиком проверяют unit-тесты без запуска сервера
 * (см. {@code StuckStateMarkerTest}).</p>
 */
public final class StuckStateMarker {

    /** Имя ключа в PersistentDataContainer (пространство имён подставляет плагин). */
    public static final String PDC_KEY = "stuck-state";

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    private static final int FIELDS = 9; // v1 + 8 значений

    private final boolean invulnerable;
    private final float walkSpeed;
    private final float flySpeed;
    private final boolean allowFlight;
    private final boolean flying;
    private final boolean collidable;
    private final int foodLevel;
    private final float saturation;

    public StuckStateMarker(boolean invulnerable, float walkSpeed, float flySpeed, boolean allowFlight,
                            boolean flying, boolean collidable, int foodLevel, float saturation) {
        this.invulnerable = invulnerable;
        this.walkSpeed = walkSpeed;
        this.flySpeed = flySpeed;
        this.allowFlight = allowFlight;
        this.flying = flying;
        this.collidable = collidable;
        this.foodLevel = foodLevel;
        this.saturation = saturation;
    }

    /** Собирает маркер из снимка защиты (значения, которые были у игрока до заморозки). */
    public static StuckStateMarker fromSnapshot(boolean hadInvulnerable, float prevWalkSpeed, float prevFlySpeed,
                                                boolean hadAllowFlight, boolean hadFlying, boolean hadCollidable,
                                                int hadFoodLevel, float hadSaturation) {
        return new StuckStateMarker(hadInvulnerable, prevWalkSpeed, prevFlySpeed, hadAllowFlight, hadFlying,
                hadCollidable, hadFoodLevel, hadSaturation);
    }

    public boolean invulnerable() {
        return invulnerable;
    }

    public float walkSpeed() {
        return walkSpeed;
    }

    public float flySpeed() {
        return flySpeed;
    }

    public boolean allowFlight() {
        return allowFlight;
    }

    public boolean flying() {
        return flying;
    }

    public boolean collidable() {
        return collidable;
    }

    public int foodLevel() {
        return foodLevel;
    }

    public float saturation() {
        return saturation;
    }

    /** Строка для PersistentDataContainer. Float печатаем через {@link Float#toString(float)}: без локалей. */
    public String encode() {
        return VERSION
                + SEPARATOR + "inv=" + (invulnerable ? 1 : 0)
                + SEPARATOR + "walk=" + walkSpeed
                + SEPARATOR + "fly=" + flySpeed
                + SEPARATOR + "af=" + (allowFlight ? 1 : 0)
                + SEPARATOR + "fl=" + (flying ? 1 : 0)
                + SEPARATOR + "col=" + (collidable ? 1 : 0)
                + SEPARATOR + "food=" + foodLevel
                + SEPARATOR + "sat=" + saturation;
    }

    /**
     * Разбирает строку. Возвращает {@code null}, если строка пустая, битая, обрезанная или
     * неизвестной версии — вызывающий код в этом случае только предупреждает и чистит маркер.
     */
    public static StuckStateMarker decode(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split("\\" + SEPARATOR, -1);
        if (parts.length != FIELDS || !VERSION.equals(parts[0])) {
            return null;
        }
        try {
            boolean invulnerable = parseFlag(parts[1], "inv=");
            float walkSpeed = parseFloat(parts[2], "walk=");
            float flySpeed = parseFloat(parts[3], "fly=");
            boolean allowFlight = parseFlag(parts[4], "af=");
            boolean flying = parseFlag(parts[5], "fl=");
            boolean collidable = parseFlag(parts[6], "col=");
            int foodLevel = parseFood(parts[7]);
            float saturation = parseFloat(parts[8], "sat=");
            return new StuckStateMarker(invulnerable, walkSpeed, flySpeed, allowFlight, flying, collidable,
                    foodLevel, saturation);
        } catch (IllegalArgumentException broken) {
            return null;
        }
    }

    private static boolean parseFlag(String part, String prefix) {
        String value = requirePrefix(part, prefix);
        if ("1".equals(value)) {
            return true;
        }
        if ("0".equals(value)) {
            return false;
        }
        throw new IllegalArgumentException(prefix + value);
    }

    private static float parseFloat(String part, String prefix) {
        float value = Float.parseFloat(requirePrefix(part, prefix));
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(prefix + value);
        }
        return value;
    }

    private static int parseFood(String part) {
        int value = Integer.parseInt(requirePrefix(part, "food="));
        if (value < 0 || value > 20) {
            throw new IllegalArgumentException("food=" + value);
        }
        return value;
    }

    private static String requirePrefix(String part, String prefix) {
        if (!part.startsWith(prefix)) {
            throw new IllegalArgumentException(prefix + " != " + part);
        }
        return part.substring(prefix.length());
    }

    @Override
    public String toString() {
        return encode();
    }
}
