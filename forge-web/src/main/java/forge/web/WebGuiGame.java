package forge.web;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

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
 * <p>Blocking decision methods (getChoices/confirm/assignCombatDamage/...) are
 * currently AUTO-STUBBED so a full headless game can run end to end. Each stub is
 * tagged {@code [WEB-TODO]} on stderr; wiring them to real browser prompts is the
 * next increment. Priority / attackers / blockers already flow through the Input
 * framework -> updateButtons/showPromptMessage/setSelectables -> submitAction.
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

    public void setSink(Sink s) { this.sink = s; pushState(); }
    public PlayerView getHumanView() { return humanView; }
    public boolean isOkEnabled() { return okEnabled; }
    public int getInputEpoch() { return inputEpoch.get(); }

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
        return GameViewSerializer.serialize(gv, you, promptText, buildActions());
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
    // Blocking decisions — AUTO-STUBBED (see class doc). [WEB-TODO]
    // ------------------------------------------------------------------

    private static void todo(String what) {
        System.err.println("[WEB-TODO] auto-answered decision: " + what);
    }

    @Override
    public <T> List<T> getChoices(String message, int min, int max, List<T> choices,
                                  List<T> selected, FSerializableFunction<T, String> display) {
        todo("getChoices/" + message);
        List<T> res = new ArrayList<>();
        if (choices == null || choices.isEmpty() || max < 0) return res; // reveal-only or nothing
        int want = Math.max(min, 0);
        if (want == 0) want = Math.min(1, max <= 0 ? 0 : 1); // pick one when a single pick is allowed
        for (int i = 0; i < choices.size() && res.size() < Math.max(want, min); i++) {
            res.add(choices.get(i));
        }
        return res;
    }

    @Override
    public boolean confirm(CardView c, String question, boolean defaultIsYes, List<String> options) {
        todo("confirm/" + question);
        return defaultIsYes;
    }

    @Override
    public boolean showConfirmDialog(String message, String title, String yesButtonText,
                                     String noButtonText, boolean defaultYes) {
        todo("confirmDialog/" + title);
        return defaultYes;
    }

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon,
                                List<String> options, int defaultOption) {
        todo("optionDialog/" + title);
        return defaultOption;
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon,
                                  String initialInput, List<String> inputOptions, boolean isNumeric) {
        todo("inputDialog/" + title);
        if (initialInput != null) return initialInput;
        if (inputOptions != null && !inputOptions.isEmpty()) return inputOptions.get(0);
        return isNumeric ? "0" : "";
    }

    @Override
    public GameEntityView chooseSingleEntityForEffect(String title, List<? extends GameEntityView> optionList,
                                                       DelayedReveal delayedReveal, boolean isOptional) {
        todo("chooseSingleEntity/" + title);
        if (isOptional || optionList == null || optionList.isEmpty()) return null;
        return optionList.get(0);
    }

    @Override
    public List<GameEntityView> chooseEntitiesForEffect(String title, List<? extends GameEntityView> optionList,
                                                        int min, int max, DelayedReveal delayedReveal) {
        todo("chooseEntities/" + title);
        List<GameEntityView> res = new ArrayList<>();
        if (optionList == null) return res;
        for (int i = 0; i < optionList.size() && res.size() < Math.max(min, 0); i++) {
            res.add(optionList.get(i));
        }
        return res;
    }

    @Override
    public Map<CardView, Integer> assignCombatDamage(CardView attacker, List<CardView> blockers,
                                                     int damage, GameEntityView defender,
                                                     boolean overrideOrder, boolean maySkip) {
        todo("assignCombatDamage");
        Map<CardView, Integer> res = new LinkedHashMap<>();
        if (blockers != null && !blockers.isEmpty()) {
            res.put(blockers.get(0), damage); // dump all damage on the first blocker
        }
        return res;
    }

    @Override
    public Map<Object, Integer> assignGenericAmount(CardView effectSource, Map<Object, Integer> target,
                                                    int amount, boolean atLeastOne, String amountLabel) {
        todo("assignGenericAmount/" + amountLabel);
        Map<Object, Integer> res = new LinkedHashMap<>();
        if (target != null && !target.isEmpty()) {
            res.put(target.keySet().iterator().next(), amount);
        }
        return res;
    }

    @Override
    public SpellAbilityView getAbilityToPlay(CardView hostCard, List<SpellAbilityView> abilities,
                                             ITriggerEvent triggerEvent) {
        if (abilities == null || abilities.isEmpty()) return null;
        return abilities.get(0); // auto-pick the first ability of a played card
    }

    @Override
    public <T> OrderResult<T> order(String title, String top, int remainingObjectsMin, int remainingObjectsMax,
                                    List<T> sourceChoices, List<T> destChoices, CardView referenceCard,
                                    boolean sideboardingMode, boolean showRememberCheckbox) {
        todo("order/" + title);
        List<T> ordered = new ArrayList<>();
        if (destChoices != null) ordered.addAll(destChoices);
        if (sourceChoices != null) ordered.addAll(sourceChoices);
        return new OrderResult<>(ordered, false);
    }

    @Override
    public List<PaperCard> sideboard(CardPool sideboard, CardPool main, String message) {
        todo("sideboard");
        return null; // keep current deck
    }

    @Override
    public List<CardView> manipulateCardList(String title, Iterable<CardView> cards, Iterable<CardView> manipulable,
                                             boolean toTop, boolean toBottom, boolean toAnywhere) {
        todo("manipulateCardList/" + title);
        List<CardView> res = new ArrayList<>();
        if (cards != null) for (CardView cv : cards) res.add(cv);
        return res;
    }
}
