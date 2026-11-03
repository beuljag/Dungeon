package kr.server.dungeon;

import kr.server.dungeon.DungeonData.Reward;
import kr.server.dungeon.DungeonData.ShopItem;
import kr.server.dungeon.DungeonInstance.EventSpot;
import kr.server.dungeon.DungeonInstance.Floor;
import kr.server.dungeon.DungeonInstance.Member;
import kr.server.dungeon.DungeonInstance.ShopHolder;
import kr.server.dungeon.RoomTemplate.Door;
import kr.server.dungeon.RoomTemplate.MarkerType;
import kr.server.dungeon.RoomTemplate.Point;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Villager;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/** 던전 재화, 이벤트 방(보물, 제단, 도박, 웨이브), 상점을 맡는다. */
final class EventManager {

    private static final int SHOP_MAX_SLOTS = 27;
    private static final int TREASURE_SLOTS = 27;
    /** 웨이브는 벽에서 이 칸 수 이상 들어와야 시작한다. 문간에 선 채로 문이 닫히지 않게 한다. */
    private static final int WAVE_TRIGGER_INSET = 2;

    private final DungeonPlugin plugin;
    private final DungeonManager manager;
    private final NamespacedKey dungeonOnlyKey;

    EventManager(DungeonPlugin plugin, DungeonManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.dungeonOnlyKey = new NamespacedKey(plugin, "dungeon_only");
    }

    // ---- 재화 ----

    private String currency() {
        return plugin.getConfig().getString("currency-name", "던전 코인");
    }

    /** 살아 있는 파티원 모두에게 각자 같은 양을 준다. 깊은 층일수록 많아진다. */
    void grantCoins(DungeonInstance instance, Floor floor, String configKey) {
        int base = plugin.getConfig().getInt("coins." + configKey);
        int bonus = plugin.getConfig().getInt("coins.floor-bonus-percent", 50);
        int amount = base * (100 + bonus * floor.index) / 100;
        if (amount <= 0) {
            return;
        }
        instance.members.forEach((id, member) -> {
            if (!member.spectator) {
                member.coins += amount;
                Player player = Bukkit.getPlayer(id);
                if (player != null) {
                    player.sendActionBar(DungeonPlugin.color("&e+" + amount + " " + currency() + " &7(보유 " + member.coins + ")"));
                }
            }
        });
    }

    void showWallet(Player player, DungeonInstance instance) {
        Member member = instance.members.get(player.getUniqueId());
        tell(player, "보유 " + currency() + ": &e" + (member != null ? member.coins : 0) + " &7(던전을 나가면 사라집니다)");
    }

    void onMobDeath(DungeonInstance instance, Floor floor, UUID id) {
        if (floor.mimics.remove(id)) {
            grantCoins(instance, floor, "mimic");
        } else if (floor.coinMobs.remove(id)) {
            grantCoins(instance, floor, "mob");
        }
    }

    // ---- 이벤트 블록 ----

    boolean isSpot(Floor floor, Block block) {
        return floor.spots.containsKey(new Point(block.getX(), block.getY(), block.getZ()));
    }

