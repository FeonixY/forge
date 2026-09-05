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
import forge.player.LobbyPlayerHuman;
import forge.util.ThreadUtil;

/**
 * Global one-time setup (GuiBase + card DB) plus per-match bootstrap: one human
 * (driven by a per-connection {@link WebGuiGame}) versus one Forge AI. Each call to
 * {@link #startHumanVsAi} builds an independent {@link HostedMatch} on its own Forge
 * game thread, so many connections can play concurrently.
 */
public final class MatchBootstrap {
    private MatchBootstrap() {}

    private static volatile boolean initialized = false;

    /** Install the headless GuiBase + load the card database. Idempotent / global. */
    public static synchronized void ensureInitialized() {
        if (!(GuiBase.getInterface() instanceof WebGuiBase)) {
            GuiBase.setInterface(new WebGuiBase());
        }
        if (!initialized) {
            FModel.initialize(null, prefs -> {
                prefs.setPref(FPref.LOAD_CARD_SCRIPTS_LAZILY, false);
                prefs.setPref(FPref.UI_LANGUAGE, "en-US");
                prefs.setPref(FPref.ENFORCE_DECK_LEGALITY, false);
                // Best-of-3 so between-games sideboarding actually triggers (also the Forge
                // default, but pin it explicitly). HostedMatch reads this pref for gamesPerMatch.
                prefs.setPref(FPref.UI_MATCHES_PER_GAME, "3");
                return null;
            });
            initialized = true;
        }
    }

    /** Default deck when a connection starts a game without providing a decklist. */
    public static Deck defaultDeck() {
        Deck d = new Deck("MDC Default");
        d.getMain().add("Forest", 34);
        d.getMain().add("Grizzly Bears", 26);
        return d;
    }

    /** SmokeTest / no-deck entry: default human deck (AI mirrors it). */
    public static HostedMatch startHumanVsAi(WebGuiGame gui) {
        return startHumanVsAi(gui, null);
    }

    /**
     * Start an independent human-vs-AI Constructed match. {@code humanDeck} null ->
     * the default deck. The AI opponent plays a copy of the human deck (fair mirror).
     * Returns immediately; the game runs on its own Forge game thread and blocks for
     * input delivered via {@code gui.submitAction} / {@code gui.submitDecision}.
     */
    public static HostedMatch startHumanVsAi(WebGuiGame gui, Deck humanDeck) {
        ensureInitialized();

        Deck human = humanDeck != null ? humanDeck : defaultDeck();
        Deck ai = DeckParser.copyForAi(human);

        RegisteredPlayer humanRp = new RegisteredPlayer(human);
        // Fresh human LobbyPlayer per match (NOT the shared singleton) so concurrent
        // matches don't alias the same player object.
        humanRp.setPlayer(new LobbyPlayerHuman("You"));

        RegisteredPlayer aiRp = new RegisteredPlayer(ai);
        aiRp.setPlayer(GamePlayerUtil.createAiPlayer());

        List<RegisteredPlayer> players = Arrays.asList(humanRp, aiRp);

        HostedMatch hosted = new HostedMatch();
        // startMatch schedules the game on a Forge game thread (GameAction.invoke) and
        // returns; the WebGuiGame receives update callbacks from that thread.
        hosted.startMatch(GameType.Constructed, null, players, humanRp, gui);
        return hosted;
    }

    /** True if the calling code is running on Forge's dedicated game thread. */
    public static boolean onGameThread() {
        return ThreadUtil.isGameThread();
    }
}
