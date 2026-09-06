package forge.web;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.Multiset;

import forge.card.CardType.CoreType;
import forge.card.CardType.Supertype;
import forge.card.MagicColor;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.card.CardView.CardStateView;
import forge.game.card.CounterType;
import forge.game.phase.PhaseType;
import forge.game.player.PlayerView;
import forge.game.zone.ZoneType;

/**
 * Maps a Forge {@link GameView} to the MDC browser JSON contract consumed by
 * mdc-web/battle.html. Pure and side-effect free so it can be unit-tested and
 * called from any thread that holds a consistent GameView snapshot.
 *
 * <p>Contract (see mdc-web/mock_gameview.json):
 * <pre>
 * { you, turn, priority, phase, prompt,
 *   players:[{id,name,life,handCount,libraryCount,graveyardCount,mana{W,U,B,R,G,C},battlefield:[cardRef]}],
 *   hand:[cardRef], stack:[cardRef], actions:[{id,label,cardId?}] }
 * cardRef = {id,name,zh,tapped,types[],power,toughness,counters[],img,(controller,note)}
 * </pre>
 *
 * <p>IMAGE / ZH LIMITATION: the engine identifies cards by an internal Forge id and
 * set+collector number, not by a Scryfall id, and carries no Chinese name. The mtgch
 * image URL in battle.html needs a Scryfall id, so we emit {@code img:""} and
 * {@code zh:""} for now (id = Forge card id, used only as a stable key). Resolving
 * set/collectorNumber -> Scryfall id (and English -> Chinese) is a documented TODO,
 * to be done against the MDC set_cards table outside the engine.
 */
public final class GameViewSerializer {
    private GameViewSerializer() {}

    /** Build the full contract object and serialize to a JSON string. */
    public static String serialize(GameView gv, PlayerView you, String prompt,
                                   List<Map<String, Object>> actions) {
        return Json.write(toMap(gv, you, prompt, actions));
    }

    /** Build the contract as a Map (useful for tests / inspection). */
    public static Map<String, Object> toMap(GameView gv, PlayerView you, String prompt,
                                            List<Map<String, Object>> actions) {
        List<PlayerView> players = new ArrayList<>();
        if (gv != null && gv.getPlayers() != null) {
            for (PlayerView pv : gv.getPlayers()) players.add(pv);
        }

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("you", playerId(you, players));
        root.put("turn", gv == null ? null : playerId(gv.getPlayerTurn(), players));
        root.put("priority", playerId(priorityPlayer(players), players));
        root.put("phase", phaseCode(gv == null ? null : gv.getPhase()));
        // Raw phase step (additive): lets the browser distinguish combat sub-steps
        // (declare-attackers vs declare-blockers) that phaseCode() collapses to "COMBAT".
        root.put("step", gv == null || gv.getPhase() == null ? "" : gv.getPhase().name());
        root.put("prompt", prompt == null ? "" : prompt);
        // End-of-game signals (additive): drive the browser's "next game / match over" UI.
        if (gv != null) {
            root.put("gameOver", gv.isGameOver());
            root.put("matchOver", gv.isMatchOver());
            if (gv.isGameOver()) root.put("winner", gv.getWinningPlayerName());
        }

        List<Object> playersJson = new ArrayList<>();
        for (PlayerView pv : players) {
            playersJson.add(playerToMap(pv, players));
        }
        root.put("players", playersJson);

        // Human hand (revealed to the controlling player).
        List<Object> hand = new ArrayList<>();
        if (you != null && you.getHand() != null) {
            for (CardView cv : you.getHand()) hand.add(cardRef(cv));
        }
        root.put("hand", hand);

        // Stack (top-most last, matching Forge order). Each item carries its targets
        // (card + player names) so the browser can show what each spell/ability points at.
        List<Object> stack = new ArrayList<>();
        if (gv != null && gv.getStack() != null) {
            for (forge.game.spellability.StackItemView si : gv.getStack()) {
                CardView src = si.getSourceCard();
                Map<String, Object> ref = src != null ? cardRef(src) : new LinkedHashMap<>();
                ref.put("controller", playerId(si.getActivatingPlayer(), players));
                if (si.getText() != null && !si.getText().isEmpty()) {
                    ref.put("note", si.getText());
                }
                List<Object> tgts = new ArrayList<>();
                if (si.getTargetCards() != null) {
                    for (CardView tc : si.getTargetCards()) {
                        String en = tc.getCurrentState() != null ? tc.getCurrentState().getName() : tc.getName();
                        CardImage ti = resolveImage(en, tc.getId());
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("kind", "card");
                        t.put("name", (ti.zh != null && !ti.zh.isEmpty()) ? ti.zh : (en == null ? "?" : en));
                        t.put("fid", String.valueOf(tc.getId()));
                        tgts.add(t);
                    }
                }
                if (si.getTargetPlayers() != null) {
                    for (PlayerView tp : si.getTargetPlayers()) {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("kind", "player");
                        t.put("name", tp.getName());
                        t.put("player", playerId(tp, players));
                        tgts.add(t);
                    }
                }
                if (!tgts.isEmpty()) ref.put("targets", tgts);
                stack.add(ref);
            }
        }
        root.put("stack", stack);

        root.put("actions", actions == null ? new ArrayList<>() : actions);
        return root;
    }

