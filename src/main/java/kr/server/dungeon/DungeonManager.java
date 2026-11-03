package kr.server.dungeon;

import com.sk89q.worldedit.WorldEditException;
import kr.server.dungeon.DungeonData.DungeonDef;
import kr.server.dungeon.DungeonData.FloorDef;
import kr.server.dungeon.DungeonData.Reward;
import kr.server.dungeon.DungeonInstance.Floor;
import kr.server.dungeon.DungeonInstance.Member;
import kr.server.dungeon.RoomTemplate.Dir;
import kr.server.dungeon.RoomTemplate.Door;
import kr.server.dungeon.RoomTemplate.MarkerType;
import kr.server.dungeon.RoomTemplate.Point;
import kr.server.dungeon.RoomTemplate.RoomType;
import kr.server.dungeon.SessionStore.Session;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;
import java.util.stream.Stream;

/** 던전 생성, 입장과 퇴장, 층 진행, 사망과 재접속을 맡는다. */
final class DungeonManager implements Runnable {

    /** 층 하나가 쓰는 칸의 크기. 층은 칸을 하나씩 새로 받고, 칸은 서버를 재시작할 때까지 다시 쓰지 않는다. */
    private static final int CELL_SIZE = 1024;
    private static final int CELLS_PER_ROW = 1000;
    private static final int FLOOR_BASE_Y = 100;
    private static final double GATE_RANGE_SQUARED = 1.5 * 1.5;
    private static final int REWARD_SLOTS = 27;

    private final DungeonPlugin plugin;
    private final DungeonData data;
    private final RoomLibrary rooms;
    private final SessionStore sessions;
    private final PartyManager parties;
    private final Random random = new Random();
    private final EventManager events;

    private final Map<Integer, DungeonInstance> instances = new HashMap<>();
    private final Map<UUID, DungeonInstance> byPlayer = new HashMap<>();
    private final Set<UUID> pending = new HashSet<>();
    private World world;
    private int nextId = 1;
    private int nextCell;
    /** 이 값이 true인 동안의 텔레포트는 플러그인이 직접 시킨 것이다. */
    private boolean internalTeleport;

    DungeonManager(DungeonPlugin plugin, DungeonData data, RoomLibrary rooms, SessionStore sessions, PartyManager parties) {
        this.plugin = plugin;
        this.data = data;
        this.rooms = rooms;
        this.sessions = sessions;
        this.parties = parties;
        this.events = new EventManager(plugin, this);
    }

    EventManager events() {
        return events;
    }

    /** 지난 실행의 던전 월드를 지우고 빈 월드를 새로 만든다. */
    void prepareWorld() {
        String name = plugin.getConfig().getString("world-name", "dungeon_instances");
        if (Bukkit.getWorld(name) == null) {
            deleteFolder(new File(Bukkit.getWorldContainer(), name).toPath());
        }
        world = new WorldCreator(name).generator(new VoidGenerator()).generateStructures(false).createWorld();
        world.setGameRule(GameRule.DO_MOB_SPAWNING, false);
        world.setGameRule(GameRule.DO_DAYLIGHT_CYCLE, false);
        world.setGameRule(GameRule.DO_WEATHER_CYCLE, false);
        world.setGameRule(GameRule.MOB_GRIEFING, false);
        world.setGameRule(GameRule.SPAWN_CHUNK_RADIUS, 0);
        world.setGameRule(GameRule.ANNOUNCE_ADVANCEMENTS, false);
        world.setTime(18000);
        world.setPVP(false);
    }

    private void deleteFolder(Path folder) {
        if (!Files.isDirectory(folder)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(folder)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        } catch (IOException e) {
            plugin.getLogger().warning("지난 던전 월드를 지우지 못했습니다: " + e);
        }
    }

    World world() {
        return world;
    }

    boolean isInternalTeleport() {
        return internalTeleport;
    }

    DungeonInstance instanceOf(UUID player) {
        return byPlayer.get(player);
    }

    Map<Integer, DungeonInstance> instances() {
        return instances;
    }

    // ---- 입장 ----

