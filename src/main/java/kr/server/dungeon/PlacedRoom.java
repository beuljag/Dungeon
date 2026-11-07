package kr.server.dungeon;

import kr.server.dungeon.RoomTemplate.Dir;
import kr.server.dungeon.RoomTemplate.Door;
import kr.server.dungeon.RoomTemplate.Point;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 층에 배치된 방. origin은 회전한 뒤의 최소 모서리(월드 좌표)다. */
final class PlacedRoom {

    final RoomTemplate template;
    /** 시계 방향 90도 회전 수 (0~3) */
    final int rotation;
    final Point origin;
    final int depth;
    final boolean[] doorUsed;
    boolean activated;
    /** 진행 중인 웨이브 번호(1부터). 웨이브가 진행 중이 아니면 0이다. */
    int wave;
    final Set<UUID> waveMobs = new HashSet<>();
    /** 일반 방의 문 잠금: 이 몹을 모두 잡아야 문이 열린다. */
    final Set<UUID> lockMobs = new HashSet<>();
    boolean locked;

    PlacedRoom(RoomTemplate template, int rotation, Point origin, int depth) {
        this.template = template;
        this.rotation = rotation;
        this.origin = origin;
        this.depth = depth;
        this.doorUsed = new boolean[template.doors().size()];
    }

    int sizeX() {
        return rotation % 2 == 0 ? template.sizeX() : template.sizeZ();
    }

    int sizeZ() {
        return rotation % 2 == 0 ? template.sizeZ() : template.sizeX();
    }

    /** 템플릿 좌표를 회전한 방 안의 좌표로 바꾼다. WorldEdit의 rotateY(-90 * rotation)과 같은 결과여야 한다. */
    static Point rotateLocal(Point p, int rotation, int sizeX, int sizeZ) {
        return switch (rotation) {
            case 1 -> new Point(sizeZ - 1 - p.z(), p.y(), p.x());
            case 2 -> new Point(sizeX - 1 - p.x(), p.y(), sizeZ - 1 - p.z());
            case 3 -> new Point(p.z(), p.y(), sizeX - 1 - p.x());
            default -> p;
        };
    }

    Point toWorld(Point local) {
        Point r = rotateLocal(local, rotation, template.sizeX(), template.sizeZ());
        return new Point(origin.x() + r.x(), origin.y() + r.y(), origin.z() + r.z());
    }

    Dir doorDir(Door door) {
        return door.dir().rotated(rotation);
    }

    /** 템플릿의 최소 모서리가 놓이는 월드 좌표. WorldEdit 붙여넣기의 기준점이 된다. */
    Point pasteOrigin() {
        return toWorld(new Point(0, 0, 0));
    }

    boolean contains(double x, double y, double z) {
        return x >= origin.x() && x < origin.x() + sizeX()
                && y >= origin.y() && y < origin.y() + template.sizeY()
                && z >= origin.z() && z < origin.z() + sizeZ();
    }

    /** 벽에서 inset칸 이상 안쪽에 있는지 본다. 문이 닫힐 때 문간에 끼지 않게 하는 용도다. */
    boolean containsInner(double x, double y, double z, int inset) {
        return x >= origin.x() + inset && x < origin.x() + sizeX() - inset
                && y >= origin.y() && y < origin.y() + template.sizeY()
                && z >= origin.z() + inset && z < origin.z() + sizeZ() - inset;
    }

    boolean overlaps(RoomTemplate other, int otherRotation, Point otherOrigin) {
        int otherX = otherRotation % 2 == 0 ? other.sizeX() : other.sizeZ();
        int otherZ = otherRotation % 2 == 0 ? other.sizeZ() : other.sizeX();
        return origin.x() < otherOrigin.x() + otherX && otherOrigin.x() < origin.x() + sizeX()
                && origin.y() < otherOrigin.y() + other.sizeY() && otherOrigin.y() < origin.y() + template.sizeY()
                && origin.z() < otherOrigin.z() + otherZ && otherOrigin.z() < origin.z() + sizeZ();
    }
}
