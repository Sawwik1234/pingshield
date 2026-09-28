package com.sawwik.pingshield;

import net.kyori.adventure.bossbar.BossBar;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Состояние одного защищённого игрока.
 *
 * <p>Объект живёт в {@link java.util.concurrent.ConcurrentHashMap}, и к нему обращаются
 * разные регионы Folia, поэтому изменяемые поля — {@code volatile}, а флаги состояния —
 * атомарные. Тяжёлые пололя (BossBar) трогаем только в регионе игрока.</p>
 */
public final class Protection {

    /** Почему включена защита. */
    public enum Reason {
        /** Автоматически по пингу. */
        AUTO_PING,
        /** Вручную администратором: автоправила не снимают такую защиту. */
        MANUAL
    }

    private final UUID uuid;
    private final String name;
    private final Reason reason;
    private final long startedAt;
    private final int thresholdUsed;

    /** Точка удержания (заморозки). */
    private volatile Location anchor;
    private volatile int lastPing;
    private volatile int peakPing;

    private volatile long lastActionbarAt;
    private volatile long lastEnforcedAt;
    private volatile long lastTeleportBackAt;
    private volatile long lastBossBarUpdateAt;
    private volatile long dangerSince = -1L;
    private volatile long lastDangerNoticeAt;

    private final AtomicBoolean released = new AtomicBoolean(false);

    // Снимок состояния игрока до заморозки — чтобы вернуть ровно как было
    private boolean hadAllowFlight;
    private boolean hadFlying;
    private boolean hadCollidable = true;
    private boolean hadInvulnerable;
    private int hadFoodLevel = 20;
    private float hadSaturation = 5.0F;
    private int hadFireTicks;
    private float prevFlySpeed = 0.1F;
    private float prevWalkSpeed = 0.2F;
    private boolean snapshotTaken;

    /** Эффекты, которые поставил именно плагин (снимаем только их). */
    private final Set<PotionEffectType> appliedEffects = new LinkedHashSet<>();

    /** Полоса в боссбаре (если включена). Создаётся и удаляется в регионе игрока. */
    private BossBar bossBar;

    /**
     * Режим «щит без заморозки» (abuse.chronic-policy = IMMUNITY_ONLY):
     * игрока не морозят, но урон по нему и от него всё равно не проходит.
     * Нужен для игроков с постоянно нестабильным каналом, которых иначе
     * приходилось бы морозить по несколько раз в час.
     */
    private volatile boolean immunityOnly;

    /**
     * Персональный режим заморозки (из meta LuckPerms, например {@code pingshield-freeze-mode=teleport}).
     * {@code null} = использовать режим из конфига сервера.
     */
    private volatile PingShieldConfig.FreezeMode freezeModeOverride;

    /**
     * true, пока задача enforce уже стоит в очереди EntityScheduler.
     * Не даёт поставить десяток одинаковых задач, если регион игрока подтормаживает.
     */
    private final AtomicBoolean enforceQueued = new AtomicBoolean(false);

    private int teleportBackCount;

    public Protection(UUID uuid, String name, Reason reason, Location anchor, long startedAt, int thresholdUsed) {
        this.uuid = uuid;
        this.name = name;
        this.reason = reason;
        this.anchor = anchor;
        this.startedAt = startedAt;
        this.thresholdUsed = thresholdUsed;
        this.lastActionbarAt = 0L;
        this.lastEnforcedAt = startedAt;
        this.lastTeleportBackAt = 0L;
        this.lastBossBarUpdateAt = 0L;
    }

    // ------------------------------------------------------------------ базовое

    public UUID getUuid() {
        return uuid;
    }

    public String getName() {
        return name;
    }

    public Reason getReason() {
        return reason;
    }

