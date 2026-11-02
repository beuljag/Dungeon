package kr.server.dungeon;

import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 던전에 들어간 플레이어의 복귀 정보. 입장 즉시 파일에 남겨서, 서버가 꺼져도 다음 접속 때 원래 자리로 돌려보낼 수 있다.
 */
final class SessionStore {

    static final class Session {
        final Location returnTo;
        final GameMode gameMode;
        /** 복귀 시간을 넘겨서 다음 접속 때 사망 처리해야 한다. */
        boolean penalty;

        Session(Location returnTo, GameMode gameMode, boolean penalty) {
            this.returnTo = returnTo;
            this.gameMode = gameMode;
            this.penalty = penalty;
        }
    }

    private final DungeonPlugin plugin;
    private final File file;
    private final Map<UUID, Session> sessions = new HashMap<>();

    SessionStore(DungeonPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "sessions.yml");
    }

    void load() {
        sessions.clear();
        ConfigurationSection root = YamlConfiguration.loadConfiguration(file).getConfigurationSection("sessions");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection section = root.getConfigurationSection(key);
            sessions.put(UUID.fromString(key), new Session(section.getLocation("return"),
                    GameMode.valueOf(section.getString("game-mode", "SURVIVAL")), section.getBoolean("penalty")));
        }
    }

    void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        sessions.forEach((id, session) -> {
            String path = "sessions." + id;
            yaml.set(path + ".return", session.returnTo);
            yaml.set(path + ".game-mode", session.gameMode.name());
            yaml.set(path + ".penalty", session.penalty);
        });
        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("sessions.yml 저장 실패: " + e);
        }
    }

    Session get(UUID id) {
        return sessions.get(id);
    }

    void put(UUID id, Session session) {
        sessions.put(id, session);
        save();
    }

    void remove(UUID id) {
        if (sessions.remove(id) != null) {
            save();
        }
    }
}
