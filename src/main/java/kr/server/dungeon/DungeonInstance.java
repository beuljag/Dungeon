package kr.server.dungeon;

import kr.server.dungeon.DungeonData.DungeonDef;
import kr.server.dungeon.DungeonData.FloorDef;
import kr.server.dungeon.DungeonData.ShopItem;
import kr.server.dungeon.RoomTemplate.MarkerType;
import kr.server.dungeon.RoomTemplate.Point;
import org.bukkit.Location;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 파티 하나가 진행 중인 던전 */
final class DungeonInstance {

    static final class Member {
        final String name;
        boolean spectator;
        /** 던전 재화. 던전을 나가면 Member와 함께 사라진다. */
        int coins;
        /** 접속이 끊긴 시각(밀리초). 접속 중이면 -1이다. */
        long offlineSince = -1;

        Member(String name) {
            this.name = name;
        }
    }

    /** 지어진 층 하나 */
    static final class Floor {
        final int index;
        final FloorDef def;
        final List<PlacedRoom> rooms;
        final Location start;
        double minX = Double.MAX_VALUE;
        double minZ = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE;
        double maxZ = -Double.MAX_VALUE;
        double minY = Double.MAX_VALUE;
        double maxY = -Double.MAX_VALUE;

        UUID bossId;
        Location bossSpot;
        boolean bossDead;
        Location chest;
        Location nextGate;
        Location exitGate;
        /** 보스가 죽을 때 살아 있던 사람만 보상을 받는다. */
        final Set<UUID> rewardable = new HashSet<>();
        final Map<UUID, Inventory> rewards = new HashMap<>();
        /** 내려가는 시각(밀리초). 카운트다운 중이 아니면 -1이다. */
        long descendAt = -1;
        boolean building;
        /** 잡으면 재화를 주는 몹 (일반 몹, 웨이브 몹) */
        final Set<UUID> coinMobs = new HashSet<>();
        final Set<UUID> mimics = new HashSet<>();
        /** 상인과 그 상인이 파는 물건 */
        final Map<UUID, List<ShopItem>> shopkeepers = new HashMap<>();
        /** 우클릭으로 쓰는 이벤트 블록 (보물 상자, 제단) */
        final Map<Point, EventSpot> spots = new HashMap<>();

        Floor(int index, FloorDef def, List<PlacedRoom> rooms, Location start) {
            this.index = index;
            this.def = def;
            this.rooms = rooms;
            this.start = start;
            for (PlacedRoom room : rooms) {
                for (MarkerType type : List.of(MarkerType.TREASURE, MarkerType.ALTAR, MarkerType.GAMBLE)) {
                    for (Point point : room.template.marker(type)) {
                        spots.put(room.toWorld(point), new EventSpot(type));
                    }
                }
                minX = Math.min(minX, room.origin.x());
                minY = Math.min(minY, room.origin.y());
                minZ = Math.min(minZ, room.origin.z());
                maxX = Math.max(maxX, room.origin.x() + room.sizeX());
                maxY = Math.max(maxY, room.origin.y() + room.template.sizeY());
                maxZ = Math.max(maxZ, room.origin.z() + room.sizeZ());
            }
        }

        boolean contains(Location location) {
            return location.getX() >= minX && location.getX() <= maxX
                    && location.getY() >= minY && location.getY() <= maxY
                    && location.getZ() >= minZ && location.getZ() <= maxZ;
        }
    }

    /** 보물 상자나 제단 하나의 상태 */
    static final class EventSpot {
        final MarkerType type;
        /** 보물 상자를 누군가 처음 열었는지 */
        boolean opened;
        final Map<UUID, Inventory> treasure = new HashMap<>();
        /** 플레이어별 사용 횟수 */
        final Map<UUID, Integer> uses = new HashMap<>();

        EventSpot(MarkerType type) {
            this.type = type;
        }
    }

    /** 상점 창임을 표시하고, 칸 번호로 상품을 찾을 수 있게 한다. */
    static final class ShopHolder implements InventoryHolder {
        final List<ShopItem> stock;
        private Inventory inventory;

        ShopHolder(List<ShopItem> stock) {
            this.stock = stock;
        }

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    /** 층별 보상 상자로 쓰는 개인 인벤토리임을 표시한다. */
    static final class RewardHolder implements InventoryHolder {
        private Inventory inventory;

        void bind(Inventory inventory) {
            this.inventory = inventory;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    final int id;
    final DungeonDef def;
    final Map<UUID, Member> members = new LinkedHashMap<>();
    /** 지나온 층까지 포함한 층 목록. 마지막이 현재 층이다. */
    final List<Floor> floors = new ArrayList<>();
    boolean ended;

    DungeonInstance(int id, DungeonDef def) {
        this.id = id;
        this.def = def;
    }

    Floor floor() {
        return floors.getLast();
    }

    boolean isLastFloor() {
        return floor().index >= def.floors.size() - 1;
    }

    long aliveCount() {
        return members.values().stream().filter(member -> !member.spectator).count();
    }
}
