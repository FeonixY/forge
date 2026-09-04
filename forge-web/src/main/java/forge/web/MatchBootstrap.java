package forge.web;

import java.util.Arrays;
import java.util.List;

import forge.deck.Deck;
import forge.game.GameType;
import forge.game.player.RegisteredPlayer;
import forge.gamemodes.match.HostedMatch;
import forge.gui.GuiBase;
import forge.localinstance.properties.ForgePreferences.FPref;
import forge.model.FModel;
import forge.player.GamePlayerUtil;
import forge.util.ThreadUtil;

/**
 * Boots a headless Forge Constructed game: one human (driven by {@link WebGuiGame})
 * versus one Forge AI, with two simple mono-color decks written in code. This is the
 * shared entry used by both the WebSocket server ({@link BridgeApp}) and the offline
 * smoke test ({@link SmokeTest}).
 */
public final class MatchBootstrap {
    private MatchBootstrap() {}

    private static volatile boolean initialized = false;

    /** Install the headless GuiBase + load the card database. Idempotent. */
    public static synchronized void ensureInitialized(WebGuiGame gui) {
        if (!(GuiBase.getInterface() instanceof WebGuiBase)) {
            GuiBase.setInterface(new WebGuiBase(gui));
        }
        if (!initialized) {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                return null;
            });
            initialized = true;
        }
    }

    /**
     * Build decks, wire the human to {@code gui}, and start the match. Returns
     * immediately; the game runs on Forge's game thread and blocks for input
     * that arrives via {@link WebGuiGame#submitAction}.
     */
    public static HostedMatch startHumanVsAi(WebGuiGame gui) {
        ensureInitialized(gui);

        Deck humanDeck = simpleDeck("MDC Human", "Forest", 34, "Grizzly Bears", 26);
        Deck aiDeck = simpleDeck("MDC AI", "Mountain", 34, "Grizzly Bears", 26);

        RegisteredPlayer humanRp = new RegisteredPlayer(humanDeck);
        humanRp.setPlayer(GamePlayerUtil.getGuiPlayer());

        RegisteredPlayer aiRp = new RegisteredPlayer(aiDeck);
        aiRp.setPlayer(GamePlayerUtil.createAiPlayer());

        List<RegisteredPlayer> players = Arrays.asList(humanRp, aiRp);

        HostedMatch hosted = new HostedMatch();
        // startMatch schedules the game on Forge's game thread (see GameAction.invoke)
        // and returns; the WebGuiGame receives update callbacks from that thread.
        hosted.startMatch(GameType.Constructed, null, players, humanRp, gui);
        return hosted;
    }

    static Deck simpleDeck(String name, String land, int nLand, String creature, int nCreature) {
        Deck d = new Deck(name);
        d.getMain().add(land, nLand);
        d.getMain().add(creature, nCreature);
        return d;
    }

    /** True if the calling code is running on Forge's dedicated game thread. */
    public static boolean onGameThread() {
        return ThreadUtil.isGameThread();
    }
}
