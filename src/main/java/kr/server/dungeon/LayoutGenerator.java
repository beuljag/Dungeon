package kr.server.dungeon;

import kr.server.dungeon.RoomTemplate.Dir;
import kr.server.dungeon.RoomTemplate.Door;
import kr.server.dungeon.RoomTemplate.Point;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/** 시작 방에서 문을 따라 방을 붙여 나가고, 가장 깊은 곳에 보스 방을 둔다. */
final class LayoutGenerator {

    private static final int ATTEMPTS = 200;
    /** 층 중심에서 방이 벗어날 수 없는 가로 거리. 칸(DungeonManager.CELL_SIZE)을 넘지 않게 한다. */
    static final int MAX_RADIUS = 400;
    private static final int MIN_Y = 0;
    private static final int MAX_Y = 300;

    private record OpenDoor(PlacedRoom room, int index) {
    }

    private LayoutGenerator() {
    }

    /**
     * 배치에 성공하면 방 목록(첫 번째가 시작 방, 마지막이 보스 방)을, 실패하면 null을 돌려준다.
     * specials(이벤트, 상점 방)는 남은 문에 붙여 막다른 방으로만 쓴다. 보스까지 가는 길에 끼지 않으므로
     * 웨이브 방의 문이 닫혀도 층이 막히지 않는다. 붙일 자리가 없으면 specials 없이 만든다.
     */
    static List<PlacedRoom> generate(List<RoomTemplate> starts, List<RoomTemplate> normals, List<RoomTemplate> bosses,
                                     List<RoomTemplate> specials, int normalCount, Point center, Random random) {
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            List<PlacedRoom> rooms = tryOnce(starts, normals, bosses, specials, normalCount, center, random);
            if (rooms != null) {
                return rooms;
            }
        }
        return specials.isEmpty() ? null : generate(starts, normals, bosses, List.of(), normalCount, center, random);
    }

    private static List<PlacedRoom> tryOnce(List<RoomTemplate> starts, List<RoomTemplate> normals,
                                            List<RoomTemplate> bosses, List<RoomTemplate> specials,
                                            int normalCount, Point center, Random random) {
        RoomTemplate start = starts.get(random.nextInt(starts.size()));
        List<PlacedRoom> rooms = new ArrayList<>();
        List<OpenDoor> open = new ArrayList<>();
        PlacedRoom first = new PlacedRoom(start, random.nextInt(4), center, 0);
        rooms.add(first);
        addOpenDoors(first, -1, open);

        for (int placed = 0; placed < normalCount; placed++) {
            Collections.shuffle(open, random);
            if (!attach(rooms, open, normals, true, center, random)) {
                return null;
            }
        }
        open.sort(Comparator.comparingInt((OpenDoor door) -> door.room().depth).reversed());
        if (!attach(rooms, open, bosses, true, center, random)) {
            return null;
        }
        PlacedRoom boss = rooms.removeLast();
        // 보스 방의 남은 문에 이벤트 방이 붙어 보스 방을 지나가게 되지 않도록 뺀다.
        open.removeIf(door -> door.room() == boss);
        for (RoomTemplate special : specials) {
            Collections.shuffle(open, random);
            if (!attach(rooms, open, List.of(special), false, center, random, boss)) {
                return null;
            }
        }
        rooms.add(boss);
        return rooms;
    }

    private static boolean attach(List<PlacedRoom> rooms, List<OpenDoor> open, List<RoomTemplate> candidates,
                                  boolean extendable, Point center, Random random) {
        return attach(rooms, open, candidates, extendable, center, random, null);
    }

    /**
     * 열린 문 하나에 후보 방 하나를 붙인다. 앞쪽 문부터 시도한다.
     * extendable이 false면 새 방의 문을 열린 문 목록에 넣지 않는다. alsoAvoid는 rooms 밖에서 겹치면 안 되는 방이다.
     */
    private static boolean attach(List<PlacedRoom> rooms, List<OpenDoor> open, List<RoomTemplate> candidates,
                                  boolean extendable, Point center, Random random, PlacedRoom alsoAvoid) {
        for (OpenDoor from : new ArrayList<>(open)) {
            Door fromDoor = from.room().template.doors().get(from.index());
            Dir outward = from.room().doorDir(fromDoor);
            Point fromPos = from.room().toWorld(fromDoor.pos());

            List<RoomTemplate> shuffled = new ArrayList<>(candidates);
            Collections.shuffle(shuffled, random);
            for (RoomTemplate candidate : shuffled) {
                int firstTurn = random.nextInt(4);
                for (int turn = 0; turn < 4; turn++) {
                    int rotation = (firstTurn + turn) % 4;
                    List<Door> doors = candidate.doors();
                    int firstDoor = random.nextInt(doors.size());
                    for (int d = 0; d < doors.size(); d++) {
                        int index = (firstDoor + d) % doors.size();
                        Door door = doors.get(index);
                        if (door.width() != fromDoor.width() || door.height() != fromDoor.height()
                                || door.dir().rotated(rotation) != outward.opposite()) {
                            continue;
                        }
                        Point local = PlacedRoom.rotateLocal(door.pos(), rotation, candidate.sizeX(), candidate.sizeZ());
                        Point origin = new Point(fromPos.x() + outward.dx - local.x(), fromPos.y() - local.y(),
                                fromPos.z() + outward.dz - local.z());
                        if (!fits(rooms, candidate, rotation, origin, center)
                                || (alsoAvoid != null && alsoAvoid.overlaps(candidate, rotation, origin))) {
                            continue;
                        }
                        PlacedRoom room = new PlacedRoom(candidate, rotation, origin, from.room().depth + 1);
                        from.room().doorUsed[from.index()] = true;
                        room.doorUsed[index] = true;
                        rooms.add(room);
                        open.remove(from);
                        if (extendable) {
                            addOpenDoors(room, index, open);
                        }
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static void addOpenDoors(PlacedRoom room, int except, List<OpenDoor> open) {
        for (int i = 0; i < room.template.doors().size(); i++) {
            if (i != except) {
                open.add(new OpenDoor(room, i));
            }
        }
    }

    private static boolean fits(List<PlacedRoom> rooms, RoomTemplate candidate, int rotation, Point origin, Point center) {
        int sizeX = rotation % 2 == 0 ? candidate.sizeX() : candidate.sizeZ();
        int sizeZ = rotation % 2 == 0 ? candidate.sizeZ() : candidate.sizeX();
        if (origin.y() < MIN_Y || origin.y() + candidate.sizeY() > MAX_Y
                || origin.x() < center.x() - MAX_RADIUS || origin.x() + sizeX > center.x() + MAX_RADIUS
                || origin.z() < center.z() - MAX_RADIUS || origin.z() + sizeZ > center.z() + MAX_RADIUS) {
            return false;
        }
        for (PlacedRoom room : rooms) {
            if (room.overlaps(candidate, rotation, origin)) {
                return false;
            }
        }
        return true;
    }
}
