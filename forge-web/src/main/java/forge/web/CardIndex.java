package forge.web;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * English card name -> {Scryfall id, Chinese name} lookup, loaded once from the
 * bundled {@code /card_index.json} on the classpath (works from the fat jar too).
 *
 * <p>Index format: {@code {"English Card Name": {"id":"<scryfallId>","zh":"<中文名>","face"?:"back"}}}.
 * Back-face names are separate keys; if a name appears as both a front and a back
 * face, the front-face entry wins.
 */
public final class CardIndex {
    private CardIndex() {}

    public record Entry(String id, String zh) {}

    private static volatile Map<String, Entry> index;

    public static Map<String, Entry> get() {
        Map<String, Entry> local = index;
        if (local == null) {
            synchronized (CardIndex.class) {
                local = index;
                if (local == null) {
                    local = load();
                    index = local;
                }
            }
        }
        return local;
    }

    /** Lookup by exact English card name; null if not found. */
    public static Entry lookup(String englishName) {
        if (englishName == null || englishName.isEmpty()) return null;
        return get().get(englishName);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Entry> load() {
        Map<String, Entry> m = new HashMap<>(1 << 16);
        try (InputStream in = CardIndex.class.getResourceAsStream("/card_index.json")) {
            if (in == null) {
                System.err.println("[CardIndex] /card_index.json not found on classpath; zh/img will be empty");
                return m;
            }
            String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Object root = Json.parse(text);
            if (root instanceof Map<?, ?> map) {
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String name = String.valueOf(e.getKey());
                    if (!(e.getValue() instanceof Map<?, ?> v)) continue;
                    Object id = v.get("id");
                    Object zh = v.get("zh");
                    Object face = v.get("face");
                    Entry ent = new Entry(id == null ? null : id.toString(),
                                          zh == null ? "" : zh.toString());
                    if ("back".equals(String.valueOf(face))) {
                        m.putIfAbsent(name, ent); // don't overwrite a front-face entry
                    } else {
                        m.put(name, ent);         // front-face wins
                    }
                }
            }
        } catch (Exception ex) {
            System.err.println("[CardIndex] load failed: " + ex);
        }
        System.out.println("[CardIndex] loaded " + m.size() + " card entries");
        return m;
    }
}
