package io.github.spa77k.friend;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent.TeleportCause;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

/** フレンドへのテレポート。決めた秒数その場にいたら飛ぶ。動くかダメージを受けたらやめる。 */
public final class Teleports implements Listener {

    private record Pending(UUID target, Location start, BukkitTask task) {
    }

    private final JavaPlugin plugin;
    private final FriendService service;
    private final Messages messages;
    private final long delayTicks;
    private final Map<UUID, Pending> pending = new HashMap<>();

    public Teleports(JavaPlugin plugin, FriendService service, Messages messages, int delaySeconds) {
        this.plugin = plugin;
        this.service = service;
        this.messages = messages;
        this.delayTicks = Math.max(0, delaySeconds) * 20L;
    }

    public void start(Player player, Player target) {
        cancel(player.getUniqueId());
        if (delayTicks == 0) {
            finish(player, target.getUniqueId());
            return;
        }
        messages.send(player, "teleport-wait", "name", target.getName(), "seconds", delayTicks / 20);
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            pending.remove(player.getUniqueId());
            finish(player, target.getUniqueId());
        }, delayTicks);
        pending.put(player.getUniqueId(), new Pending(target.getUniqueId(), player.getLocation(), task));
    }

    private void finish(Player player, UUID targetId) {
        Player target = Bukkit.getPlayer(targetId);
        if (!player.isOnline() || target == null) {
            messages.send(player, "teleport-target-offline");
            return;
        }
        if (!service.areFriends(player.getUniqueId(), targetId)) {
            messages.send(player, "not-friends", "name", target.getName());
            return;
        }
        if (player.teleport(target.getLocation(), TeleportCause.COMMAND)) {
            messages.send(player, "teleported", "name", target.getName());
        } else {
            messages.send(player, "teleport-failed");
        }
    }

    private boolean cancel(UUID player) {
        Pending removed = pending.remove(player);
        if (removed == null) {
            return false;
        }
        removed.task().cancel();
        return true;
    }

    public void cancelAll() {
        for (Pending entry : pending.values()) {
            entry.task().cancel();
        }
        pending.clear();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        Pending entry = pending.get(event.getPlayer().getUniqueId());
        if (entry == null || event.getTo() == null) {
            return;
        }
        Location start = entry.start();
        Location to = event.getTo();
        if (to.getWorld() != start.getWorld() || to.getBlockX() != start.getBlockX()
                || to.getBlockY() != start.getBlockY() || to.getBlockZ() != start.getBlockZ()) {
            cancel(event.getPlayer().getUniqueId());
            messages.send(event.getPlayer(), "teleport-cancelled");
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && cancel(player.getUniqueId())) {
            messages.send(player, "teleport-cancelled");
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        cancel(event.getPlayer().getUniqueId());
    }
}