    /** 이벤트 블록이면 처리하고 true를 돌려준다. */
    boolean useSpot(DungeonInstance instance, Floor floor, Player player, Block block) {
        EventSpot spot = floor.spots.get(new Point(block.getX(), block.getY(), block.getZ()));
        if (spot == null) {
            return false;
        }
        Member member = instance.members.get(player.getUniqueId());
        if (member == null || member.spectator) {
            return true;
        }
        switch (spot.type) {
            case TREASURE -> openTreasure(instance, floor, player, block, spot);
            case ALTAR -> useAltar(player, block, spot);
            case GAMBLE -> gamble(player, member, block, spot);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void openTreasure(DungeonInstance instance, Floor floor, Player player, Block block, EventSpot spot) {
        if (!spot.opened) {
            spot.opened = true;
            if (ThreadLocalRandom.current().nextDouble(100) < plugin.getConfig().getDouble("treasure.mimic-chance", 25)
                    && spawnMimic(floor, block)) {
                manager.broadcast(instance, "&c보물 상자는 미믹이었습니다!");
                return;
            }
            grantCoins(instance, floor, "treasure");
            manager.broadcast(instance, "&e" + player.getName() + " 님이 보물 상자를 열었습니다.");
        }
        Inventory inventory = spot.treasure.computeIfAbsent(player.getUniqueId(), ignored -> rollTreasure(floor));
        if (inventory.isEmpty()) {
            tell(player, "&7상자에 아이템은 없었습니다.");
            return;
        }
        player.openInventory(inventory);
    }

    private boolean spawnMimic(Floor floor, Block block) {
        Location at = block.getLocation().add(0.5, 0, 0.5);
        Entity mimic = Mobs.spawn(plugin.getConfig().getString("treasure.mimic-mob", "ZOMBIE"), at);
        if (mimic == null) {
            plugin.getLogger().warning("미믹을 소환하지 못했습니다. config.yml의 treasure.mimic-mob을 확인하세요.");
            return false;
        }
        mimic.customName(DungeonPlugin.color("&c미믹"));
        mimic.setCustomNameVisible(true);
        block.setType(Material.AIR, false);
        floor.spots.remove(new Point(block.getX(), block.getY(), block.getZ()));
        floor.mimics.add(mimic.getUniqueId());
        return true;
    }

    private Inventory rollTreasure(Floor floor) {
        DungeonInstance.RewardHolder holder = new DungeonInstance.RewardHolder();
        Inventory inventory = Bukkit.createInventory(holder, TREASURE_SLOTS, DungeonPlugin.color("&8보물 상자"));
        holder.bind(inventory);
        for (Reward reward : floor.def.treasure) {
            if (ThreadLocalRandom.current().nextDouble(100) < reward.chance()) {
                inventory.addItem(reward.item().clone());
            }
        }
        return inventory;
    }

    private void useAltar(Player player, Block block, EventSpot spot) {
        if (spot.uses.containsKey(player.getUniqueId())) {
            tell(player, "&7이 제단은 이미 사용했습니다.");
            return;
        }
        boolean cursed = ThreadLocalRandom.current().nextDouble(100) < plugin.getConfig().getDouble("altar.curse-chance", 20);
        PotionEffect effect = pickEffect(cursed ? "altar.curses" : "altar.blessings");
        if (effect == null) {
            tell(player, "&7제단이 반응하지 않습니다.");
            return;
        }
        spot.uses.put(player.getUniqueId(), 1);
        player.addPotionEffect(effect);
        tell(player, cursed ? "&c제단이 저주를 내렸습니다!" : "&a제단이 축복을 내렸습니다!");
        player.playSound(player.getLocation(), cursed ? Sound.ENTITY_WITHER_SPAWN : Sound.BLOCK_BEACON_ACTIVATE, 0.7f, 1f);
        block.getWorld().spawnParticle(cursed ? Particle.SMOKE : Particle.ENCHANT, block.getLocation().add(0.5, 1, 0.5), 30, 0.4, 0.5, 0.4);
    }

    /** 설정 한 줄의 형식은 "효과이름 레벨 초"이다. 같은 줄을 여러 번 적으면 그만큼 자주 나온다. */
    private PotionEffect pickEffect(String path) {
        List<String> lines = plugin.getConfig().getStringList(path);
        if (lines.isEmpty()) {
            return null;
        }
        String line = lines.get(ThreadLocalRandom.current().nextInt(lines.size()));
        String[] part = line.trim().split("\\s+");
        try {
            NamespacedKey key = NamespacedKey.minecraft(part[0].toLowerCase());
            PotionEffectType type = Registry.EFFECT.get(key);
            if (type != null) {
                return new PotionEffect(type, Integer.parseInt(part[2]) * 20, Integer.parseInt(part[1]) - 1);
            }
        } catch (ArrayIndexOutOfBoundsException | IllegalArgumentException e) {
            // 아래에서 함께 알린다.
        }
        plugin.getLogger().warning(path + "의 효과 형식이 잘못되었습니다: " + line);
        return null;
    }

    private void gamble(Player player, Member member, Block block, EventSpot spot) {
        int bet = plugin.getConfig().getInt("gamble.bet", 20);
        int maxUses = plugin.getConfig().getInt("gamble.max-uses", 3);
        int used = spot.uses.getOrDefault(player.getUniqueId(), 0);
        if (used >= maxUses) {
            tell(player, "&7이 제단은 더 이상 쓸 수 없습니다.");
            return;
        }
        if (member.coins < bet) {
            tell(player, "&c" + currency() + " 부족: " + bet + " 필요, 보유 " + member.coins);
            return;
        }
        spot.uses.put(player.getUniqueId(), used + 1);
        boolean win = ThreadLocalRandom.current().nextDouble(100) < plugin.getConfig().getDouble("gamble.win-chance", 45);
        member.coins += win ? bet : -bet;
        tell(player, (win ? "&a성공! " + bet * 2 + " 받았습니다." : "&c실패! " + bet + " 잃었습니다.")
                + " &7보유 " + member.coins + ", 남은 횟수 " + (maxUses - used - 1));
        player.playSound(player.getLocation(), win ? Sound.ENTITY_PLAYER_LEVELUP : Sound.ENTITY_ITEM_BREAK, 1f, 1f);
        block.getWorld().spawnParticle(win ? Particle.HAPPY_VILLAGER : Particle.SMOKE, block.getLocation().add(0.5, 1, 0.5), 20, 0.4, 0.5, 0.4);
    }

    // ---- 웨이브 ----

    boolean shouldStartWave(PlacedRoom room, Location at) {
        return room.containsInner(at.getX(), at.getY(), at.getZ(), WAVE_TRIGGER_INSET);
    }

    void startWave(DungeonInstance instance, Floor floor, PlacedRoom room) {
        setUsedDoors(room, material("wave.door-block", Material.IRON_BARS));
        room.wave = 1;
        manager.broadcast(instance, "&c문이 닫혔습니다! 몹을 모두 쓰러뜨리세요.");
        spawnWave(instance, floor, room);
    }

    private void spawnWave(DungeonInstance instance, Floor floor, PlacedRoom room) {
        for (Point point : room.template.marker(MarkerType.SPAWN)) {
            for (Entity mob : manager.spawnFloorMobs(instance, floor, room.toWorld(point))) {
                room.waveMobs.add(mob.getUniqueId());
            }
        }
    }

    void tickWaves(DungeonInstance instance, Floor floor) {
        for (PlacedRoom room : floor.rooms) {
            if (room.wave > 0) {
                tickWave(instance, floor, room);
            }
        }
    }

    private void tickWave(DungeonInstance instance, Floor floor, PlacedRoom room) {
        if (!someoneInside(instance, room)) {
            for (UUID id : room.waveMobs) {
                Entity mob = Bukkit.getEntity(id);
                if (mob != null) {
                    mob.remove();
                }
            }
            room.waveMobs.clear();
            room.wave = 0;
            setUsedDoors(room, Material.AIR);
            manager.broadcast(instance, "&7웨이브 방에 아무도 남지 않아 문이 열렸습니다.");
            return;
        }
        room.waveMobs.removeIf(id -> {
            Entity mob = Bukkit.getEntity(id);
            return mob == null || mob.isDead();
        });
        if (!room.waveMobs.isEmpty()) {
            return;
        }
        int total = plugin.getConfig().getInt("wave.count", 3);
        if (room.wave < total) {
            room.wave++;
            manager.broadcast(instance, "&c웨이브 " + room.wave + "/" + total);
            spawnWave(instance, floor, room);
            return;
        }
        room.wave = 0;
        setUsedDoors(room, Material.AIR);
        grantCoins(instance, floor, "wave-clear");
        manager.broadcast(instance, "&a웨이브를 모두 막아냈습니다! 문이 열렸습니다.");
    }

    private boolean someoneInside(DungeonInstance instance, PlacedRoom room) {
        for (Map.Entry<UUID, Member> entry : instance.members.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !entry.getValue().spectator && !player.isDead()) {
                Location at = player.getLocation();
                if (room.contains(at.getX(), at.getY(), at.getZ())) {
                    return true;
                }
            }
        }
        return false;
    }

    private void setUsedDoors(PlacedRoom room, Material material) {
        List<Door> doors = room.template.doors();
        for (int i = 0; i < doors.size(); i++) {
            if (room.doorUsed[i]) {
                manager.fillDoor(room, doors.get(i), material);
            }
        }
    }

    // ---- 상점 ----

    void spawnShopkeeper(Floor floor, PlacedRoom room, World world) {
        for (Point point : room.template.marker(MarkerType.SHOPKEEPER)) {
            Point at = room.toWorld(point);
            Villager villager = world.spawn(new Location(world, at.x() + 0.5, at.y(), at.z() + 0.5), Villager.class, spawned -> {
                spawned.setAI(false);
                spawned.setInvulnerable(true);
                spawned.setSilent(true);
                spawned.setRemoveWhenFarAway(false);
                spawned.setCollidable(false);
                spawned.customName(DungeonPlugin.color("&6던전 상인"));
                spawned.setCustomNameVisible(true);
            });
            floor.shopkeepers.put(villager.getUniqueId(), pickStock(floor));
        }
    }

    private List<ShopItem> pickStock(Floor floor) {
        List<ShopItem> stock = new ArrayList<>(floor.def.shop);
        Collections.shuffle(stock);
        int slots = Math.clamp(plugin.getConfig().getInt("shop.slots", 9), 1, SHOP_MAX_SLOTS);
        return stock.size() > slots ? new ArrayList<>(stock.subList(0, slots)) : stock;
    }

    /** 상인이면 상점 창을 열고 true를 돌려준다. */
    boolean openShop(DungeonInstance instance, Floor floor, Player player, Entity entity) {
        List<ShopItem> stock = floor.shopkeepers.get(entity.getUniqueId());
        if (stock == null) {
            return false;
        }
        Member member = instance.members.get(player.getUniqueId());
        if (member == null || member.spectator) {
            return true;
        }
        if (stock.isEmpty()) {
            tell(player, "&7팔 물건이 없습니다.");
            return true;
        }
        ShopHolder holder = new ShopHolder(stock);
        Inventory inventory = Bukkit.createInventory(holder, SHOP_MAX_SLOTS,
                DungeonPlugin.color("&8던전 상점 &7(보유 " + member.coins + ")"));
        holder.bind(inventory);
        for (int i = 0; i < stock.size(); i++) {
            inventory.setItem(i, displayItem(stock.get(i)));
        }
        player.openInventory(inventory);
        return true;
    }

    private ItemStack displayItem(ShopItem shopItem) {
        ItemStack display = shopItem.item().clone();
        ItemMeta meta = display.getItemMeta();
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        lore.add(DungeonPlugin.color("&e가격: " + shopItem.price() + " " + currency()));
        lore.add(DungeonPlugin.color("&7클릭해서 구매"));
        lore.add(DungeonPlugin.color("&c던전을 나가면 사라집니다"));
        meta.lore(lore);
        display.setItemMeta(meta);
        return display;
    }

    void buy(Player player, ShopHolder holder, int slot) {
        DungeonInstance instance = manager.instanceOf(player.getUniqueId());
        Member member = instance != null ? instance.members.get(player.getUniqueId()) : null;
        if (member == null || member.spectator || slot < 0 || slot >= holder.stock.size()) {
            return;
        }
        ShopItem shopItem = holder.stock.get(slot);
        if (member.coins < shopItem.price()) {
            tell(player, "&c" + currency() + " 부족: " + shopItem.price() + " 필요, 보유 " + member.coins);
            return;
        }
        if (player.getInventory().firstEmpty() < 0) {
            tell(player, "&c인벤토리에 빈 칸이 없습니다.");
            return;
        }
        member.coins -= shopItem.price();
        player.getInventory().addItem(markDungeonOnly(shopItem.item().clone()));
        player.playSound(player.getLocation(), Sound.ENTITY_VILLAGER_YES, 1f, 1f);
        tell(player, "구매했습니다. &7보유 " + member.coins);
    }

    private ItemStack markDungeonOnly(ItemStack item) {
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(dungeonOnlyKey, PersistentDataType.BYTE, (byte) 1);
        List<Component> lore = new ArrayList<>(meta.hasLore() ? meta.lore() : List.of());
        lore.add(DungeonPlugin.color("&c던전 전용 (나가면 사라짐)"));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    boolean isDungeonOnly(ItemStack item) {
        return item != null && item.hasItemMeta()
                && item.getItemMeta().getPersistentDataContainer().has(dungeonOnlyKey, PersistentDataType.BYTE);
    }

    /** 던전을 나갈 때 부른다. 구매한 아이템을 인벤토리, 커서, 엔더 상자에서 지운다. */
    void removeDungeonOnly(Player player) {
        if (isDungeonOnly(player.getItemOnCursor())) {
            player.setItemOnCursor(null);
        }
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            if (isDungeonOnly(contents[i])) {
                inventory.setItem(i, null);
            }
        }
        Inventory enderChest = player.getEnderChest();
        for (int i = 0; i < enderChest.getSize(); i++) {
            if (isDungeonOnly(enderChest.getItem(i))) {
                enderChest.setItem(i, null);
            }
        }
    }

    private Material material(String path, Material fallback) {
        Material material = Material.matchMaterial(plugin.getConfig().getString(path, ""));
        return material != null && material.isBlock() ? material : fallback;
    }

    private static void tell(Player player, String message) {
        player.sendMessage(DungeonPlugin.color("&6[던전] &f" + message));
    }
}
