package kr.server.dungeon;

import java.util.List;
import java.util.Map;

/** 저장된 방 하나. 좌표는 모두 방의 최소 모서리 기준이다. */
record RoomTemplate(String name, RoomType type, String theme, int sizeX, int sizeY, int sizeZ,
                    List<Door> doors, Map<MarkerType, List<Point>> markers) {

    enum RoomType {
        START("시작"), NORMAL("일반"), BOSS("보스");

        final String label;

        RoomType(String label) {
            this.label = label;
        }

        static RoomType parse(String text) {
            for (RoomType type : values()) {
                if (type.label.equals(text) || type.name().equalsIgnoreCase(text)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** 방을 저장할 때 표지판 첫 줄로 찾는 표시. */
    enum MarkerType {
        PLAYER_START("[시작]"), SPAWN("[몹]"), BOSS("[보스]"), REWARD("[보상]"), NEXT("[다음층]"), EXIT("[나가기]");

        final String text;

        MarkerType(String text) {
            this.text = text;
        }

        static MarkerType parse(String text) {
            for (MarkerType type : values()) {
                if (type.text.equals(text)) {
                    return type;
                }
            }
            return null;
        }
    }

    /** 순서가 시계 방향이어서 ordinal에 회전 수를 더하면 회전한 방향이 된다. */
    enum Dir {
        NORTH(0, -1), EAST(1, 0), SOUTH(0, 1), WEST(-1, 0);

        final int dx;
        final int dz;

        Dir(int dx, int dz) {
            this.dx = dx;
            this.dz = dz;
        }

        Dir rotated(int quarterTurns) {
            return values()[(ordinal() + quarterTurns) % 4];
        }

        Dir opposite() {
            return rotated(2);
        }
    }

    record Point(int x, int y, int z) {
    }

    /** pos는 문 바닥 가운데 칸이고 dir은 방 바깥쪽 방향이다. 너비는 홀수만 허용한다. */
    record Door(Point pos, Dir dir, int width, int height) {
    }

    List<Point> marker(MarkerType type) {
        return markers.getOrDefault(type, List.of());
    }
}