    /** 던전을 열 수 없는 이유 목록. 비어 있으면 입장할 수 있다. */
    List<String> validate(DungeonDef def) {
        List<String> problems = new ArrayList<>();
        if (def.floors.isEmpty()) {
            problems.add("층이 없습니다.");
        }
        if (rooms.byType(RoomType.START, def.theme).isEmpty()) {
            problems.add("테마 '" + def.theme + "'의 시작 방이 없습니다.");
        }
        if (rooms.byType(RoomType.BOSS, def.theme).isEmpty()) {
            problems.add("테마 '" + def.theme + "'의 보스 방이 없습니다.");
        }
        boolean needsNormal = def.floors.stream().anyMatch(floor -> floor.rooms > 0);
        if (needsNormal && rooms.byType(RoomType.NORMAL, def.theme).isEmpty()) {
            problems.add("테마 '" + def.theme + "'의 일반 방이 없습니다.");
        }
        for (int i = 0; i < def.floors.size(); i++) {
            FloorDef floor = def.floors.get(i);
            if (floor.eventRooms > 0 && rooms.byType(RoomType.EVENT, def.theme).isEmpty()) {
                problems.add((i + 1) + "층에 이벤트 방을 설정했지만 테마 '" + def.theme + "'의 이벤트 방이 없습니다.");
            }
            if (floor.shopChance > 0 && rooms.byType(RoomType.SHOP, def.theme).isEmpty()) {
                problems.add((i + 1) + "층에 상점 방을 설정했지만 테마 '" + def.theme + "'의 상점 방이 없습니다.");
            }
            if (!Mobs.exists(floor.boss)) {
                problems.add((i + 1) + "층 보스가 없거나 존재하지 않는 몹입니다: " + floor.boss);
            }
            for (String mob : floor.mobs.keySet()) {
                if (!Mobs.exists(mob)) {
                    problems.add((i + 1) + "층 몹이 존재하지 않습니다: " + mob);
                }
            }
        }
        return problems;
    }

