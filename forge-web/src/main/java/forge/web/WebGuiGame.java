package forge.web;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import forge.LobbyPlayer;
import forge.deck.CardPool;
import forge.game.GameEntityView;
import forge.game.GameState;
import forge.game.GameView;
import forge.game.card.CardView;
import forge.game.player.DelayedReveal;
import forge.game.player.IHasIcon;
import forge.game.player.PlayerView;
import forge.player.PlayerZoneUpdate;
import forge.player.PlayerZoneUpdates;
import forge.game.spellability.SpellAbilityView;
import forge.game.zone.ZoneType;
import forge.gamemodes.match.AbstractGuiGame;
import forge.interfaces.IGameController;
import forge.item.PaperCard;
import forge.localinstance.skin.FSkinProp;
import forge.trackable.TrackableCollection;
import forge.util.FSerializableFunction;
import forge.util.ITriggerEvent;

/**
 * IGuiGame implementation that bridges a single human player to a browser over
 * WebSocket. Outbound: every state change rebuilds the {@link GameViewSerializer}
 * JSON contract and pushes it to the {@link Sink}. Inbound: {@link #submitAction}
 * translates a browser {id, cardId} action into {@link IGameController} calls
 * (pass priority / play / select), mirroring how RemoteClientGuiGame feeds the
 * engine from network messages.
 *
 * <p>Blocking dialog decisions (confirm/getChoices/chooseEntity/option/input/
 * ability/amount) do a real browser round-trip: the game thread parks in
 * {@link #awaitDecision} on a {@link CompletableFuture} after publishing a
 * {@code decision} object in the pushed JSON; the WS thread (or the headless
 * auto-pilot) completes the future via {@link #answerDecision} /
 * {@link #answerPendingDefault}. A null completion means "use the caller's safe
 * default", which keeps the headless SmokeTest able to finish a whole game.
 * Priority / attackers / blockers flow separately through the Input framework
 * (updateButtons/showPromptMessage/setSelectables -> submitAction). A few bulk
 * decisions (assignCombatDamage/order/sideboard/manipulateCardList) keep improved
 * default stubs, tagged {@code [WEB-TODO]}, and never block.
 */
public class WebGuiGame extends AbstractGuiGame {

    /** Receives serialized GameView JSON snapshots. */
    public interface Sink { void onState(String json); }

    private volatile Sink sink;
    private volatile PlayerView humanView;

    // Latest interaction state, folded into the "prompt"/"actions" of each push.
    private volatile String promptText = "";
    private volatile boolean okEnabled = false, cancelEnabled = false;
    private volatile String okLabel = "OK", cancelLabel = "Cancel";

