package kr.server.dungeon;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** dungeons.yml: 던전마다 층 수와 층별 방 수, 몹, 보스, 보상을 담는다. */
final class DungeonData {

    static final String DEFAULT_THEME = "기본";
    private static final int DEFAULT_ROOMS = 5;

    record Reward(double chance, ItemStack item) {
    }

    record ShopItem(int price, ItemStack item) {
    }

    static final class FloorDef {
        int rooms = DEFAULT_ROOMS;
        /** 일반 방과 별도로 붙이는 이벤트 방의 수 */
        int eventRooms;
        /** 이 층에 상점 방이 나올 확률(%) */
        double shopChance;
        String boss;
        /** 몹 이름과 가중치. 스폰 지점마다 가중치에 따라 하나를 고른다. */
        final Map<String, Integer> mobs = new LinkedHashMap<>();
        final List<Reward> rewards = new ArrayList<>();
        final List<Reward> treasure = new ArrayList<>();
        final List<ShopItem> shop = new ArrayList<>();
    }

    static final class DungeonDef {
        final String name;
        String theme = DEFAULT_THEME;
        final List<FloorDef> floors = new ArrayList<>();

        DungeonDef(String name) {
            this.name = name;
        }
    }

    private final File file;
    private final Map<String, DungeonDef> dungeons = new TreeMap<>();

    DungeonData(DungeonPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "dungeons.yml");
    }

    Map<String, DungeonDef> dungeons() {
        return dungeons;
    }

    void load() {
        dungeons.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("dungeons");
        if (root == null) {
            return;
        }
        for (String name : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(name);
            DungeonDef def = new DungeonDef(name);
            def.theme = section.getString("theme", DEFAULT_THEME);
            ConfigurationSection floors = section.getConfigurationSection("floors");
            if (floors != null) {
                Map<Integer, FloorDef> ordered = new TreeMap<>();
                for (String key : floors.getKeys(false)) {
                    ordered.put(Integer.parseInt(key), readFloor(floors.getConfigurationSection(key)));
                }
                def.floors.addAll(ordered.values());
            }
            dungeons.put(name, def);
        }
    }

    private static FloorDef readFloor(ConfigurationSection section) {
        FloorDef floor = new FloorDef();
        floor.rooms = section.getInt("rooms", DEFAULT_ROOMS);
        floor.eventRooms = section.getInt("event-rooms");
        floor.shopChance = section.getDouble("shop-chance");
        floor.boss = section.getString("boss");
        ConfigurationSection mobs = section.getConfigurationSection("mobs");
        if (mobs != null) {
            for (String mob : mobs.getKeys(false)) {
                floor.mobs.put(mob, Math.max(1, mobs.getInt(mob, 1)));
            }
        }
        readRewards(section.getConfigurationSection("rewards"), floor.rewards);
        readRewards(section.getConfigurationSection("treasure"), floor.treasure);
        ConfigurationSection shop = section.getConfigurationSection("shop");
        if (shop != null) {
            for (String key : shop.getKeys(false)) {
                ItemStack item = shop.getItemStack(key + ".item");
                if (item != null) {
                    floor.shop.add(new ShopItem(Math.max(0, shop.getInt(key + ".price")), item));
                }
            }
        }
        return floor;
    }

    private static void readRewards(ConfigurationSection section, List<Reward> into) {
        if (section == null) {
            return;
        }
        for (String key : section.getKeys(false)) {
            ItemStack item = section.getItemStack(key + ".item");
            if (item != null) {
                into.add(new Reward(section.getDouble(key + ".chance", 100), item));
            }
        }
    }

    void save() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        for (DungeonDef def : dungeons.values()) {
            String path = "dungeons." + def.name;
            yaml.set(path + ".theme", def.theme);
            for (int i = 0; i < def.floors.size(); i++) {
                FloorDef floor = def.floors.get(i);
                String floorPath = path + ".floors." + (i + 1);
                yaml.set(floorPath + ".rooms", floor.rooms);
                yaml.set(floorPath + ".event-rooms", floor.eventRooms);
                yaml.set(floorPath + ".shop-chance", floor.shopChance);
                yaml.set(floorPath + ".boss", floor.boss);
                floor.mobs.forEach((mob, weight) -> yaml.set(floorPath + ".mobs." + mob, weight));
                writeRewards(yaml, floorPath + ".rewards", floor.rewards);
                writeRewards(yaml, floorPath + ".treasure", floor.treasure);
                for (int r = 0; r < floor.shop.size(); r++) {
                    yaml.set(floorPath + ".shop." + r + ".price", floor.shop.get(r).price());
                    yaml.set(floorPath + ".shop." + r + ".item", floor.shop.get(r).item());
                }
            }
        }
        yaml.save(file);
    }

    private static void writeRewards(YamlConfiguration yaml, String path, List<Reward> rewards) {
        for (int r = 0; r < rewards.size(); r++) {
            yaml.set(path + "." + r + ".chance", rewards.get(r).chance());
            yaml.set(path + "." + r + ".item", rewards.get(r).item());
        }
    }
}
