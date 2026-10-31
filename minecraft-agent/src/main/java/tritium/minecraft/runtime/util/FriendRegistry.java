package tritium.minecraft.runtime.util;

import java.util.*;

/**
 * OpenB4 Friends lookup, scoped to a session and supplied by the shared persisted configuration.
 */
public final class FriendRegistry {
    private Set<String> friends = Collections.emptySet(), enemies = Collections.emptySet();

    public void configure(String[] friends, String[] enemies) {
        this.friends = names(friends);
        this.enemies = names(enemies);
    }

    private static Set<String> names(String[] values) {
        Set<String> result = new HashSet<String>();
        if (values != null)
            for (String value : values)
                if (value != null && !value.trim().isEmpty())
                    result.add(value.trim().toLowerCase(Locale.ROOT));
        return result;
    }

    public boolean isFriend(String name) {
        return name != null && friends.contains(name.toLowerCase(Locale.ROOT));
    }

    public boolean isEnemy(String name) {
        return name != null && enemies.contains(name.toLowerCase(Locale.ROOT));
    }
}