    // Serializes inbound actions off the WS thread and onto a dedicated worker,
    // so engine callbacks never run on the network thread.
    private final ExecutorService inputExec =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "web-input"));

    // Monotonic id bumped whenever a fresh "your turn to act" prompt appears,
    // letting an auto-pilot client answer each prompt exactly once.
    private final AtomicInteger inputEpoch = new AtomicInteger();

    // ---- dialog-decision round-trip state ----
    /** Answer from the browser: chosen option indices and/or a raw value (number/text/CSV). */
    public record Answer(int[] picks, String value) {}

    private static final class Decision {
        final long reqId;
        final Map<String, Object> descriptor;
        final CompletableFuture<Answer> future = new CompletableFuture<>();
        Decision(long reqId, Map<String, Object> descriptor) {
            this.reqId = reqId;
            this.descriptor = descriptor;
        }
    }

    private final AtomicLong reqSeq = new AtomicLong();
    private volatile Decision pending;
    /** 0 = wait forever (default, so a real human is never cut off). >0 = ms before falling back. */
    private volatile long decisionTimeoutMs = 0;

    public void setSink(Sink s) { this.sink = s; pushState(); }
    public PlayerView getHumanView() { return humanView; }
    public boolean isOkEnabled() { return okEnabled; }
    public int getInputEpoch() { return inputEpoch.get(); }
    public void setDecisionTimeoutMs(long ms) { this.decisionTimeoutMs = ms; }
    public boolean hasPendingDecision() { return pending != null; }
    public long pendingReqId() { Decision d = pending; return d == null ? -1 : d.reqId; }

    /** First currently-selectable card id (for auto-pilot / forced selections), or null. */
    public String firstSelectableCardId() {
        for (CardView cv : selectableSnapshot()) return String.valueOf(cv.getId());
        return null;
    }

    // ------------------------------------------------------------------
    // Outbound: build + push the JSON contract
    // ------------------------------------------------------------------

    public String buildJson() {
        GameView gv = getGameView();
        PlayerView you = humanView != null ? humanView : getCurrentPlayer();
        Map<String, Object> root = GameViewSerializer.toMap(gv, you, promptText, buildActions());
        Decision d = pending;
        if (d != null) {
            root.put("decision", d.descriptor);
        }
        return Json.write(root);
    }

    // Synchronized so pushes from the game thread and the input worker never
    // interleave a sink write (keeps the JSONL / WS frame stream well-formed).
    private synchronized void pushState() {
        Sink s = sink;
        if (s == null) return;
        try {
            s.onState(buildJson());
        } catch (Exception e) {
            System.err.println("[WebGuiGame] push failed: " + e);
        }
    }

    /** Derive browser actions from the current buttons + selectable cards. */
    private List<Map<String, Object>> buildActions() {
        List<Map<String, Object>> actions = new ArrayList<>();
        if (okEnabled) {
            actions.add(action("pass", "过优先权 / OK", null));
        }
        if (cancelEnabled) {
            actions.add(action("cancel", cancelLabel == null ? "取消" : cancelLabel, null));
        }
        // Selectable cards -> play/select actions.
        PlayerView you = humanView;
        java.util.Set<Integer> handIds = new java.util.HashSet<>();
        if (you != null && you.getHand() != null) {
            for (CardView cv : you.getHand()) handIds.add(cv.getId());
        }
        for (CardView cv : selectableSnapshot()) {
            String name = cv.getCurrentState() != null ? cv.getCurrentState().getName() : cv.getName();
            if (handIds.contains(cv.getId())) {
                actions.add(action("play", "使用 " + name, String.valueOf(cv.getId())));
            } else {
                actions.add(action("select", name, String.valueOf(cv.getId())));
            }
        }
        return actions;
    }

    private static Map<String, Object> action(String id, String label, String cardId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        if (cardId != null) m.put("cardId", cardId);
        return m;
    }

    // Track selectable CardViews (AbstractGuiGame keeps the set privately, so we mirror ids).
    private final java.util.Set<Integer> selectableIds = ConcurrentHashMap.newKeySet();

    private List<CardView> selectableSnapshot() {
        List<CardView> out = new ArrayList<>();
        GameView gv = getGameView();
        if (gv == null || selectableIds.isEmpty()) return out;
        for (Integer id : selectableIds) {
            CardView cv = findCard(id);
            if (cv != null) out.add(cv);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Inbound: browser action -> engine
    // ------------------------------------------------------------------

    /** Feed a browser action into the engine. Runs asynchronously on the input worker. */
    public void submitAction(final String id, final String cardId) {
        inputExec.submit(() -> {
            try {
                doAction(id, cardId);
            } catch (Exception e) {
                System.err.println("[WebGuiGame] action '" + id + "' failed: " + e);
            }
        });
    }

    private void doAction(String id, String cardId) {
        IGameController ctrl = getGameController(humanView);
        if (ctrl == null) ctrl = getGameController();
        if (ctrl == null) {
            System.err.println("[WebGuiGame] no controller for action " + id);
            return;
        }
        switch (id == null ? "" : id) {
            case "pass": case "ok":
                ctrl.selectButtonOk();
                break;
            case "cancel": case "endturn":
                ctrl.selectButtonCancel();
                break;
            case "concede":
                ctrl.concede();
                break;
            case "play": case "select": {
                CardView cv = cardId == null ? null : findCard(Integer.parseInt(cardId));
                if (cv != null) {
                    ctrl.selectCard(cv, null, null);
                } else {
                    System.err.println("[WebGuiGame] card not found: " + cardId);
                }
                break;
            }
            default:
                System.err.println("[WebGuiGame] unknown action: " + id);
        }
    }

    /** Locate a CardView by Forge id across all visible zones + the stack. */
    private CardView findCard(int id) {
        GameView gv = getGameView();
        if (gv == null || gv.getPlayers() == null) return null;
        for (PlayerView pv : gv.getPlayers()) {
            for (ZoneType z : ZoneType.values()) {
                var coll = pv.getCards(z);
                if (coll == null) continue;
                for (CardView cv : coll) if (cv.getId() == id) return cv;
            }
        }
        if (gv.getStack() != null) {
            for (var si : gv.getStack()) {
                CardView cv = si.getSourceCard();
                if (cv != null && cv.getId() == id) return cv;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Dialog-decision round-trip (game thread parks; WS/auto-pilot answers)
    // ------------------------------------------------------------------

    /**
     * Publish a decision request and block the game thread until it is answered.
     * IMPORTANT: {@code future.get()} is called with NO lock held, and the
     * completing side ({@link #answerDecision}) takes no lock either, so there is
     * no deadlock against the {@code synchronized pushState()}.
     *
     * @return the browser's {@link Answer}, or {@code null} if it timed out or was
     *         answered with "use default" — the caller then applies a safe default.
     */
    private Answer awaitDecision(Map<String, Object> descriptor) {
        long id = reqSeq.incrementAndGet();
        descriptor.put("reqId", id);
        Decision d = new Decision(id, descriptor);
        pending = d;
        pushState(); // browser now sees root.decision
        Answer ans = null;
        try {
            long t = decisionTimeoutMs;
            ans = (t > 0) ? d.future.get(t, TimeUnit.MILLISECONDS) : d.future.get();
        } catch (Exception e) {
            // timeout / interrupt -> caller default
        } finally {
            if (pending == d) pending = null;
        }
        pushState(); // clear the decision from the view
        return ans;
    }

    /** Complete a pending decision from the browser. Safe from any thread. */
    public void answerDecision(long reqId, int[] picks, String value) {
        Decision d = pending;
        if (d != null && d.reqId == reqId) {
            d.future.complete(new Answer(picks, value));
        }
    }

    /** Inbound {id:"decide"} entry from the WS server. */
    public void submitDecision(long reqId, int[] picks, String value) {
        answerDecision(reqId, picks, value);
    }

    /** Headless auto-pilot: answer the pending decision with the caller's default. */
    public void answerPendingDefault() {
        Decision d = pending;
        if (d != null) {
            d.future.complete(null); // null Answer -> each method falls back to its default
        }
    }

    // ---- descriptor builders ----

    private Map<String, Object> baseDesc(String type, String title, String prompt,
                                         int min, int max, boolean optional, boolean numeric) {
        Map<String, Object> d = new LinkedHashMap<>();
        d.put("type", type);
        d.put("title", title == null ? "" : title);
        d.put("prompt", prompt == null ? "" : prompt);
        d.put("min", min);
        d.put("max", max);
        d.put("optional", optional);
        d.put("numeric", numeric);
        return d;
    }

    private List<Map<String, Object>> optsFromStrings(List<String> items) {
        List<Map<String, Object>> o = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idx", i);
            m.put("label", items.get(i) == null ? "" : items.get(i));
            o.add(m);
        }
        return o;
    }

    private List<Map<String, Object>> optsFrom(List<?> items, Function<Object, String> label) {
        List<Map<String, Object>> o = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Object it = items.get(i);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("idx", i);
            m.put("label", label != null ? label.apply(it) : labelFor(it));
            if (it instanceof CardView cv) {
                var s = cv.getCurrentState();
                String en = s != null ? s.getName() : cv.getName();
                GameViewSerializer.CardImage ci = GameViewSerializer.resolveImage(en, cv.getId());
                m.put("cardId", ci.id);   // Scryfall id (or Forge id) — cosmetic; picks are by idx
                m.put("img", ci.img);
                m.put("imgen", ci.imgen);
            }
            o.add(m);
        }
        return o;
    }

    private String labelFor(Object o) {
        if (o instanceof CardView cv) {
            var s = cv.getCurrentState();
            return s != null ? s.getName() : cv.getName();
        }
        if (o instanceof SpellAbilityView sa) {
            String s = sa.getDescription();
            return (s == null || s.isEmpty()) ? sa.toString() : s;
        }
        if (o instanceof GameEntityView g) {
            return g.getName();
        }
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------
    // IGuiGame / AbstractGuiGame overrides — state push
    // ------------------------------------------------------------------

    @Override
    protected void updateCurrentPlayer(PlayerView player) { pushState(); }

    @Override
    public void setGameView(GameView gameView) {
        super.setGameView(gameView);
        pushState();
    }

    @Override
    public void openView(TrackableCollection<PlayerView> myPlayers) {
        if (myPlayers != null && !myPlayers.isEmpty()) {
            humanView = myPlayers.iterator().next();
            setCurrentPlayer(humanView);
        }
        pushState();
    }

    @Override
    public void showPromptMessage(PlayerView playerView, String message, CardView card) {
        this.promptText = message == null ? "" : message;
        pushState();
    }

    @Override
    public void updateButtons(PlayerView owner, String label1, String label2,
                              boolean enable1, boolean enable2, boolean focus1) {
        this.okLabel = label1;
        this.cancelLabel = label2;
        this.okEnabled = enable1;
        this.cancelEnabled = enable2;
        if (enable1) {
            // Bump on every OK-enabled prompt (not just false->true): consecutive
            // prompts (coin toss -> keep hand -> priority ...) can keep OK enabled
            // throughout, and an auto-pilot must be able to answer each one.
            inputEpoch.incrementAndGet();
        }
        pushState();
    }

    @Override
    public void setSelectables(Iterable<CardView> cards, int min, int max) {
        super.setSelectables(cards, min, max);
        for (CardView cv : cards) selectableIds.add(cv.getId());
        inputEpoch.incrementAndGet();
        pushState();
    }

    @Override
    public void clearSelectables() {
        super.clearSelectables();
        selectableIds.clear();
        pushState();
    }

    @Override public void showCombat() { pushState(); }
    @Override public void updateStack() { pushState(); }
    @Override public void updatePhase(boolean saveState) { pushState(); }
    @Override public void updateTurn(PlayerView player) { pushState(); }
    @Override public void updateCards(Iterable<CardView> cards) { pushState(); }
    @Override public void updateManaPool(Iterable<PlayerView> manaPoolUpdate) { pushState(); }
    @Override public void updateLives(Iterable<PlayerView> livesUpdate) { pushState(); }
    @Override public void updateShards(Iterable<PlayerView> shardsUpdate) { pushState(); }
    @Override public void showManaPool(PlayerView player) { }
    @Override public void hideManaPool(PlayerView player) { }

    @Override
    public void finishGame() {
        promptText = "对局结束 / Game over";
        okEnabled = cancelEnabled = false;
        pushState();
    }

    // ------------------------------------------------------------------
    // No-op UI plumbing
    // ------------------------------------------------------------------

    @Override public void flashIncorrectAction() {}
    @Override public void alertUser() {}
    @Override public void enableOverlay() {}
    @Override public void disableOverlay() {}
    @Override public void updatePlayerControl() {}
    @Override public void updateDayTime(String daytime) {}
    @Override public GameState getGamestate() { return null; }
    @Override public void setPanelSelection(CardView hostCard) {}
    @Override public void setCard(CardView card) {}
    @Override public void setPlayerAvatar(LobbyPlayer player, IHasIcon ihi) {}
    @Override public void message(String message, String title) {
        System.out.println("[msg] " + title + ": " + message);
    }
    @Override public void showErrorDialog(String message, String title) {
        System.err.println("[err] " + title + ": " + message);
    }
    @Override public boolean isUiSetToSkipPhase(PlayerView playerTurn, forge.game.phase.PhaseType phase) { return false; }

    @Override
    public Iterable<PlayerZoneUpdate> tempShowZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) {
        return zonesToUpdate;
    }
    @Override public void hideZones(PlayerView controller, Iterable<PlayerZoneUpdate> zonesToUpdate) {}
    @Override
    public PlayerZoneUpdates openZones(PlayerView controller, Collection<ZoneType> zones,
                                       Map<PlayerView, Object> players, boolean backupLastZones) {
        return null;
    }
    @Override public void restoreOldZones(PlayerView playerView, PlayerZoneUpdates playerZoneUpdates) {}

    // ------------------------------------------------------------------
    // Dialog decisions — real browser round-trip (default fallback keeps headless alive)
    // ------------------------------------------------------------------

    private static void todo(String what) {
        System.err.println("[WEB-TODO] default-stubbed decision: " + what);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices,
                                  List<T> selected, FSerializableFunction<T, String> display) {
        List<T> res = new ArrayList<>();
        if (choices == null || choices.isEmpty() || max < 0) return res; // reveal-only or nothing to pick
        Map<String, Object> d = baseDesc("choose", message, message, Math.max(min, 0), max, min <= 0, false);
        final FSerializableFunction<T, String> disp = display;
        d.put("options", optsFrom(choices, disp != null ? (Object o) -> {
            try { @SuppressWarnings("unchecked") T t = (T) o; return disp.apply(t); }
            catch (Exception e) { return labelFor(o); }
        } : null));
        Answer a = awaitDecision(d);
        if (a != null && a.picks() != null) {
            for (int idx : a.picks()) if (idx >= 0 && idx < choices.size()) res.add(choices.get(idx));
            if (res.size() >= Math.max(min, 0)) return res;
        }
        // default: first max(min,1) items
        res.clear();
        int want = Math.max(min, 0);
        if (want == 0) want = Math.min(1, max);
        for (int i = 0; i < choices.size() && res.size() < want; i++) res.add(choices.get(i));
        return res;
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        List<String> opts = (options == null || options.isEmpty()) ? Arrays.asList("是", "否") : options;
        Map<String, Object> d = baseDesc("confirm", question, question, 1, 1, false, false);
        d.put("options", optsFromStrings(opts));
        Answer a = awaitDecision(d);
        if (a == null || a.picks() == null || a.picks().length == 0) return defaultIsYes;
        return a.picks()[0] == 0; // first option = affirmative
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText,
                                     String noButtonText, boolean defaultYes) {
        Map<String, Object> d = baseDesc("confirm", title, message, 1, 1, false, false);
        d.put("options", optsFromStrings(Arrays.asList(yesButtonText, noButtonText)));
        Answer a = awaitDecision(d);
        if (a == null || a.picks() == null || a.picks().length == 0) return defaultYes;
        return a.picks()[0] == 0;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon,
                                List<String> options, int defaultOption) {
        if (options == null || options.isEmpty()) return defaultOption;
        Map<String, Object> d = baseDesc("option", title, message, 1, 1, false, false);
        d.put("options", optsFromStrings(options));
        Answer a = awaitDecision(d);
        if (a == null || a.picks() == null || a.picks().length == 0) return defaultOption;
        int p = a.picks()[0];
        return (p >= 0 && p < options.size()) ? p : defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon,
                                  String initialInput, List<String> inputOptions, boolean isNumeric) {
        Map<String, Object> d = baseDesc("input", title, message, 0, 0, false, isNumeric);
        if (initialInput != null) d.put("value", initialInput);
        if (inputOptions != null && !inputOptions.isEmpty()) d.put("options", optsFromStrings(inputOptions));
        Answer a = awaitDecision(d);
        if (a == null) {
            if (initialInput != null) return initialInput;
            if (inputOptions != null && !inputOptions.isEmpty()) return inputOptions.get(0);
            return isNumeric ? "0" : "";
        }
        if (a.value() != null) return a.value();
        if (a.picks() != null && a.picks().length > 0 && inputOptions != null
                && a.picks()[0] >= 0 && a.picks()[0] < inputOptions.size()) {
            return inputOptions.get(a.picks()[0]);
        }
        return initialInput != null ? initialInput : (isNumeric ? "0" : "");
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
                                                       DelayedReveal delayedReveal, boolean isOptional) {
        if (optionList == null || optionList.isEmpty()) return null;
        Map<String, Object> d = baseDesc("chooseEntity", title, title, isOptional ? 0 : 1, 1, isOptional, false);
        d.put("options", optsFrom(new ArrayList<GameEntityView>(optionList), null));
        Answer a = awaitDecision(d);
        if (a != null && a.picks() != null && a.picks().length > 0) {
            int idx = a.picks()[0];
            if (idx >= 0 && idx < optionList.size()) return optionList.get(idx);
            return null; // explicit "none" from an optional prompt
        }
        return isOptional ? null : optionList.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
                                                        int min, int max, DelayedReveal delayedReveal) {
        List<GameEntityView> res = new ArrayList<>();
        if (optionList == null || optionList.isEmpty()) return res;
        Map<String, Object> d = baseDesc("chooseEntities", title, title, Math.max(min, 0), max, min <= 0, false);
        d.put("options", optsFrom(new ArrayList<GameEntityView>(optionList), null));
        Answer a = awaitDecision(d);
        if (a != null && a.picks() != null) {
            for (int idx : a.picks()) if (idx >= 0 && idx < optionList.size()) res.add(optionList.get(idx));
            if (res.size() >= Math.max(min, 0)) return res;
        }
        res.clear();
        for (int i = 0; i < optionList.size() && res.size() < Math.max(min, 0); i++) res.add(optionList.get(i));
        return res;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers,
                                                     int damage, GameEntityView defender,
                                                     boolean overrideOrder, boolean maySkip) {
        Map<CardView, Integer> res = new LinkedHashMap<>();
        if (blockers == null || blockers.isEmpty()) return res;
        if (blockers.size() == 1) { res.put(blockers.get(0), damage); return res; }

        // amount-per-blocker decision: distribute `damage` across the blockers.
        String atk = attacker != null && attacker.getCurrentState() != null
                ? attacker.getCurrentState().getName() : "攻击者";
        Map<String, Object> d = baseDesc("amount", "分配战斗伤害",
                atk + " 造成 " + damage + " 点伤害，分配给阻挡者", blockers.size(), blockers.size(), false, true);
        d.put("amount", damage);
        d.put("options", optsFrom(new ArrayList<CardView>(blockers), null));
        Answer a = awaitDecision(d);
        if (a != null && a.value() != null) {
            String[] parts = a.value().split(",");
            if (parts.length == blockers.size()) {
                int[] vals = new int[blockers.size()];
                int sum = 0;
                boolean ok = true;
                for (int i = 0; i < parts.length && ok; i++) {
                    try { vals[i] = Integer.parseInt(parts[i].trim()); sum += vals[i]; }
                    catch (NumberFormatException e) { ok = false; }
                }
                if (ok && sum == damage) {
                    for (int i = 0; i < blockers.size(); i++) if (vals[i] > 0) res.put(blockers.get(i), vals[i]);
                    return res;
                }
            }
        }
        res.put(blockers.get(0), damage); // default: all on the first blocker
        return res;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target,
                                                    int amount, boolean atLeastOne, String amountLabel) {
        Map<Object, Integer> res = new LinkedHashMap<>();
        if (target == null || target.isEmpty()) return res;
        List<Object> keys = new ArrayList<>(target.keySet());
        if (keys.size() == 1) { res.put(keys.get(0), amount); return res; }

        Map<String, Object> d = baseDesc("amount", amountLabel == null ? "分配数量" : amountLabel,
                "总计 " + amount, keys.size(), keys.size(), false, true);
        d.put("amount", amount);
        d.put("options", optsFrom(keys, null));
        Answer a = awaitDecision(d);
        if (a != null && a.value() != null) {
            // value = CSV of amounts aligned to option idx
            String[] parts = a.value().split(",");
            if (parts.length == keys.size()) {
                int[] vals = new int[keys.size()];
                int sum = 0;
                boolean ok = true;
                for (int i = 0; i < parts.length && ok; i++) {
                    try { vals[i] = Integer.parseInt(parts[i].trim()); sum += vals[i]; }
                    catch (NumberFormatException e) { ok = false; }
                }
                if (ok && sum == amount) {
                    for (int i = 0; i < keys.size(); i++) if (vals[i] > 0) res.put(keys.get(i), vals[i]);
                    return res;
                }
            }
        }
        res.put(keys.get(0), amount); // default: all on the first target
        return res;
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities,
                                             ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        if (abilities.size() == 1) return abilities.get(0);
        Map<String, Object> d = baseDesc("ability", "选择要使用的技能 / 模式", "", 1, 1, false, false);
        d.put("options", optsFrom(abilities, null));
        Answer a = awaitDecision(d);
        if (a != null && a.picks() != null && a.picks().length > 0) {
            int idx = a.picks()[0];
            if (idx >= 0 && idx < abilities.size()) return abilities.get(idx);
        }
        return abilities.get(0);
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        List<T> ordered = new ArrayList<>();
        if (destChoices != null) ordered.addAll(destChoices);
        if (sourceChoices == null || sourceChoices.isEmpty()) {
            return new OrderResult<>(ordered, false);
        }
        List<T> pool = new ArrayList<>(sourceChoices);
        int remMin = Math.max(0, remainingObjectsMin);
        int remMax = remainingObjectsMax < 0 ? 0 : remainingObjectsMax;
        if (remMax < remMin) remMax = remMin;

        // Order by successive single picks (top -> bottom). Each pick is a "choose"
        // decision (max 1). Stop once the number of leftovers is within [remMin, remMax].
        int guard = pool.size() + 2;
        while (pool.size() > remMin && guard-- > 0) {
            boolean mustPick = pool.size() > remMax; // too many remain -> a pick is required
            Map<String, Object> d = baseDesc("choose",
                    title == null ? "排序" : title,
                    (top == null ? "" : top) + "（从上到下依次选择）",
                    mustPick ? 1 : 0, 1, !mustPick, false);
            d.put("options", optsFrom(pool, null));
            Answer a = awaitDecision(d);
            if (a != null && a.picks() != null && a.picks().length > 0) {
                int idx = a.picks()[0];
                if (idx >= 0 && idx < pool.size()) { ordered.add(pool.remove(idx)); continue; }
            }
            if (a != null && a.picks() != null && a.picks().length == 0 && !mustPick) {
                break; // user stopped; leftovers stay unselected
            }
            // default / timeout: take the top if a pick is required, else stop
            if (mustPick) ordered.add(pool.remove(0));
            else break;
        }
        return new OrderResult<>(ordered, false);
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        // [WEB-TODO] no sideboard UI yet — keep the current deck (never blocks; only
        // relevant between games of a match).
        todo("sideboard (default: keep deck)");
        return null;
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        // [WEB-TODO] no dedicated reorder-to-top/bottom UI yet — keep the given order.
        todo("manipulateCardList/" + title + " (default: unchanged)");
        List<CardView> res = new ArrayList<>();
        if (cards != null) for (CardView cv : cards) res.add(cv);
        return res;
    }
}