    private static Map<String, Object> playerToMap(PlayerView pv, List<PlayerView> players) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("id", playerId(pv, players));
        p.put("name", pv.getName());
        p.put("life", pv.getLife());
        p.put("handCount", pv.getZoneSize(ZoneType.Hand));
        p.put("libraryCount", pv.getZoneSize(ZoneType.Library));
        p.put("graveyardCount", pv.getZoneSize(ZoneType.Graveyard));
        p.put("exileCount", pv.getZoneSize(ZoneType.Exile));
        // Graveyard and exile are public zones — send their contents so the browser can
        // show them on demand (library stays count-only; hidden info is never serialized).
        p.put("graveyard", zoneRefs(pv, ZoneType.Graveyard));
        p.put("exile", zoneRefs(pv, ZoneType.Exile));

        Map<String, Object> mana = new LinkedHashMap<>();
        mana.put("W", pv.getMana(MagicColor.WHITE));
        mana.put("U", pv.getMana(MagicColor.BLUE));
        mana.put("B", pv.getMana(MagicColor.BLACK));
        mana.put("R", pv.getMana(MagicColor.RED));
        mana.put("G", pv.getMana(MagicColor.GREEN));
        mana.put("C", pv.getMana(MagicColor.COLORLESS));
        p.put("mana", mana);

