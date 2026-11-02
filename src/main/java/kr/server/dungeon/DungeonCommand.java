package kr.server.dungeon;

import kr.server.dungeon.DungeonData.DungeonDef;
import kr.server.dungeon.DungeonData.FloorDef;
import kr.server.dungeon.DungeonData.Reward;
import kr.server.dungeon.RoomTemplate.RoomType;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

final class DungeonCommand implements TabExecutor {

    private static final String PREFIX = "&6[던전] &f";
    private static final Pattern VALID_NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,32}");
    private static final String ADMIN = "dungeon.admin";

    private final DungeonPlugin plugin;
    private final DungeonData data;
    private final RoomLibrary rooms;
    private final PartyManager parties;
    private final DungeonManager manager;

    DungeonCommand(DungeonPlugin plugin, DungeonData data, RoomLibrary rooms, PartyManager parties, DungeonManager manager) {
        this.plugin = plugin;
        this.data = data;
        this.rooms = rooms;
        this.parties = parties;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            help(sender, label);
            return true;
        }
        try {
            switch (args[0]) {
                case "입장", "enter" -> enter(sender, args);
                case "파티", "party" -> party(sender, args);
                default -> {
                    if (!sender.hasPermission(ADMIN)) {
                        help(sender, label);
                    } else {
                        admin(sender, label, args);
                    }
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("파일 저장 실패: " + e);
            send(sender, "&c파일 저장에 실패했습니다. 콘솔을 확인하세요.");
        }
        return true;
    }

    private void help(CommandSender sender, String label) {
        send(sender, "명령어 목록");
        List<String> lines = new ArrayList<>(List.of(
                "입장 <던전> &7- 던전 입장 (파티가 있으면 파티장만)",
                "파티 초대 <플레이어> | 수락 | 거절 | 나가기 | 추방 <플레이어> | 목록"));
        if (sender.hasPermission(ADMIN)) {
            lines.addAll(List.of(
                    "입장 <던전> <플레이어> &7- 대상 입장 (NPC, 콘솔용)",
                    "방 저장 <이름> <시작|일반|보스> [테마] &7- WorldEdit 선택 영역 저장",
                    "방 삭제 <이름> | 방 목록",
                    "생성 <던전> [테마] | 삭제 <던전> | 정보 <던전>",
                    "층수 <던전> <층수> | 방수 <던전> <층> <일반 방 수>",
                    "보스 <던전> <층> <몹>",
                    "몹 <던전> <층> 추가 <몹> [가중치] | 몹 <던전> <층> 제거 <몹>",
                    "보상 <던전> <층> 추가 <확률%> &7- 손에 든 아이템",
                    "보상 <던전> <층> 비우기",
                    "현황 | 배치 <번호> | 종료 <번호> | 리로드"));
        }
        for (String line : lines) {
            sender.sendMessage(DungeonPlugin.color("&e/" + label + " " + line));
        }
    }

    // ---- 입장 ----

    private void enter(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, "&c사용법: /던전 입장 <던전> [플레이어]");
            return;
        }
        DungeonDef def = data.dungeons().get(args[1]);
        if (def == null) {
            send(sender, "&c없는 던전입니다: " + args[1]);
            return;
        }
        Player target;
        if (args.length >= 3) {
            if (!sender.hasPermission(ADMIN)) {
                send(sender, "&c다른 사람을 입장시킬 권한이 없습니다.");
                return;
            }
            target = Bukkit.getPlayerExact(args[2]);
            if (target == null) {
                send(sender, "&c접속 중이 아닙니다: " + args[2]);
                return;
            }
        } else if (sender instanceof Player player) {
            if (!player.hasPermission("dungeon.enter")
                    || (!plugin.getConfig().getBoolean("allow-player-enter-command", true) && !player.hasPermission(ADMIN))) {
                send(sender, "&c입장 NPC를 통해 들어가세요.");
                return;
            }
            target = player;
        } else {
            send(sender, "&c콘솔에서는 플레이어를 지정하세요.");
            return;
        }
        String error = manager.enter(target, def);
        if (error != null) {
            send(target, "&c" + error);
            if (sender != target) {
                send(sender, "&c" + target.getName() + " 입장 실패: " + error);
            }
        }
    }

    // ---- 파티 ----

    private void party(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player) || !player.hasPermission("dungeon.party")) {
            send(sender, "&c플레이어만 쓸 수 있습니다.");
            return;
        }
        UUID id = player.getUniqueId();
        String action = args.length >= 2 ? args[1] : "목록";
        PartyManager.Party party = parties.get(id);
        switch (action) {
            case "초대" -> {
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null || target == player) {
                    send(player, "&c접속 중인 다른 플레이어를 지정하세요.");
                } else if (party != null && !party.leader().equals(id)) {
                    send(player, "&c파티장만 초대할 수 있습니다.");
                } else if (parties.get(target.getUniqueId()) != null) {
                    send(player, "&c이미 파티에 있는 플레이어입니다.");
                } else if (party != null && party.members.size() >= maxParty()) {
                    send(player, "&c파티가 가득 찼습니다. (최대 " + maxParty() + "명)");
                } else {
                    parties.invite(id, target.getUniqueId());
                    send(player, target.getName() + " 님을 초대했습니다.");
                    send(target, player.getName() + " 님의 파티 초대: &e/던전 파티 수락 &f또는 &e/던전 파티 거절 &7(60초)");
                }
            }
            case "수락" -> {
                if (party != null) {
                    send(player, "&c이미 파티에 있습니다.");
                    return;
                }
                PartyManager.Party joined = parties.accept(id, maxParty());
                if (joined == null) {
                    send(player, "&c유효한 초대가 없습니다.");
                    return;
                }
                sendToParty(joined, player.getName() + " 님이 파티에 들어왔습니다.");
            }
            case "거절" -> send(player, parties.decline(id) ? "초대를 거절했습니다." : "&c받은 초대가 없습니다.");
            case "나가기" -> {
                if (party == null) {
                    send(player, "&c파티에 있지 않습니다.");
                    return;
                }
                parties.leave(id);
                send(player, "파티에서 나왔습니다.");
                sendToParty(party, player.getName() + " 님이 파티를 나갔습니다.");
            }
            case "추방" -> {
                if (party == null || !party.leader().equals(id)) {
                    send(player, "&c파티장만 추방할 수 있습니다.");
                    return;
                }
                Player target = args.length >= 3 ? Bukkit.getPlayerExact(args[2]) : null;
                if (target == null || target == player || !party.members.contains(target.getUniqueId())) {
                    send(player, "&c접속 중인 파티원을 지정하세요.");
                    return;
                }
                parties.leave(target.getUniqueId());
                send(target, "파티에서 추방되었습니다.");
                sendToParty(party, target.getName() + " 님을 추방했습니다.");
            }
            default -> {
                if (party == null) {
                    send(player, "파티에 있지 않습니다. &e/던전 파티 초대 <플레이어>");
                    return;
                }
                StringBuilder names = new StringBuilder();
                for (UUID member : party.members) {
                    String name = Bukkit.getOfflinePlayer(member).getName();
                    names.append(names.isEmpty() ? "&e" + name + " &7(파티장)&f" : ", " + name);
                }
                send(player, "파티원: " + names);
            }
        }
    }

    private int maxParty() {
        return plugin.getConfig().getInt("max-party-size", 4);
    }

    private void sendToParty(PartyManager.Party party, String message) {
        for (UUID member : party.members) {
            Player player = Bukkit.getPlayer(member);
            if (player != null) {
                send(player, message);
            }
        }
    }

    // ---- 관리 ----

    private void admin(CommandSender sender, String label, String[] args) throws IOException {
        switch (args[0]) {
            case "방" -> room(sender, args);
            case "생성" -> create(sender, args);
            case "삭제" -> delete(sender, args);
            case "정보" -> info(sender, args);
            case "층수" -> floorCount(sender, args);
            case "방수" -> roomCount(sender, args);
            case "보스" -> boss(sender, args);
            case "몹" -> mob(sender, args);
            case "보상" -> reward(sender, args);
            case "현황" -> status(sender);
            case "배치" -> layout(sender, args);
            case "종료" -> stop(sender, args);
            case "리로드" -> {
                plugin.reload();
                send(sender, "설정을 다시 불러왔습니다. 진행 중인 던전에는 새 몹과 보상이 다음 입장부터 적용됩니다.");
            }
            default -> help(sender, label);
        }
    }

    private void room(CommandSender sender, String[] args) throws IOException {
        String action = args.length >= 2 ? args[1] : "목록";
        switch (action) {
            case "저장" -> {
                if (!(sender instanceof Player player)) {
                    send(sender, "&c게임 안에서만 쓸 수 있습니다.");
                    return;
                }
                RoomType type = args.length >= 4 ? RoomType.parse(args[3]) : null;
                if (type == null || !VALID_NAME.matcher(args[2]).matches()) {
                    send(sender, "&c사용법: /던전 방 저장 <이름> <시작|일반|보스> [테마]");
                    return;
                }
                String theme = args.length >= 5 ? args[4] : DungeonData.DEFAULT_THEME;
                List<String> problems = rooms.saveFromSelection(player, args[2], type, theme);
                if (problems.isEmpty()) {
                    RoomTemplate room = rooms.rooms().get(args[2]);
                    send(sender, "방 저장: &e" + args[2] + " &f(" + type.label + ", 테마 " + theme + ", "
                            + room.sizeX() + "x" + room.sizeY() + "x" + room.sizeZ() + ", 문 " + room.doors().size() + "개)");
                } else {
                    send(sender, "&c저장하지 못했습니다.");
                    problems.forEach(problem -> sender.sendMessage(DungeonPlugin.color("&c- " + problem)));
                }
            }
            case "삭제" -> {
                if (args.length < 3) {
                    send(sender, "&c사용법: /던전 방 삭제 <이름>");
                    return;
                }
                send(sender, rooms.delete(args[2]) ? "방을 삭제했습니다." : "&c없는 방입니다.");
            }
            default -> {
                send(sender, "방 " + rooms.rooms().size() + "개");
                for (RoomTemplate room : rooms.rooms().values()) {
                    sender.sendMessage(DungeonPlugin.color("&e" + room.name() + " &7" + room.type().label + " / " + room.theme()
                            + " / " + room.sizeX() + "x" + room.sizeY() + "x" + room.sizeZ()
                            + " / 문 " + room.doors().size() + " / 몹 지점 " + room.marker(RoomTemplate.MarkerType.SPAWN).size()));
                }
            }
        }
    }

    private void create(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2 || !VALID_NAME.matcher(args[1]).matches()) {
            send(sender, "&c사용법: /던전 생성 <던전> [테마]");
            return;
        }
        if (data.dungeons().containsKey(args[1])) {
            send(sender, "&c이미 있는 던전입니다.");
            return;
        }
        DungeonDef def = new DungeonDef(args[1]);
        if (args.length >= 3) {
            def.theme = args[2];
        }
        def.floors.add(new FloorDef());
        data.dungeons().put(args[1], def);
        data.save();
        send(sender, "던전을 만들었습니다. 다음: &e/던전 층수, /던전 보스, /던전 몹, /던전 보상");
    }

    private void delete(CommandSender sender, String[] args) throws IOException {
        if (args.length < 2 || data.dungeons().remove(args[1]) == null) {
            send(sender, "&c없는 던전입니다.");
            return;
        }
        data.save();
        send(sender, "던전을 삭제했습니다. 진행 중인 파티는 끝까지 진행합니다.");
    }

    private void info(CommandSender sender, String[] args) {
        DungeonDef def = args.length >= 2 ? data.dungeons().get(args[1]) : null;
        if (def == null) {
            send(sender, "던전: &e" + String.join(", ", data.dungeons().keySet()));
            return;
        }
        send(sender, "&e" + def.name + " &f테마 " + def.theme + ", " + def.floors.size() + "층");
        for (int i = 0; i < def.floors.size(); i++) {
            FloorDef floor = def.floors.get(i);
            sender.sendMessage(DungeonPlugin.color("&e" + (i + 1) + "층 &7일반 방 " + floor.rooms + " / 보스 " + floor.boss
                    + " / 몹 " + floor.mobs + " / 보상 " + floor.rewards.size() + "종"));
        }
        List<String> problems = manager.validate(def);
        problems.forEach(problem -> sender.sendMessage(DungeonPlugin.color("&c- " + problem)));
        if (problems.isEmpty()) {
            sender.sendMessage(DungeonPlugin.color("&a입장 가능"));
        }
    }

    private void floorCount(CommandSender sender, String[] args) throws IOException {
        DungeonDef def = args.length >= 3 ? data.dungeons().get(args[1]) : null;
        Integer count = args.length >= 3 ? parseInt(args[2]) : null;
        if (def == null || count == null || count < 1) {
            send(sender, "&c사용법: /던전 층수 <던전> <층수>");
            return;
        }
        while (def.floors.size() < count) {
            def.floors.add(new FloorDef());
        }
        while (def.floors.size() > count) {
            def.floors.removeLast();
        }
        data.save();
        send(sender, def.name + ": " + count + "층으로 설정했습니다.");
    }

    private FloorDef floor(CommandSender sender, String[] args) {
        DungeonDef def = args.length >= 3 ? data.dungeons().get(args[1]) : null;
        Integer index = args.length >= 3 ? parseInt(args[2]) : null;
        if (def == null || index == null || index < 1 || index > def.floors.size()) {
            send(sender, "&c던전 이름과 층 번호를 확인하세요.");
            return null;
        }
        return def.floors.get(index - 1);
    }

    private void roomCount(CommandSender sender, String[] args) throws IOException {
        FloorDef floor = floor(sender, args);
        Integer count = args.length >= 4 ? parseInt(args[3]) : null;
        if (floor == null) {
            return;
        }
        if (count == null || count < 0) {
            send(sender, "&c사용법: /던전 방수 <던전> <층> <일반 방 수>");
            return;
        }
        floor.rooms = count;
        data.save();
        send(sender, "일반 방 수를 " + count + "개로 설정했습니다.");
    }

    private void boss(CommandSender sender, String[] args) throws IOException {
        FloorDef floor = floor(sender, args);
        if (floor == null) {
            return;
        }
        String mob = args.length >= 4 ? Mobs.normalize(args[3]) : null;
        if (mob == null) {
            send(sender, "&c없는 몹입니다. 사용법: /던전 보스 <던전> <층> <몹>");
            return;
        }
        floor.boss = mob;
        data.save();
        send(sender, "보스를 &e" + mob + "&f(으)로 설정했습니다.");
    }

    private void mob(CommandSender sender, String[] args) throws IOException {
        FloorDef floor = floor(sender, args);
        if (floor == null) {
            return;
        }
        String action = args.length >= 4 ? args[3] : "";
        String mob = args.length >= 5 ? Mobs.normalize(args[4]) : null;
        if (action.equals("추가") && mob != null) {
            Integer weight = args.length >= 6 ? parseInt(args[5]) : Integer.valueOf(1);
            if (weight == null || weight < 1) {
                send(sender, "&c가중치는 1 이상의 정수입니다.");
                return;
            }
            floor.mobs.put(mob, weight);
            data.save();
            send(sender, "몹 추가: &e" + mob + " &7가중치 " + weight);
        } else if (action.equals("제거") && args.length >= 5) {
            String key = mob != null ? mob : args[4];
            send(sender, floor.mobs.remove(key) != null ? "몹을 제거했습니다." : "&c목록에 없는 몹입니다.");
            data.save();
        } else {
            send(sender, "&c사용법: /던전 몹 <던전> <층> 추가 <몹> [가중치] | 제거 <몹>");
        }
    }

    private void reward(CommandSender sender, String[] args) throws IOException {
        FloorDef floor = floor(sender, args);
        if (floor == null) {
            return;
        }
        String action = args.length >= 4 ? args[3] : "";
        if (action.equals("비우기")) {
            floor.rewards.clear();
            data.save();
            send(sender, "보상을 비웠습니다.");
        } else if (action.equals("추가") && sender instanceof Player player) {
            ItemStack item = player.getInventory().getItemInMainHand();
            Double chance = args.length >= 5 ? parseDouble(args[4]) : null;
            if (item.getType() == Material.AIR || chance == null || chance <= 0 || chance > 100) {
                send(sender, "&c아이템을 손에 들고 확률을 적으세요: /던전 보상 <던전> <층> 추가 <1~100>");
                return;
            }
            floor.rewards.add(new Reward(chance, item.clone()));
            data.save();
            send(sender, "보상 추가: &e" + item.getType() + " x" + item.getAmount() + " &7확률 " + chance + "%");
        } else {
            send(sender, "&c사용법: /던전 보상 <던전> <층> 추가 <확률%> | 비우기");
        }
    }

    private void status(CommandSender sender) {
        send(sender, "진행 중인 던전 " + manager.instances().size() + "개");
        manager.instances().forEach((id, instance) -> sender.sendMessage(DungeonPlugin.color(
                "&e#" + id + " &f" + instance.def.name + " " + (instance.floor().index + 1) + "층 &7"
                        + instance.members.values().stream()
                        .map(member -> member.name + (member.spectator ? "(관전)" : "")).toList())));
    }

    /** 맵이 이상할 때 확인하는 용도로, 현재 층의 방 배치를 보여 준다. */
    private void layout(CommandSender sender, String[] args) {
        Integer id = args.length >= 2 ? parseInt(args[1]) : null;
        DungeonInstance instance = id != null ? manager.instances().get(id) : null;
        if (instance == null) {
            send(sender, "&c없는 번호입니다. /던전 현황으로 확인하세요.");
            return;
        }
        for (PlacedRoom room : instance.floor().rooms) {
            sender.sendMessage(DungeonPlugin.color("&e" + room.template.name() + " &7rot " + room.rotation
                    + " origin " + room.origin.x() + " " + room.origin.y() + " " + room.origin.z()
                    + " size " + room.sizeX() + " " + room.template.sizeY() + " " + room.sizeZ()
                    + " depth " + room.depth));
        }
    }

    private void stop(CommandSender sender, String[] args) {
        Integer id = args.length >= 2 ? parseInt(args[1]) : null;
        DungeonInstance instance = id != null ? manager.instances().get(id) : null;
        if (instance == null) {
            send(sender, "&c없는 번호입니다. /던전 현황으로 확인하세요.");
            return;
        }
        manager.end(instance, "&e관리자가 던전을 종료했습니다. 아이템을 잃지 않습니다.");
        send(sender, "던전 #" + id + "을(를) 종료했습니다.");
    }

    private static Integer parseInt(String text) {
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Double parseDouble(String text) {
        try {
            return Double.valueOf(text);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void send(CommandSender sender, String message) {
        sender.sendMessage(DungeonPlugin.color(PREFIX + message));
    }

    // ---- 탭 완성 ----

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(ADMIN);
        List<String> options = new ArrayList<>();
        String first = args[0];
        if (args.length == 1) {
            options.addAll(List.of("입장", "파티"));
            if (admin) {
                options.addAll(List.of("방", "생성", "삭제", "정보", "층수", "방수", "보스", "몹", "보상", "현황", "배치", "종료", "리로드"));
            }
        } else if (first.equals("파티")) {
            if (args.length == 2) {
                options.addAll(List.of("초대", "수락", "거절", "나가기", "추방", "목록"));
            } else if (args.length == 3) {
                Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
            }
        } else if (first.equals("입장")) {
            if (args.length == 2) {
                options.addAll(data.dungeons().keySet());
            } else if (args.length == 3 && admin) {
                Bukkit.getOnlinePlayers().forEach(player -> options.add(player.getName()));
            }
        } else if (admin) {
            completeAdmin(args, options);
        }
        String last = args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(last)).limit(100).toList();
    }

    private void completeAdmin(String[] args, List<String> options) {
        String first = args[0];
        if (first.equals("방")) {
            if (args.length == 2) {
                options.addAll(List.of("저장", "삭제", "목록"));
            } else if (args.length == 3 && args[1].equals("삭제")) {
                options.addAll(rooms.rooms().keySet());
            } else if (args.length == 4 && args[1].equals("저장")) {
                options.addAll(List.of("시작", "일반", "보스"));
            }
            return;
        }
        if (args.length == 2 && List.of("삭제", "정보", "층수", "방수", "보스", "몹", "보상").contains(first)) {
            options.addAll(data.dungeons().keySet());
        } else if (args.length == 3 && List.of("방수", "보스", "몹", "보상").contains(first)) {
            DungeonDef def = data.dungeons().get(args[1]);
            for (int i = 1; def != null && i <= def.floors.size(); i++) {
                options.add(String.valueOf(i));
            }
        } else if (args.length == 4 && first.equals("보스")) {
            options.addAll(Mobs.names());
        } else if (args.length == 4 && first.equals("몹")) {
            options.addAll(List.of("추가", "제거"));
        } else if (args.length == 4 && first.equals("보상")) {
            options.addAll(List.of("추가", "비우기"));
        } else if (args.length == 5 && first.equals("몹")) {
            options.addAll(Mobs.names());
        } else if (args.length == 2 && (first.equals("종료") || first.equals("배치"))) {
            manager.instances().keySet().forEach(id -> options.add(String.valueOf(id)));
        }
    }
}
