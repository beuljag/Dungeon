package kr.server.dungeon;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 메모리에만 있는 파티. 서버를 재시작하면 사라진다. */
final class PartyManager {

    private static final long INVITE_MILLIS = 60_000;

    static final class Party {
        /** 첫 번째가 파티장이다. */
        final List<UUID> members = new ArrayList<>();

        UUID leader() {
            return members.getFirst();
        }
    }

    private record Invite(Party party, long expiresAt) {
    }

    private final Map<UUID, Party> parties = new HashMap<>();
    private final Map<UUID, Invite> invites = new HashMap<>();

    Party get(UUID player) {
        return parties.get(player);
    }

    /** 초대한 사람이 파티가 없으면 새로 만든다. */
    void invite(UUID leader, UUID target) {
        Party party = parties.computeIfAbsent(leader, ignored -> {
            Party created = new Party();
            created.members.add(leader);
            return created;
        });
        invites.put(target, new Invite(party, System.currentTimeMillis() + INVITE_MILLIS));
    }

    /** 유효한 초대가 있으면 파티에 넣는다. 파티가 가득 차 있으면 넣지 않고 null을 돌려준다. */
    Party accept(UUID player, int maxSize) {
        Invite invite = invites.remove(player);
        if (invite == null || invite.expiresAt() < System.currentTimeMillis()
                || invite.party().members.isEmpty() || invite.party().members.size() >= maxSize) {
            return null;
        }
        invite.party().members.add(player);
        parties.put(player, invite.party());
        return invite.party();
    }

    boolean decline(UUID player) {
        return invites.remove(player) != null;
    }

    /** 나간 사람이 파티장이면 다음 사람이 이어받고, 혼자 남으면 파티가 사라진다. */
    void leave(UUID player) {
        Party party = parties.remove(player);
        if (party == null) {
            return;
        }
        party.members.remove(player);
        if (party.members.size() == 1) {
            parties.remove(party.members.getFirst());
            party.members.clear();
        }
    }
}
