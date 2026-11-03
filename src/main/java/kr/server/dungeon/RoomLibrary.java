package kr.server.dungeon;

import com.sk89q.worldedit.EditSession;
import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.WorldEdit;
import com.sk89q.worldedit.WorldEditException;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.extent.clipboard.BlockArrayClipboard;
import com.sk89q.worldedit.extent.clipboard.Clipboard;
import com.sk89q.worldedit.extent.clipboard.io.BuiltInClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormat;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardFormats;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardReader;
import com.sk89q.worldedit.extent.clipboard.io.ClipboardWriter;
import com.sk89q.worldedit.function.operation.ForwardExtentCopy;
import com.sk89q.worldedit.function.operation.Operations;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.math.transform.AffineTransform;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.session.ClipboardHolder;
import com.sk89q.worldedit.util.SideEffect;
import com.sk89q.worldedit.util.SideEffectSet;
import kr.server.dungeon.RoomTemplate.Dir;
import kr.server.dungeon.RoomTemplate.Door;
import kr.server.dungeon.RoomTemplate.MarkerType;
import kr.server.dungeon.RoomTemplate.Point;
import kr.server.dungeon.RoomTemplate.RoomType;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** 방을 스키매틱과 표시 정보로 저장하고, 층을 지을 때 붙여 넣는다. */
final class RoomLibrary {

    private static final int MAX_ROOM_SIZE = 128;
    private static final int DEFAULT_DOOR_WIDTH = 3;
    private static final int DEFAULT_DOOR_HEIGHT = 3;
    static final String DOOR_TEXT = "[문]";

    private final DungeonPlugin plugin;
    private final File folder;
    private final File indexFile;
    private final Map<String, RoomTemplate> rooms = new TreeMap<>();
    private final Map<String, Clipboard> clipboards = new HashMap<>();

    RoomLibrary(DungeonPlugin plugin) {
        this.plugin = plugin;
        this.folder = new File(plugin.getDataFolder(), "rooms");
        this.indexFile = new File(plugin.getDataFolder(), "rooms.yml");
    }

    Map<String, RoomTemplate> rooms() {
        return rooms;
    }

    List<RoomTemplate> byType(RoomType type, String theme) {
        return rooms.values().stream()
                .filter(room -> room.type() == type && room.theme().equals(theme))
                .toList();
    }

    void load() {
        rooms.clear();
        clipboards.clear();
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(indexFile);
        ConfigurationSection section = yaml.getConfigurationSection("rooms");
        if (section == null) {
            return;
        }
        for (String name : section.getKeys(false)) {
            ConfigurationSection room = section.getConfigurationSection(name);
            RoomType type = RoomType.parse(room.getString("type", ""));
            if (type == null || !schematicFile(name).isFile()) {
                plugin.getLogger().warning("방 '" + name + "'을(를) 건너뜁니다: 종류가 잘못되었거나 스키매틱이 없습니다.");
                continue;
            }
            List<Door> doors = new ArrayList<>();
            for (String text : room.getStringList("doors")) {
                String[] part = text.split(",");
                doors.add(new Door(new Point(Integer.parseInt(part[0]), Integer.parseInt(part[1]), Integer.parseInt(part[2])),
                        Dir.valueOf(part[3]), Integer.parseInt(part[4]), Integer.parseInt(part[5])));
            }
            Map<MarkerType, List<Point>> markers = new EnumMap<>(MarkerType.class);
            ConfigurationSection markerSection = room.getConfigurationSection("markers");
            if (markerSection != null) {
                for (String key : markerSection.getKeys(false)) {
                    List<Point> points = new ArrayList<>();
                    for (String text : markerSection.getStringList(key)) {
                        String[] part = text.split(",");
                        points.add(new Point(Integer.parseInt(part[0]), Integer.parseInt(part[1]), Integer.parseInt(part[2])));
                    }
                    markers.put(MarkerType.valueOf(key), points);
                }
            }
            rooms.put(name, new RoomTemplate(name, type, room.getString("theme", DungeonData.DEFAULT_THEME),
                    room.getInt("size-x"), room.getInt("size-y"), room.getInt("size-z"), doors, markers));
        }
    }

