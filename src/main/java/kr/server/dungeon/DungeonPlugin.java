package kr.server.dungeon;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class DungeonPlugin extends JavaPlugin {

    private DungeonData data;
    private RoomLibrary rooms;
    private SessionStore sessions;
    private PartyManager parties;
    private DungeonManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        data = new DungeonData(this);
        data.load();
        rooms = new RoomLibrary(this);
        rooms.load();
        sessions = new SessionStore(this);
        sessions.load();
        parties = new PartyManager();

        manager = new DungeonManager(this, data, rooms, sessions, parties);
        manager.prepareWorld();
        getServer().getPluginManager().registerEvents(new DungeonListener(this, manager, sessions, parties), this);
        getServer().getScheduler().runTaskTimer(this, manager, 10L, 10L);

        DungeonCommand command = new DungeonCommand(this, data, rooms, parties, manager);
        PluginCommand registered = getCommand("dungeon");
        registered.setExecutor(command);
        registered.setTabCompleter(command);

        getLogger().info("던전 " + data.dungeons().size() + "개, 방 " + rooms.rooms().size() + "개를 불러왔습니다.");
    }

    @Override
    public void onDisable() {
        if (manager != null) {
            manager.endAll();
        }
    }

    void reload() {
        reloadConfig();
        data.load();
        rooms.load();
    }

    static Component color(String text) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
    }
}