    /** 파티(또는 혼자)를 던전에 들여보낸다. 바로 시작할 수 없으면 이유를 돌려주고, 시작했으면 null을 돌려준다. */
    String enter(Player leader, DungeonDef def) {
        PartyManager.Party party = parties.get(leader.getUniqueId());
        if (party != null && !party.leader().equals(leader.getUniqueId())) {
            return "파티장만 입장할 수 있습니다.";
        }
        List<UUID> team = party != null ? new ArrayList<>(party.members) : List.of(leader.getUniqueId());
        for (UUID id : team) {
            Player player = Bukkit.getPlayer(id);
            if (player == null) {
                return "접속하지 않은 파티원이 있습니다.";
            }
            if (sessions.get(id) != null || pending.contains(id)) {
                return player.getName() + " 님은 이미 던전에 있거나 입장 중입니다.";
            }
        }
        if (leader.isDead()) {
            return "죽은 상태로는 입장할 수 없습니다.";
        }
        List<String> problems = validate(def);
        if (!problems.isEmpty()) {
            plugin.getLogger().warning("던전 '" + def.name + "'을(를) 열 수 없습니다: " + problems);
            return "이 던전은 아직 준비되지 않았습니다. 관리자에게 문의하세요.";
        }

        DungeonInstance instance = new DungeonInstance(nextId++, def);
        pending.addAll(team);
        tell(team, "&7던전을 만드는 중입니다...");
        buildFloor(instance, 0, floor -> {
            if (floor == null) {
                pending.removeAll(team);
                tell(team, "&c던전을 만들지 못했습니다. 관리자에게 문의하세요.");
                return;
            }
            instance.floors.add(floor);
            long delay = plugin.getConfig().getLong("entry-delay-seconds", 5);
            tell(team, "&e" + delay + "초 뒤 입장합니다. &c죽으면 귀속 저주가 없는 아이템을 모두 잃습니다."
                    + (team.size() > 1 ? " &7빠지려면 /던전 파티 나가기" : ""));
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                pending.removeAll(team);
                start(instance, leader.getUniqueId());
            }, delay * 20L);
        });
        return null;
    }

    private void start(DungeonInstance instance, UUID leaderId) {
        Player leader = Bukkit.getPlayer(leaderId);
        if (leader == null || leader.isDead()) {
            clearEntities(instance.floor());
            return;
        }
        PartyManager.Party party = parties.get(leaderId);
        List<UUID> team = party != null ? new ArrayList<>(party.members) : List.of(leaderId);
        instances.put(instance.id, instance);
        for (UUID id : team) {
            Player player = Bukkit.getPlayer(id);
            if (player != null && !player.isDead() && sessions.get(id) == null) {
                join(instance, player);
            }
        }
        announceFloor(instance);
    }

    private void join(DungeonInstance instance, Player player) {
        UUID id = player.getUniqueId();
        sessions.put(id, new Session(player.getLocation(), player.getGameMode(), false));
        instance.members.put(id, new Member(player.getName()));
        byPlayer.put(id, instance);
        move(player, instance.floor().start);
        player.setGameMode(GameMode.SURVIVAL);
        player.setAllowFlight(false);
        player.setFlying(false);
    }

    private void announceFloor(DungeonInstance instance) {
        Floor floor = instance.floor();
        String title = "&6" + instance.def.name + " &f" + (floor.index + 1) + "층";
        String subtitle = "&7보스를 쓰러뜨려야 나갈 수 있습니다";
        forOnline(instance, player -> player.showTitle(Title.title(DungeonPlugin.color(title), DungeonPlugin.color(subtitle))));
    }

    // ---- 층 생성 ----

    /** 배치를 정하고 방을 틱마다 하나씩 붙여 넣은 뒤 callback을 부른다. 실패하면 null을 넘긴다. */
    private void buildFloor(DungeonInstance instance, int index, Consumer<Floor> callback) {
        FloorDef def = instance.def.floors.get(index);
        int cell = nextCell++;
        Point center = new Point(cell % CELLS_PER_ROW * CELL_SIZE + CELL_SIZE / 2, FLOOR_BASE_Y,
                cell / CELLS_PER_ROW * CELL_SIZE + CELL_SIZE / 2);
        List<PlacedRoom> layout = LayoutGenerator.generate(rooms.byType(RoomType.START, instance.def.theme),
                rooms.byType(RoomType.NORMAL, instance.def.theme), rooms.byType(RoomType.BOSS, instance.def.theme),
                pickSpecialRooms(instance, def), def.rooms, center, random);
        if (layout == null) {
            plugin.getLogger().warning("던전 '" + instance.def.name + "' " + (index + 1)
                    + "층 배치에 실패했습니다. 문이 둘 이상인 일반 방과 문 크기가 맞는지 확인하세요.");
            callback.accept(null);
            return;
        }
        Deque<PlacedRoom> queue = new ArrayDeque<>(layout);
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            PlacedRoom room = queue.poll();
            if (room == null) {
                task.cancel();
                Point marker = layout.getFirst().toWorld(layout.getFirst().template.marker(MarkerType.PLAYER_START).getFirst());
                Location start = new Location(world, marker.x() + 0.5, marker.y(), marker.z() + 0.5);
                callback.accept(new Floor(index, def, layout, start));
                return;
            }
            try {
                rooms.paste(world, room);
                finishRoom(room);
            } catch (IOException | WorldEditException e) {
                task.cancel();
                plugin.getLogger().severe("방 '" + room.template.name() + "' 붙여넣기 실패: " + e);
                callback.accept(null);
            }
        }, 0L, 1L);
    }

    private List<RoomTemplate> pickSpecialRooms(DungeonInstance instance, FloorDef def) {
        List<RoomTemplate> specials = new ArrayList<>();
        List<RoomTemplate> eventRooms = rooms.byType(RoomType.EVENT, instance.def.theme);
        for (int i = 0; i < def.eventRooms && !eventRooms.isEmpty(); i++) {
            specials.add(eventRooms.get(random.nextInt(eventRooms.size())));
        }
        List<RoomTemplate> shopRooms = rooms.byType(RoomType.SHOP, instance.def.theme);
        if (!shopRooms.isEmpty() && random.nextDouble() * 100 < def.shopChance) {
            specials.add(shopRooms.get(random.nextInt(shopRooms.size())));
        }
        return specials;
    }

    /** 표지판을 지우고, 이벤트 블록을 놓고, 연결되지 않은 문을 막는다. */
    private void finishRoom(PlacedRoom room) {
        for (Map.Entry<MarkerType, List<Point>> entry : room.template.markers().entrySet()) {
            Material block = switch (entry.getKey()) {
                case TREASURE -> Material.CHEST;
                case ALTAR -> material("altar.block", Material.ENCHANTING_TABLE);
                case GAMBLE -> material("gamble.block", Material.LODESTONE);
                default -> Material.AIR;
            };
            for (Point point : entry.getValue()) {
                setBlock(room.toWorld(point), block);
            }
        }
        Material seal = material("door-seal-block", Material.STONE_BRICKS);
        List<Door> doors = room.template.doors();
        for (int i = 0; i < doors.size(); i++) {
            Door door = doors.get(i);
            setBlock(room.toWorld(door.pos()), Material.AIR);
            if (!room.doorUsed[i]) {
                fillDoor(room, door, seal);
            }
        }
    }

    /** 문의 바깥 면 전체를 한 블록으로 채운다. */
    void fillDoor(PlacedRoom room, Door door, Material material) {
        Point center = room.toWorld(door.pos());
        Dir dir = room.doorDir(door);
        int half = (door.width() - 1) / 2;
        for (int side = -half; side <= half; side++) {
            for (int up = 0; up < door.height(); up++) {
                setBlock(new Point(center.x() + side * Math.abs(dir.dz), center.y() + up,
                        center.z() + side * Math.abs(dir.dx)), material);
            }
        }
    }

    private void setBlock(Point point, Material material) {
        world.getBlockAt(point.x(), point.y(), point.z()).setType(material, false);
    }

    private Material material(String path, Material fallback) {
        Material material = Material.matchMaterial(plugin.getConfig().getString(path, ""));
        return material != null && material.isBlock() ? material : fallback;
    }

    // ---- 주기 처리 ----

    @Override
    public void run() {
        for (DungeonInstance instance : new ArrayList<>(instances.values())) {
            tickOffline(instance);
            if (!instance.ended) {
                tickFloor(instance);
            }
        }
    }

    private void tickOffline(DungeonInstance instance) {
        long limit = plugin.getConfig().getLong("reconnect-seconds", 300) * 1000L;
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Member> entry : new ArrayList<>(instance.members.entrySet())) {
            Member member = entry.getValue();
            if (member.offlineSince >= 0 && now - member.offlineSince >= limit) {
                UUID id = entry.getKey();
                instance.members.remove(id);
                byPlayer.remove(id);
                Session session = sessions.get(id);
                if (session != null && !member.spectator) {
                    session.penalty = true;
                    sessions.save();
                }
                broadcast(instance, "&c" + member.name + " 님이 시간 안에 돌아오지 않았습니다.");
                checkEnd(instance);
            }
        }
    }

    private void tickFloor(DungeonInstance instance) {
        Floor floor = instance.floor();
        if (floor.building) {
            return;
        }
        for (UUID id : new ArrayList<>(instance.members.keySet())) {
            Member member = instance.members.get(id);
            Player player = Bukkit.getPlayer(id);
            if (member == null || player == null || player.isDead()) {
                continue;
            }
            if (member.spectator) {
                if (!floor.start.getWorld().equals(player.getWorld()) || !floor.contains(player.getLocation())) {
                    move(player, spectatorTarget(instance));
                }
                continue;
            }
            activateRooms(instance, floor, player);
            if (floor.bossDead) {
                checkGates(instance, floor, player);
                if (instance.ended || instance.floor() != floor) {
                    return;
                }
            }
        }
        events.tickWaves(instance, floor);
        keepBossAlive(floor);
        if (floor.bossDead) {
            showGateParticles(floor, !instance.isLastFloor());
        }
        if (floor.descendAt >= 0 && System.currentTimeMillis() >= floor.descendAt) {
            descend(instance);
        }
    }

    private void activateRooms(DungeonInstance instance, Floor floor, Player player) {
        Location at = player.getLocation();
        for (PlacedRoom room : floor.rooms) {
            if (room.activated || !room.contains(at.getX(), at.getY(), at.getZ())) {
                continue;
            }
            if (!room.template.marker(MarkerType.WAVE).isEmpty()) {
                // 웨이브 방의 [몹]은 웨이브마다 쓰므로 처음 들어올 때 소환하지 않는다.
                if (events.shouldStartWave(room, at)) {
                    room.activated = true;
                    events.startWave(instance, floor, room);
                }
                continue;
            }
            room.activated = true;
            for (Point point : room.template.marker(MarkerType.SPAWN)) {
                spawnFloorMob(floor, room.toWorld(point));
            }
            events.spawnShopkeeper(floor, room, world);
            if (room.template.type() == RoomType.BOSS) {
                Point spot = room.toWorld(room.template.marker(MarkerType.BOSS).getFirst());
                floor.bossSpot = new Location(world, spot.x() + 0.5, spot.y(), spot.z() + 0.5);
                placeBossRoomMarkers(floor, room);
                spawnBoss(instance, floor);
            }
        }
    }

    /** 층의 몹 목록에서 하나를 골라 소환한다. 목록이 비었거나 실패하면 null이다. */
    Entity spawnFloorMob(Floor floor, Point point) {
        if (floor.def.mobs.isEmpty()) {
            return null;
        }
        int total = floor.def.mobs.values().stream().mapToInt(Integer::intValue).sum();
        int roll = ThreadLocalRandom.current().nextInt(total);
        for (Map.Entry<String, Integer> entry : floor.def.mobs.entrySet()) {
            roll -= entry.getValue();
            if (roll < 0) {
                Entity mob = Mobs.spawn(entry.getKey(), new Location(world, point.x() + 0.5, point.y(), point.z() + 0.5));
                if (mob == null) {
                    plugin.getLogger().warning("몹을 소환하지 못했습니다: " + entry.getKey());
                    return null;
                }
                floor.coinMobs.add(mob.getUniqueId());
                return mob;
            }
        }
        return null;
    }

    private void placeBossRoomMarkers(Floor floor, PlacedRoom room) {
        floor.chest = blockLocation(room.toWorld(room.template.marker(MarkerType.REWARD).getFirst()));
        floor.nextGate = blockLocation(room.toWorld(room.template.marker(MarkerType.NEXT).getFirst()));
        floor.exitGate = blockLocation(room.toWorld(room.template.marker(MarkerType.EXIT).getFirst()));
    }

    private Location blockLocation(Point point) {
        return new Location(world, point.x(), point.y(), point.z());
    }

    private void spawnBoss(DungeonInstance instance, Floor floor) {
        Entity boss = Mobs.spawn(floor.def.boss, floor.bossSpot);
        if (boss == null) {
            // 보스가 없어지면 아무도 나갈 수 없으므로, 소환에 실패하면 잡은 것으로 처리한다.
            plugin.getLogger().severe("보스를 소환하지 못해 처치한 것으로 처리합니다: " + floor.def.boss);
            onBossDead(instance, floor);
            return;
        }
        floor.bossId = boss.getUniqueId();
        broadcast(instance, "&4보스가 나타났습니다!");
    }

    /** 보스가 죽지 않았는데 사라졌으면 (디스폰, 스킬 등) 다시 소환해서 층이 막히지 않게 한다. */
    private void keepBossAlive(Floor floor) {
        if (floor.bossId == null || floor.bossDead || floor.bossSpot == null) {
            return;
        }
        if (Bukkit.getEntity(floor.bossId) == null && floor.bossSpot.isChunkLoaded()
                && floor.bossSpot.getChunk().isEntitiesLoaded()) {
            Entity boss = Mobs.spawn(floor.def.boss, floor.bossSpot);
            if (boss != null) {
                floor.bossId = boss.getUniqueId();
            }
        }
    }

    void onEntityDeath(Entity entity) {
        for (DungeonInstance instance : new ArrayList<>(instances.values())) {
            Floor floor = instance.floor();
            if (entity.getUniqueId().equals(floor.bossId) && !floor.bossDead) {
                onBossDead(instance, floor);
                return;
            }
            events.onMobDeath(instance, floor, entity.getUniqueId());
        }
    }

    private void onBossDead(DungeonInstance instance, Floor floor) {
        floor.bossDead = true;
        instance.members.forEach((id, member) -> {
            if (!member.spectator) {
                floor.rewardable.add(id);
            }
        });
        floor.chest.getBlock().setType(Material.CHEST);
        events.grantCoins(instance, floor, "boss");

        placeGate(floor.exitGate, material("exit-gate-block", Material.GOLD_BLOCK), "&e나가기");
        if (instance.isLastFloor()) {
            broadcast(instance, "&a던전을 클리어했습니다! &f상자에서 보상을 받고 &e나가기 &f발판으로 나가세요.");
        } else {
            placeGate(floor.nextGate, material("next-gate-block", Material.CRYING_OBSIDIAN), "&d다음 층으로");
            broadcast(instance, "&a보스를 쓰러뜨렸습니다! &f상자에서 보상을 받고 &d다음 층 &f또는 &e나가기 &f발판을 밟으세요.");
        }
        forOnline(instance, player -> player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, 1f, 1f));
    }

    private void placeGate(Location gate, Material floorBlock, String label) {
        gate.clone().subtract(0, 1, 0).getBlock().setType(floorBlock);
        world.spawn(gate.clone().add(0.5, 1.7, 0.5), TextDisplay.class, display -> {
            display.text(DungeonPlugin.color(label));
            display.setBillboard(Display.Billboard.CENTER);
            display.setPersistent(false);
        });
    }

    private void showGateParticles(Floor floor, boolean hasNext) {
        world.spawnParticle(Particle.HAPPY_VILLAGER, floor.exitGate.clone().add(0.5, 0.5, 0.5), 5, 0.3, 0.5, 0.3);
        if (hasNext) {
            world.spawnParticle(Particle.PORTAL, floor.nextGate.clone().add(0.5, 0.5, 0.5), 15, 0.3, 0.5, 0.3);
        }
    }

    private void checkGates(DungeonInstance instance, Floor floor, Player player) {
        Location at = player.getLocation();
        if (at.distanceSquared(floor.exitGate.clone().add(0.5, 0, 0.5)) <= GATE_RANGE_SQUARED) {
            leaveSafely(instance, player, "&a던전에서 무사히 나왔습니다.");
            checkEnd(instance);
            return;
        }
        if (!instance.isLastFloor() && floor.descendAt < 0
                && at.distanceSquared(floor.nextGate.clone().add(0.5, 0, 0.5)) <= GATE_RANGE_SQUARED) {
            long delay = plugin.getConfig().getLong("descend-delay-seconds", 10);
            floor.descendAt = System.currentTimeMillis() + delay * 1000L;
            broadcast(instance, "&d" + player.getName() + " 님이 다음 층을 선택했습니다. " + delay
                    + "초 뒤 내려갑니다. &e나가려면 지금 나가기 발판을 밟으세요.");
        }
    }

    private void descend(DungeonInstance instance) {
        Floor floor = instance.floor();
        floor.descendAt = -1;
        floor.building = true;
        buildFloor(instance, floor.index + 1, next -> {
            floor.building = false;
            if (instance.ended) {
                if (next != null) {
                    clearEntities(next);
                }
                return;
            }
            if (next == null) {
                broadcast(instance, "&c다음 층을 만들지 못했습니다. 나가기 발판으로 나가거나 관리자에게 문의하세요.");
                return;
            }
            instance.floors.add(next);
            clearEntities(floor);
            for (UUID id : instance.members.keySet()) {
                Player player = Bukkit.getPlayer(id);
                if (player != null && !player.isDead()) {
                    player.closeInventory();
                    move(player, next.start);
                }
            }
            announceFloor(instance);
        });
    }

    private void clearEntities(Floor floor) {
        for (PlacedRoom room : floor.rooms) {
            int minChunkX = room.origin.x() >> 4;
            int maxChunkX = (room.origin.x() + room.sizeX()) >> 4;
            int minChunkZ = room.origin.z() >> 4;
            int maxChunkZ = (room.origin.z() + room.sizeZ()) >> 4;
            for (int x = minChunkX; x <= maxChunkX; x++) {
                for (int z = minChunkZ; z <= maxChunkZ; z++) {
                    if (world.isChunkLoaded(x, z)) {
                        for (Entity entity : world.getChunkAt(x, z).getEntities()) {
                            if (!(entity instanceof Player)) {
                                entity.remove();
                            }
                        }
                    }
                }
            }
        }
    }

    private void forOnline(DungeonInstance instance, Consumer<Player> action) {
        for (UUID id : new ArrayList<>(instance.members.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                action.accept(player);
            }
        }
    }

    void broadcast(DungeonInstance instance, String message) {
        forOnline(instance, player -> player.sendMessage(DungeonPlugin.color("&6[던전] &f" + message)));
    }

    private void tell(List<UUID> team, String message) {
        for (UUID id : team) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                player.sendMessage(DungeonPlugin.color("&6[던전] &f" + message));
            }
        }
    }

    private Location spectatorTarget(DungeonInstance instance) {
        for (Map.Entry<UUID, Member> entry : instance.members.entrySet()) {
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player != null && !entry.getValue().spectator && !player.isDead()
                    && instance.floor().contains(player.getLocation())) {
                return player.getLocation();
            }
        }
        return instance.floor().start;
    }

    // ---- 퇴장 ----

    private void move(Player player, Location location) {
        internalTeleport = true;
        try {
            player.teleport(location);
        } finally {
            internalTeleport = false;
        }
    }

    /** 온라인 플레이어를 던전에서 빼고 입장 전 상태로 돌려보낸다. 아이템은 건드리지 않는다. */
    private void leaveSafely(DungeonInstance instance, Player player, String message) {
        UUID id = player.getUniqueId();
        instance.members.remove(id);
        byPlayer.remove(id);
        player.closeInventory();
        restore(player);
        player.sendMessage(DungeonPlugin.color("&6[던전] &f" + message));
    }

    private void restore(Player player) {
        UUID id = player.getUniqueId();
        Session session = sessions.get(id);
        if (session == null) {
            return;
        }
        Location target = session.returnTo != null && session.returnTo.getWorld() != null
                ? session.returnTo : Bukkit.getWorlds().getFirst().getSpawnLocation();
        events.removeDungeonOnly(player);
        player.setGameMode(session.gameMode);
        move(player, target);
        sessions.remove(id);
    }

    /** 살아 있는 사람이 없으면 던전을 끝낸다. 접속이 끊겼어도 복귀 시간 안이면 살아 있는 사람으로 친다. */
    private void checkEnd(DungeonInstance instance) {
        if (!instance.ended && instance.aliveCount() == 0) {
            end(instance, "&c살아 있는 파티원이 없어 던전이 끝났습니다.");
        }
    }

    /** 던전을 없애고 남은 사람을 내보낸다. 접속이 끊긴 사람은 다음 접속 때 돌아간다. */
    void end(DungeonInstance instance, String message) {
        instance.ended = true;
        instances.remove(instance.id);
        for (UUID id : new ArrayList<>(instance.members.keySet())) {
            byPlayer.remove(id);
            Player player = Bukkit.getPlayer(id);
            if (player != null) {
                if (player.isDead()) {
                    player.spigot().respawn();
                }
                player.closeInventory();
                restore(player);
                player.sendMessage(DungeonPlugin.color("&6[던전] &f" + message));
            }
        }
        instance.members.clear();
        if (!instance.floors.isEmpty()) {
            clearEntities(instance.floor());
        }
    }

    void endAll() {
        for (DungeonInstance instance : new ArrayList<>(instances.values())) {
            end(instance, "&e서버 사정으로 던전이 종료되어 아이템을 잃지 않고 돌아왔습니다.");
        }
        sessions.save();
    }

    // ---- 사망 ----

    /** 귀속 저주가 없는 아이템을 모두 지운다. 열어 둔 창의 아이템까지 인벤토리로 돌아온 뒤에 처리한다. */
    static void stripItems(Player player) {
        player.closeInventory();
        player.setItemOnCursor(null);
        PlayerInventory inventory = player.getInventory();
        ItemStack[] contents = inventory.getContents();
        for (int i = 0; i < contents.length; i++) {
            ItemStack item = contents[i];
            if (item != null && !item.containsEnchantment(Enchantment.BINDING_CURSE)) {
                inventory.setItem(i, null);
            }
        }
    }

    boolean isActiveMember(Player player) {
        DungeonInstance instance = byPlayer.get(player.getUniqueId());
        if (instance == null) {
            return false;
        }
        Member member = instance.members.get(player.getUniqueId());
        return member != null && !member.spectator;
    }

    /** 사망 이벤트에서 부른다. 아이템 삭제 후 관전자로 바꾼다. */
    void onMemberDeath(Player player) {
        DungeonInstance instance = byPlayer.get(player.getUniqueId());
        Member member = instance.members.get(player.getUniqueId());
        member.spectator = true;
        stripItems(player);
        broadcast(instance, "&c" + player.getName() + " 님이 쓰러졌습니다. 관전으로 전환합니다.");
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline() && player.isDead()) {
                player.spigot().respawn();
            }
            if (player.isOnline() && instance.members.containsKey(player.getUniqueId())) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            checkEnd(instance);
        });
    }

    /** 리스폰 위치. 던전과 관계없는 사람이면 null이다. */
    Location respawnLocation(Player player) {
        DungeonInstance instance = byPlayer.get(player.getUniqueId());
        if (instance != null) {
            return spectatorTarget(instance);
        }
        Session session = sessions.get(player.getUniqueId());
        return session != null ? session.returnTo : null;
    }

    // ---- 접속 ----

    void onQuit(Player player) {
        UUID id = player.getUniqueId();
        DungeonInstance instance = byPlayer.get(id);
        if (instance == null) {
            parties.leave(id);
            return;
        }
        Member member = instance.members.get(id);
        if (member != null) {
            member.offlineSince = System.currentTimeMillis();
            broadcast(instance, "&7" + player.getName() + " 님의 접속이 끊겼습니다.");
        }
    }

    /** 접속 다음 틱에 부른다. 복귀, 시간 초과 사망 처리, 끝난 던전에서의 귀환을 처리한다. */
    void onJoin(Player player) {
        UUID id = player.getUniqueId();
        Session session = sessions.get(id);
        if (session == null) {
            if (player.getWorld().equals(world) && !player.hasPermission("dungeon.admin")) {
                move(player, Bukkit.getWorlds().getFirst().getSpawnLocation());
            }
            return;
        }
        if (player.isDead()) {
            player.spigot().respawn();
        }
        DungeonInstance instance = byPlayer.get(id);
        if (instance != null) {
            Member member = instance.members.get(id);
            member.offlineSince = -1;
            if (member.spectator) {
                player.setGameMode(GameMode.SPECTATOR);
            }
            if (!player.getWorld().equals(world) || !instance.floor().contains(player.getLocation())) {
                move(player, member.spectator ? spectatorTarget(instance) : instance.floor().start);
            }
            broadcast(instance, "&a" + player.getName() + " 님이 돌아왔습니다.");
            return;
        }
        if (session.penalty) {
            stripItems(player);
            player.sendMessage(DungeonPlugin.color("&6[던전] &c시간 안에 돌아오지 않아 사망 처리되었습니다. 귀속 저주가 없는 아이템을 잃었습니다."));
        } else {
            player.sendMessage(DungeonPlugin.color("&6[던전] &f던전이 끝나 원래 자리로 돌아왔습니다."));
        }
        restore(player);
    }

    // ---- 보상 ----

    /** 우클릭을 플러그인이 처리하는 블록인지 본다. 보조 손 이벤트가 바닐라 동작을 일으키지 않게 막을 때 쓴다. */
    boolean isHandledBlock(Player player, Block block) {
        DungeonInstance instance = byPlayer.get(player.getUniqueId());
        if (instance == null) {
            return false;
        }
        Floor floor = instance.floor();
        return events.isSpot(floor, block) || (floor.chest != null && floor.chest.getBlock().equals(block));
    }

    /** 보상 상자 칸이면 처리하고 true를 돌려준다. */
    boolean openRewardChest(Player player, Block block) {
        DungeonInstance instance = byPlayer.get(player.getUniqueId());
        if (instance == null) {
            return false;
        }
        Floor floor = instance.floor();
        if (floor.chest == null || !floor.bossDead || !floor.chest.getBlock().equals(block)) {
            return events.useSpot(instance, floor, player, block);
        }
        if (!floor.rewardable.contains(player.getUniqueId())) {
            player.sendMessage(DungeonPlugin.color("&6[던전] &7보스가 쓰러질 때 살아 있던 사람만 보상을 받습니다."));
            return true;
        }
        Inventory inventory = floor.rewards.computeIfAbsent(player.getUniqueId(), ignored -> rollRewards(instance, floor));
        player.openInventory(inventory);
        return true;
    }

    private Inventory rollRewards(DungeonInstance instance, Floor floor) {
        DungeonInstance.RewardHolder holder = new DungeonInstance.RewardHolder();
        Inventory inventory = Bukkit.createInventory(holder, REWARD_SLOTS,
                DungeonPlugin.color("&8" + instance.def.name + " " + (floor.index + 1) + "층 보상"));
        holder.bind(inventory);
        for (Reward reward : floor.def.rewards) {
            if (ThreadLocalRandom.current().nextDouble(100) < reward.chance()) {
                inventory.addItem(reward.item().clone());
            }
        }
        return inventory;
    }
}