        List<Object> bf = new ArrayList<>();
        if (pv.getBattlefield() != null) {
            for (CardView cv : pv.getBattlefield()) bf.add(cardRef(cv));
        }
        p.put("battlefield", bf);
        return p;
    }

    /** Serialize a public zone's cards to cardRefs (empty list if none/hidden). */
    private static List<Object> zoneRefs(PlayerView pv, ZoneType zone) {
        List<Object> out = new ArrayList<>();
        var coll = pv.getCards(zone);
        if (coll != null) for (CardView cv : coll) out.add(cardRef(cv));
        return out;
    }

    /** Build a cardRef per the contract. */
    public static Map<String, Object> cardRef(CardView cv) {
        Map<String, Object> ref = new LinkedHashMap<>();
        CardStateView st = cv.getCurrentState();

        String name = st != null ? st.getName() : cv.getName();
        // Resolve Scryfall id + Chinese name + mtgch image URLs from the bundled index.
        CardImage img = resolveImage(name, cv.getId());
        ref.put("id", img.id);
        // Forge engine id (additive): the stable integer key the browser must send back
        // as {@code cardId} for select/play, since {@code id} above may be a Scryfall id.
        ref.put("fid", String.valueOf(cv.getId()));
        ref.put("name", name == null || name.isEmpty() ? "???" : name);
        ref.put("zh", img.zh);
        ref.put("tapped", cv.isTapped());

        List<Object> types = new ArrayList<>();
        boolean isCreature = false;
        if (st != null && st.getType() != null) {
            for (Supertype s : st.getType().getSupertypes()) types.add(s.name());
            for (CoreType c : st.getType().getCoreTypes()) types.add(c.name());
            isCreature = st.getType().isCreature();
        }
        ref.put("types", types);

        // P/T only meaningful for creatures; contract uses "" otherwise.
        if (isCreature && st != null) {
            ref.put("power", String.valueOf(st.getPower()));
            ref.put("toughness", String.valueOf(st.getToughness()));
        } else {
            ref.put("power", "");
            ref.put("toughness", "");
        }

        List<Object> counters = new ArrayList<>();
        Multiset<CounterType> ctrs = cv.getCounters();
        if (ctrs != null) {
            for (Multiset.Entry<CounterType> e : ctrs.entrySet()) {
                if (e.getCount() <= 0) continue;
                Map<String, Object> cm = new LinkedHashMap<>();
                cm.put("type", counterName(e.getElement()));
                cm.put("count", e.getCount());
                counters.add(cm);
            }
        }
        ref.put("counters", counters);

        ref.put("img", img.img);       // zhs (Chinese art) first
        ref.put("imgen", img.imgen);   // sf (English) fallback for the browser's onerror
        return ref;
    }

    // ---- image / id / zh resolution (shared with decision options) ----

    /** Resolved identity for a card: Scryfall id (or Forge id fallback), zh name, image URLs. */
    public static final class CardImage {
        public final String id, zh, img, imgen;
        CardImage(String id, String zh, String img, String imgen) {
            this.id = id; this.zh = zh; this.img = img; this.imgen = imgen;
        }
    }

    /**
     * Look up {@code englishName} in the bundled index. On a hit: id = Scryfall id,
     * zh = Chinese name, img/imgen = mtgch zhs/sf URLs. On a miss: id = the Forge
     * card id (as a stable key), zh/img/imgen empty.
     */
    public static CardImage resolveImage(String englishName, int forgeId) {
        CardIndex.Entry e = CardIndex.lookup(englishName);
        String scry = (e != null && e.id() != null && !e.id().isBlank()) ? e.id() : null;
        String id = scry != null ? scry : String.valueOf(forgeId);
        String zh = e != null ? e.zh() : "";
        String imgZhs = "", imgSf = "";
        if (scry != null && scry.length() >= 2) {
            imgZhs = mtgch("zhs", scry);
            imgSf = mtgch("sf", scry);
        }
        return new CardImage(id, zh, imgZhs, imgSf);
    }

    private static String mtgch(String lang, String scryId) {
        return "https://images.mtgch.com/" + lang + "/normal/front/"
                + scryId.charAt(0) + "/" + scryId.charAt(1) + "/" + scryId + ".webp";
    }

    // ---- helpers ----

    private static String counterName(CounterType ct) {
        String n = ct.getName();
        if (n == null) return "";
        // Normalize the common P1P1 / M1M1 counters to the +1/+1 style the UI matches on.
        if (n.equalsIgnoreCase("P1P1")) return "+1/+1";
        if (n.equalsIgnoreCase("M1M1")) return "-1/-1";
        return n;
    }

    /** Stable "p1","p2",... id from position in the players list; human is whatever slot it occupies. */
    static String playerId(PlayerView pv, List<PlayerView> players) {
        if (pv == null) return null;
        for (int i = 0; i < players.size(); i++) {
            if (players.get(i) != null && players.get(i).getId() == pv.getId()) {
                return "p" + (i + 1);
            }
        }
        return "p" + (pv.getId());
    }

    private static PlayerView priorityPlayer(List<PlayerView> players) {
        for (PlayerView pv : players) {
            if (pv != null && pv.getHasPriority()) return pv;
        }
        return null;
    }

    /** Collapse Forge's granular phases into the 7-step contract vocabulary used by battle.html. */
    static String phaseCode(PhaseType phase) {
        if (phase == null) return "";
        switch (phase) {
            case UNTAP:  return "UNTAP";
            case UPKEEP: return "UPKEEP";
            case DRAW:   return "DRAW";
            case MAIN1:  return "MAIN1";
            case COMBAT_BEGIN:
            case COMBAT_DECLARE_ATTACKERS:
            case COMBAT_DECLARE_BLOCKERS:
            case COMBAT_FIRST_STRIKE_DAMAGE:
            case COMBAT_DAMAGE:
            case COMBAT_END:
                return "COMBAT";
            case MAIN2:  return "MAIN2";
            case END_OF_TURN:
            case CLEANUP:
                return "END";
            default:     return phase.name();
        }
    }
}