    public boolean isManual() {
        return reason == Reason.MANUAL;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public int getThresholdUsed() {
        return thresholdUsed;
    }

    public long getElapsedSeconds() {
        return Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
    }

    public Location getAnchor() {
        return anchor;
    }

    public void setAnchor(Location anchor) {
        this.anchor = anchor;
    }

    public int getLastPing() {
        return lastPing;
    }

    public void setLastPing(int lastPing) {
        this.lastPing = lastPing;
        if (lastPing > peakPing) {
            this.peakPing = lastPing;
        }
    }

    public int getPeakPing() {
        return peakPing;
    }

    public long getLastActionbarAt() {
        return lastActionbarAt;
    }

    public void setLastActionbarAt(long value) {
        this.lastActionbarAt = value;
    }

    public long getLastEnforcedAt() {
        return lastEnforcedAt;
    }

    public void setLastEnforcedAt(long value) {
        this.lastEnforcedAt = value;
    }

    public long getLastTeleportBackAt() {
        return lastTeleportBackAt;
    }

    public void setLastTeleportBackAt(long value) {
        this.lastTeleportBackAt = value;
    }

    public long getLastBossBarUpdateAt() {
        return lastBossBarUpdateAt;
    }

    public void setLastBossBarUpdateAt(long value) {
        this.lastBossBarUpdateAt = value;
    }

    public int getTeleportBackCount() {
        return teleportBackCount;
    }

    public void incrementTeleportBackCount() {
        this.teleportBackCount++;
    }

    public boolean isImmunityOnly() {
        return immunityOnly;
    }

    public void setImmunityOnly(boolean immunityOnly) {
        this.immunityOnly = immunityOnly;
    }

    /** Режим заморозки для этого игрока: персональный, если задан, иначе серверный. */
    public PingShieldConfig.FreezeMode effectiveFreezeMode(PingShieldConfig.FreezeMode serverDefault) {
        return freezeModeOverride != null ? freezeModeOverride : serverDefault;
    }

    public void setFreezeModeOverride(PingShieldConfig.FreezeMode mode) {
        this.freezeModeOverride = mode;
    }

    public PingShieldConfig.FreezeMode getFreezeModeOverride() {
        return freezeModeOverride;
    }

    public boolean markEnforceQueued() {
        return enforceQueued.compareAndSet(false, true);
    }

    public void clearEnforceQueued() {
        enforceQueued.set(false);
    }

    public boolean markReleased() {
        return released.compareAndSet(false, true);
    }

    public boolean isReleased() {
        return released.get();
    }

    // ------------------------------------------------------------------ удержание в опасности

    /** Время начала «опасной ситуации» (игрок в лаве/огне/пустоте), -1 если сейчас безопасно. */
    public long getDangerSince() {
        return dangerSince;
    }

    public void setDangerSince(long value) {
        this.dangerSince = value;
    }

    public long getLastDangerNoticeAt() {
        return lastDangerNoticeAt;
    }

    public void setLastDangerNoticeAt(long value) {
        this.lastDangerNoticeAt = value;
    }

    // ------------------------------------------------------------------ снимок состояния

    public void captureSnapshot(Player player) {
        this.hadAllowFlight = player.getAllowFlight();
        this.hadFlying = player.isFlying();
        this.hadCollidable = player.isCollidable();
        this.hadInvulnerable = player.isInvulnerable();
        this.hadFoodLevel = player.getFoodLevel();
        this.hadSaturation = player.getSaturation();
        this.hadFireTicks = player.getFireTicks();
        this.prevFlySpeed = player.getFlySpeed();
        this.prevWalkSpeed = player.getWalkSpeed();
        this.snapshotTaken = true;
    }

    public boolean isSnapshotTaken() {
        return snapshotTaken;
    }

    public boolean hadAllowFlight() {
        return hadAllowFlight;
    }

    public boolean hadFlying() {
        return hadFlying;
    }

    public boolean hadCollidable() {
        return hadCollidable;
    }

    public boolean hadInvulnerable() {
        return hadInvulnerable;
    }

    public int hadFoodLevel() {
        return hadFoodLevel;
    }

    public float hadSaturation() {
        return hadSaturation;
    }

    public int hadFireTicks() {
        return hadFireTicks;
    }

    public float getPrevFlySpeed() {
        return prevFlySpeed;
    }

    public float getPrevWalkSpeed() {
        return prevWalkSpeed;
    }

    public void setPrevFlySpeed(float value) {
        this.prevFlySpeed = value;
    }

    public void setPrevWalkSpeed(float value) {
        this.prevWalkSpeed = value;
    }

    // ------------------------------------------------------------------ эффекты и полоса

    public Set<PotionEffectType> getAppliedEffects() {
        return appliedEffects;
    }

    public BossBar getBossBar() {
        return bossBar;
    }

    public void setBossBar(BossBar bossBar) {
        this.bossBar = bossBar;
    }

    @Override
    public String toString() {
        return "Protection{" + name + ", " + reason + ", ping=" + lastPing + ", " + getElapsedSeconds() + "s"
                + ", tpsBack=" + teleportBackCount + "}";
    }
}
