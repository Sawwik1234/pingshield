package com.sawwik.pingshield.listener;

import org.bukkit.Location;
import org.bukkit.entity.AreaEffectCloud;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.entity.Tameable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent.DamageCause;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerAttemptPickupItemEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketEntityEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerGameModeChangeEvent;
import org.bukkit.event.player.PlayerHarvestBlockEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTakeLecternBookEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleFlightEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.inventory.InventoryHolder;
import com.sawwik.pingshield.PingShieldConfig;
import com.sawwik.pingshield.PingShieldPlugin;
import com.sawwik.pingshield.ProtectionManager;

import java.util.Locale;

/**
 * Единый слушатель. События Folia вызывает в регионе, владеющем нужной сущностью,
 * поэтому обращения к Bukkit API внутри обработчиков безопасны.
 */
public final class ProtectionListener implements Listener {

    private final PingShieldPlugin plugin;
    private final PingShieldConfig cfg;
    private final ProtectionManager manager;

    public ProtectionListener(PingShieldPlugin plugin, ProtectionManager manager) {
        this.plugin = plugin;
        this.cfg = plugin.config();
        this.manager = manager;
    }

    // ------------------------------------------------------------------ вход / выход / смерть

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        manager.handleJoin(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.handleQuit(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        manager.handleDeath(event.getEntity());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        manager.handleRespawn(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onGameModeChange(PlayerGameModeChangeEvent event) {
        manager.handleGameModeChange(event.getPlayer(), event.getNewGameMode());
    }

    // ------------------------------------------------------------------ движение и полёт

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (!manager.isFrozen(player) || manager.isNpc(player)) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || from.getWorld() != to.getWorld()) {
            event.setCancelled(true);
            return;
        }
        boolean movedHorizontally = from.getX() != to.getX() || from.getZ() != to.getZ();
        boolean movedVertically = from.getY() != to.getY() && !cfg.freezeAllowVertical;
        if (movedHorizontally || movedVertically) {
            // Сервер оставит игрока в точке from и отправит клиенту коррекцию позиции
            event.setCancelled(true);
        }
    }

    /**
     * Игрок может сам выключить полёт (двойной пробел) — и начнёт падать,
     * пока защита ещё активна. Запрещаем.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onToggleFlight(PlayerToggleFlightEvent event) {
        if (cfg.freezeMode != PingShieldConfig.FreezeMode.FLY) {
            return;
        }
        if (manager.isFrozen(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Взрывы, пистоны, рывок тритоном и отбрасывание — всё это задаёт игроку скорость.
     * Обнуляем, иначе игрока придётся постоянно телепортировать назад.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        if (!manager.isFrozen(event.getPlayer())) {
            return;
        }
        if (cfg.freezeMode == PingShieldConfig.FreezeMode.FLY
                && (event.getVelocity().getX() != 0.0D || event.getVelocity().getZ() != 0.0D)) {
            event.setCancelled(true);
        } else if (event.getVelocity().lengthSquared() > 0.0001D) {
            event.setCancelled(true);
        }
    }

    /**
     * Пока игрок заморожен, он не может быть перемещён ничем: порталы, эндер-жемчуг,
     * /tp от плагина — всё отменяется. Иначе «он ничего не может» не выполнялось бы.
     * Исключение — наш собственный возврат на якорь.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        Player player = event.getPlayer();
        if (!manager.isFrozen(player) || event.getTo() == null) {
            return;
        }
        if (manager.isSelfTeleport(player.getUniqueId())) {
            return; // наш возврат на якорь
        }
        if (cfg.blockTeleports) {
            event.setCancelled(true);
            return;
        }
        // Если блокировка телепортов выключена — просто сдвигаем точку удержания
        manager.refreshAnchor(player, event.getTo());
    }

    // ------------------------------------------------------------------ урон

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }

        // Детект искусственного лага: запоминаем, что игрок только что получал урон —
        // попытка «выпросить» защиту сразу после удара/в бою даёт баллы подозрительности
        manager.markDamage(player);

        // Льготный период после снятия защиты: игрок мог «зависнуть» и упасть
        if (event.getCause() == DamageCause.FALL && cfg.fallDamageGraceMs > 0 && manager.isInFallGrace(player)) {
            block(event);
            return;
        }

        if (!cfg.blockIncoming || !manager.isProtected(player) || manager.isNpc(player)) {
            return;
        }
        if (cfg.isIncomingException(event.getCause())) {
            return;
        }
        block(event);
    }

    /**
     * Замороженный игрок не наносит урон сам — иначе высокий пинг превращался бы
     * в преимущество в PvP («лагаю, значит бью безнаказанно»).
     * Дополнительно закрываем урон от его прирученных питомцев и его снарядов.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = resolveAttacker(event.getDamager());

        // Боевой тег: используется античит-режимом combat.prevent-protection
        if (attacker != null) {
            manager.tagCombat(attacker);
            if (event.getEntity() instanceof Player victim) {
                manager.tagCombat(victim);
            }
        }

        if (!cfg.blockOutgoing || attacker == null || manager.isNpc(attacker) || !manager.isProtected(attacker)) {
            return;
        }
        block(event);
    }

    /** Питомцы замороженного игрока (волки, кошки, железные големы) тоже не должны драться за него. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onPetDamage(EntityDamageByEntityEvent event) {
        if (!cfg.blockOutgoing || !cfg.blockTamedPets) {
            return;
        }
        Entity damager = event.getDamager();
        if (!(damager instanceof Tameable tameable)) {
            return;
        }
        if (tameable.getOwner() instanceof Player owner && manager.isProtected(owner)) {
            block(event);
        }
    }

    private Player resolveAttacker(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter) {
            return shooter;
        }
        if (damager instanceof TNTPrimed tnt && tnt.getSource() instanceof Player source) {
            return source;
        }
        if (damager instanceof AreaEffectCloud cloud && cloud.getSource() instanceof Player source) {
            return source;
        }
        return null;
    }

    private void block(EntityDamageEvent event) {
        event.setCancelled(true);
        event.setDamage(0.0D);
    }

    // ------------------------------------------------------------------ мобы

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onEntityTarget(EntityTargetLivingEntityEvent event) {
        if (!cfg.mobsIgnoreProtected) {
            return;
        }
        if (event.getTarget() instanceof Player player && manager.isProtected(player)) {
            event.setCancelled(true);
        }
    }

    // ------------------------------------------------------------------ взаимодействия

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onDropItem(PlayerDropItemEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBucketEntity(PlayerBucketEntityEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onHarvest(PlayerHarvestBlockEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onTakeBook(PlayerTakeLecternBookEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (cfg.blockSleeping && frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof Player && !cfg.blockOwnInventory) {
            return; // свои и чужие инвентари разрешены настройкой
        }
        if (frozenInteraction(player)) {
            event.setCancelled(true);
        }
    }

    /** Замороженный не подбирает предметы — он вообще ничего не делает. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onPickup(PlayerAttemptPickupItemEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Смена слота в хотбаре — тоже действие. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onItemHeld(PlayerItemHeldEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /** Рыбалка (заброс удочки) — взаимодействие с миром. */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (frozenInteraction(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    /**
     * Команды по умолчанию НЕ блокируются: замороженный игрок должен иметь возможность
     * написать /msg, /help или администратору. Если включить block — работает белый список.
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!cfg.blockCommands) {
            return;
        }
        Player player = event.getPlayer();
        if (!manager.isFrozen(player) || manager.isNpc(player)) {
            return;
        }
        String message = event.getMessage();
        if (message == null || message.isEmpty()) {
            return;
        }
        String label = message.substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        if (label.contains(":")) {
            label = label.substring(label.indexOf(':') + 1);
        }
        if (!cfg.allowedCommands.contains(label)) {
            event.setCancelled(true);
        }
    }

    private boolean frozenInteraction(Player player) {
        // «Щит без заморозки» (IMMUNITY_ONLY) не блокирует взаимодействия:
        // игрок не заморожен, ему просто не идёт урон.
        return cfg.blockInteractions && manager.isFrozen(player) && !manager.isNpc(player);
    }
}
