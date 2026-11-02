package kr.server.dungeon;

import kr.server.dungeon.DungeonInstance.Member;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

import java.util.List;
import java.util.Locale;

/** 사망과 접속 처리를 관리자에게 넘기고, 던전 월드의 규칙(블록 보호, 탈출 차단)을 지킨다. */
final class DungeonListener implements Listener {

    private final DungeonPlugin plugin;
    private final DungeonManager manager;
    private final SessionStore sessions;
    private final PartyManager parties;

    DungeonListener(DungeonPlugin plugin, DungeonManager manager, SessionStore sessions, PartyManager parties) {
        this.plugin = plugin;
        this.manager = manager;
        this.sessions = sessions;
        this.parties = parties;
    }

    // ---- 사망, 접속 ----

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        if (!manager.isActiveMember(player)) {
            return;
        }
        // 인벤토리는 직접 비운다. 레벨은 잃지 않는다.
        event.setKeepInventory(true);
        event.setKeepLevel(true);
        event.getDrops().clear();
        event.setDroppedExp(0);
        manager.onMemberDeath(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location location = manager.respawnLocation(event.getPlayer());
        if (location != null && location.getWorld() != null) {
            event.setRespawnLocation(location);
        }
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        if (event.getEntity().getWorld().equals(manager.world())) {
            manager.onEntityDeath(event.getEntity());
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        manager.onQuit(event.getPlayer());
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                manager.onJoin(player);
            }
        });
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK && event.getClickedBlock() != null
                && event.getClickedBlock().getWorld().equals(manager.world())
                && manager.openRewardChest(event.getPlayer(), event.getClickedBlock())) {
            event.setCancelled(true);
        }
    }

    // ---- 탈출 차단 ----

    @EventHandler(ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (manager.isInternalTeleport()) {
            return;
        }
        Player player = event.getPlayer();
        DungeonInstance instance = manager.instanceOf(player.getUniqueId());
        boolean toDungeon = event.getTo().getWorld().equals(manager.world());
        if (instance == null) {
            if (toDungeon && !player.hasPermission("dungeon.admin")) {
                event.setCancelled(true);
            }
            return;
        }
        Member member = instance.members.get(player.getUniqueId());
        boolean spectatorMenu = member != null && member.spectator
                && event.getCause() == PlayerTeleportEvent.TeleportCause.SPECTATE;
        if (!toDungeon || (spectatorMenu && !instance.floor().contains(event.getTo()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPortal(PlayerPortalEvent event) {
        if (event.getFrom().getWorld().equals(manager.world())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (manager.instanceOf(player.getUniqueId()) == null || player.hasPermission("dungeon.admin")) {
            return;
        }
        String first = event.getMessage().substring(1).split(" ", 2)[0].toLowerCase(Locale.ROOT);
        int colon = first.indexOf(':');
        String name = colon >= 0 ? first.substring(colon + 1) : first;
        List<String> allowed = plugin.getConfig().getStringList("allowed-commands");
        if (!allowed.contains(name)) {
            event.setCancelled(true);
            player.sendMessage(DungeonPlugin.color("&6[던전] &c던전 안에서는 쓸 수 없는 명령어입니다."));
        }
    }

    // ---- 맵 보호 ----

    private boolean protectedFrom(Player player, org.bukkit.World world) {
        return world.equals(manager.world())
                && !(player.hasPermission("dungeon.admin") && manager.instanceOf(player.getUniqueId()) == null);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        if (protectedFrom(event.getPlayer(), event.getBlock().getWorld())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        if (event.getEntity().getWorld().equals(manager.world())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        if (event.getBlock().getWorld().equals(manager.world())) {
            event.blockList().clear();
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        if (event.getBlock().getWorld().equals(manager.world())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event.getEntity().getWorld().equals(manager.world())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBurn(BlockBurnEvent event) {
        if (event.getBlock().getWorld().equals(manager.world())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onIgnite(BlockIgniteEvent event) {
        if (event.getBlock().getWorld().equals(manager.world())
                && event.getCause() != BlockIgniteEvent.IgniteCause.FLINT_AND_STEEL) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLeavesDecay(LeavesDecayEvent event) {
        if (event.getBlock().getWorld().equals(manager.world())) {
            event.setCancelled(true);
        }
    }
}
