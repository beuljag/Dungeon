package kr.server.dungeon;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Registry;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 몹 이름 규칙: 기본 몹은 ZOMBIE, 미믹몹은 mythic:이름 */
final class Mobs {

    private static final String MYTHIC_PREFIX = "mythic:";

    private Mobs() {
    }

    private static boolean mythicEnabled() {
        return Bukkit.getPluginManager().isPluginEnabled("MythicMobs");
    }

    private static EntityType vanilla(String name) {
        try {
            EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
            return type.isAlive() && type.isSpawnable() ? type : null;
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 입력한 이름을 저장 형식으로 바꾼다. 없는 몹이면 null이다. */
    static String normalize(String input) {
        if (input.regionMatches(true, 0, MYTHIC_PREFIX, 0, MYTHIC_PREFIX.length())) {
            String name = input.substring(MYTHIC_PREFIX.length());
            return mythicEnabled() && MythicHook.exists(name) ? MYTHIC_PREFIX + name : null;
        }
        EntityType type = vanilla(input);
        if (type != null) {
            return type.name();
        }
        return mythicEnabled() && MythicHook.exists(input) ? MYTHIC_PREFIX + input : null;
    }

    static boolean exists(String spec) {
        return spec != null && normalize(spec) != null;
    }

    static Entity spawn(String spec, Location location) {
        String normalized = normalize(spec);
        if (normalized == null) {
            return null;
        }
        Entity entity;
        if (normalized.startsWith(MYTHIC_PREFIX)) {
            entity = MythicHook.spawn(normalized.substring(MYTHIC_PREFIX.length()), location);
        } else {
            entity = location.getWorld().spawnEntity(location, EntityType.valueOf(normalized));
        }
        if (entity instanceof LivingEntity living) {
            living.setRemoveWhenFarAway(false);
        }
        return entity;
    }

    static List<String> names() {
        List<String> names = new ArrayList<>();
        Registry.ENTITY_TYPE.forEach(type -> {
            if (type != EntityType.UNKNOWN && type.isAlive() && type.isSpawnable()) {
                names.add(type.name());
            }
        });
        if (mythicEnabled()) {
            MythicHook.names().forEach(name -> names.add(MYTHIC_PREFIX + name));
        }
        return names;
    }
}