    private void saveIndex() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (RoomTemplate room : rooms.values()) {
            String path = "rooms." + room.name();
            yaml.set(path + ".type", room.type().name());
            yaml.set(path + ".theme", room.theme());
            yaml.set(path + ".size-x", room.sizeX());
            yaml.set(path + ".size-y", room.sizeY());
            yaml.set(path + ".size-z", room.sizeZ());
            List<String> doors = new ArrayList<>();
            for (Door door : room.doors()) {
                doors.add(text(door.pos()) + "," + door.dir() + "," + door.width() + "," + door.height());
            }
            yaml.set(path + ".doors", doors);
            room.markers().forEach((type, points) ->
                    yaml.set(path + ".markers." + type.name(), points.stream().map(RoomLibrary::text).toList()));
        }
        yaml.save(indexFile);
    }

    private static String text(Point point) {
        return point.x() + "," + point.y() + "," + point.z();
    }

    private File schematicFile(String name) {
        return new File(folder, name + ".schem");
    }

    /** 플레이어의 WorldEdit 선택 영역을 방으로 저장한다. 문제가 있으면 저장하지 않고 이유 목록을 돌려준다. */
    List<String> saveFromSelection(Player player, String name, RoomType type, String theme) throws IOException {
        List<String> problems = new ArrayList<>();
        com.sk89q.worldedit.entity.Player actor = BukkitAdapter.adapt(player);
        Region region;
        try {
            region = WorldEdit.getInstance().getSessionManager().get(actor).getSelection(actor.getWorld());
        } catch (IncompleteRegionException e) {
            return List.of("WorldEdit로 방 영역을 먼저 선택하세요. (//wand 또는 //pos1, //pos2)");
        }
        BlockVector3 min = region.getMinimumPoint();
        BlockVector3 max = region.getMaximumPoint();
        int sizeX = max.x() - min.x() + 1;
        int sizeY = max.y() - min.y() + 1;
        int sizeZ = max.z() - min.z() + 1;
        if (sizeX > MAX_ROOM_SIZE || sizeY > MAX_ROOM_SIZE || sizeZ > MAX_ROOM_SIZE) {
            return List.of("방 크기는 한 변이 " + MAX_ROOM_SIZE + "칸 이하여야 합니다. 지금: " + sizeX + "x" + sizeY + "x" + sizeZ);
        }

        List<Door> doors = new ArrayList<>();
        Map<MarkerType, List<Point>> markers = new EnumMap<>(MarkerType.class);
        World world = player.getWorld();
        for (int x = min.x(); x <= max.x(); x++) {
            for (int y = min.y(); y <= max.y(); y++) {
                for (int z = min.z(); z <= max.z(); z++) {
                    Block block = world.getBlockAt(x, y, z);
                    if (!Material.AIR.equals(block.getType()) && block.getState(false) instanceof Sign sign) {
                        readSign(sign, min, max, doors, markers, problems);
                    }
                }
            }
        }
        checkRequired(type, doors, markers, problems);
        if (!problems.isEmpty()) {
            return problems;
        }

        BlockArrayClipboard clipboard = new BlockArrayClipboard(region);
        clipboard.setOrigin(min);
        try (EditSession session = WorldEdit.getInstance().newEditSession(actor.getWorld())) {
            ForwardExtentCopy copy = new ForwardExtentCopy(session, region, clipboard, min);
            Operations.complete(copy);
        } catch (WorldEditException e) {
            throw new IOException(e);
        }
        if (!folder.isDirectory() && !folder.mkdirs()) {
            throw new IOException("폴더를 만들 수 없습니다: " + folder);
        }
        try (ClipboardWriter writer = BuiltInClipboardFormat.SPONGE_V3_SCHEMATIC
                .getWriter(new FileOutputStream(schematicFile(name)))) {
            writer.write(clipboard);
        }
        clipboards.remove(name);
        rooms.put(name, new RoomTemplate(name, type, theme, sizeX, sizeY, sizeZ, doors, markers));
        saveIndex();
        return List.of();
    }

    private static void readSign(Sign sign, BlockVector3 min, BlockVector3 max, List<Door> doors,
                                 Map<MarkerType, List<Point>> markers, List<String> problems) {
        String first = line(sign, 0);
        String at = "(" + sign.getX() + ", " + sign.getY() + ", " + sign.getZ() + ")";
        Point local = new Point(sign.getX() - min.x(), sign.getY() - min.y(), sign.getZ() - min.z());
        if (DOOR_TEXT.equals(first)) {
            List<Dir> faces = new ArrayList<>();
            if (sign.getX() == min.x()) faces.add(Dir.WEST);
            if (sign.getX() == max.x()) faces.add(Dir.EAST);
            if (sign.getZ() == min.z()) faces.add(Dir.NORTH);
            if (sign.getZ() == max.z()) faces.add(Dir.SOUTH);
            if (faces.size() != 1) {
                problems.add(DOOR_TEXT + " 표지판 " + at + "은(는) 선택 영역의 한쪽 벽면(모서리 제외)에 있어야 합니다.");
                return;
            }
            int width = DEFAULT_DOOR_WIDTH;
            int height = DEFAULT_DOOR_HEIGHT;
            String size = line(sign, 1);
            if (!size.isEmpty()) {
                String[] part = size.toLowerCase().split("x");
                try {
                    width = Integer.parseInt(part[0].trim());
                    height = Integer.parseInt(part[1].trim());
                } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
                    problems.add(DOOR_TEXT + " 표지판 " + at + "의 둘째 줄은 3x3처럼 너비x높이로 적으세요.");
                    return;
                }
            }
            if (width < 1 || height < 1 || width % 2 == 0) {
                problems.add(DOOR_TEXT + " 표지판 " + at + "의 너비는 홀수, 높이는 1 이상이어야 합니다.");
                return;
            }
            doors.add(new Door(local, faces.getFirst(), width, height));
            return;
        }
        MarkerType type = MarkerType.parse(first);
        if (type != null) {
            markers.computeIfAbsent(type, ignored -> new ArrayList<>()).add(local);
        }
    }

    private static String line(Sign sign, int index) {
        return ChatColor.stripColor(sign.getSide(Side.FRONT).getLine(index)).trim();
    }

    private static void checkRequired(RoomType type, List<Door> doors, Map<MarkerType, List<Point>> markers,
                                      List<String> problems) {
        if (doors.isEmpty()) {
            problems.add(DOOR_TEXT + " 표지판이 하나 이상 필요합니다.");
        }
        List<MarkerType> required = switch (type) {
            case START -> List.of(MarkerType.PLAYER_START);
            case BOSS -> List.of(MarkerType.BOSS, MarkerType.REWARD, MarkerType.NEXT, MarkerType.EXIT);
            case SHOP -> List.of(MarkerType.SHOPKEEPER);
            case NORMAL, EVENT -> List.of();
        };
        for (MarkerType marker : required) {
            if (!markers.containsKey(marker)) {
                problems.add(type.label + " 방에는 " + marker.text + " 표지판이 필요합니다.");
            }
        }
        if (type == RoomType.EVENT && !markers.containsKey(MarkerType.TREASURE) && !markers.containsKey(MarkerType.ALTAR)
                && !markers.containsKey(MarkerType.GAMBLE) && !markers.containsKey(MarkerType.WAVE)) {
            problems.add("이벤트 방에는 [보물], [제단], [도박], [웨이브] 중 하나 이상이 필요합니다.");
        }
        if (markers.containsKey(MarkerType.WAVE) && !markers.containsKey(MarkerType.SPAWN)) {
            problems.add("[웨이브] 방에는 몹이 나올 [몹] 표지판이 필요합니다.");
        }
    }

    boolean delete(String name) throws IOException {
        if (rooms.remove(name) == null) {
            return false;
        }
        clipboards.remove(name);
        File file = schematicFile(name);
        if (file.exists() && !file.delete()) {
            plugin.getLogger().warning("스키매틱을 지우지 못했습니다: " + file);
        }
        saveIndex();
        return true;
    }

    /** 회전을 적용해 방을 붙여 넣는다. 저장 시 원점을 최소 모서리로 맞췄으므로 pasteOrigin에 그대로 놓는다. */
    void paste(World world, PlacedRoom room) throws IOException, WorldEditException {
        Clipboard clipboard = clipboard(room.template.name());
        ClipboardHolder holder = new ClipboardHolder(clipboard);
        holder.setTransform(new AffineTransform().rotateY(-90.0 * room.rotation));
        Point to = room.pasteOrigin();
        try (EditSession session = WorldEdit.getInstance().newEditSession(BukkitAdapter.adapt(world))) {
            // 주변 블록 갱신을 끈다. 켜 두면 벽보다 먼저 놓인 표지판과 횃불이 아이템으로 떨어진다.
            session.setSideEffectApplier(SideEffectSet.none().with(SideEffect.LIGHTING, SideEffect.State.ON));
            Operations.complete(holder.createPaste(session)
                    .to(BlockVector3.at(to.x(), to.y(), to.z()))
                    .ignoreAirBlocks(false)
                    .build());
        }
    }

    private Clipboard clipboard(String name) throws IOException {
        Clipboard cached = clipboards.get(name);
        if (cached != null) {
            return cached;
        }
        File file = schematicFile(name);
        ClipboardFormat format = ClipboardFormats.findByFile(file);
        if (format == null) {
            throw new IOException("스키매틱 형식을 알 수 없습니다: " + file);
        }
        Clipboard clipboard;
        try (ClipboardReader reader = format.getReader(new FileInputStream(file))) {
            clipboard = reader.read();
        }
        clipboard.setOrigin(clipboard.getRegion().getMinimumPoint());
        clipboards.put(name, clipboard);
        return clipboard;
    }
}
