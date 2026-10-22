package tritium.ncm.music.lyrics;

import java.util.ArrayList;
import java.util.List;

/** Folia's default source order, with the user's preference moved to the front. */
public enum LyricSource {
    NETEASE("netease"), AMLL("amll"), QQ("qq"), KUGOU("kugou");

    private final String id;

    LyricSource(String id) { this.id = id; }
    public String id() { return id; }

    public static LyricSource fromId(String id) {
        for (var source : values()) if (source.id.equals(id)) return source;
        return NETEASE;
    }

    public static List<LyricSource> order(LyricSource preferred) {
        var order = new ArrayList<LyricSource>();
        order.add(preferred == null ? NETEASE : preferred);
        for (var source : values()) if (!order.contains(source)) order.add(source);
        return List.copyOf(order);
    }
}
