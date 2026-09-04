package forge.web;

import java.util.List;

import forge.deck.Deck;
import forge.deck.DeckRecognizer;
import forge.deck.DeckRecognizer.Token;
import forge.deck.DeckRecognizer.TokenType;
import forge.deck.DeckSection;

/**
 * Parse an MTGA / Arena decklist text into a Forge {@link Deck} using Forge's own
 * headless {@link DeckRecognizer} (forge-core, no GUI). Handles the Arena format:
 * a {@code Deck} section, {@code <count> <English name>} lines (optionally with a
 * trailing {@code (SET) 123}), an optional blank line, then a {@code Sideboard}
 * section. Unrecognized lines are skipped and logged.
 *
 * <p>Requires the card database to be loaded (FModel.initialize done once).
 */
public final class DeckParser {
    private DeckParser() {}

    /**
     * @return a Deck with at least one main-deck card, or {@code null} if the text
     *         is blank / nothing usable parsed (caller then falls back to a default).
     */
    public static Deck parseArena(String name, String text) {
        if (text == null || text.isBlank()) return null;

        String[] lines = text.replace("\r", "").split("\n");
        DeckRecognizer rec = new DeckRecognizer(); // permissive: no format/section constraints
        List<Token> tokens;
        try {
            tokens = rec.parseCardList(lines);
        } catch (Exception e) {
            System.err.println("[deck] parse error: " + e);
            return null;
        }

        Deck d = new Deck(name == null || name.isBlank() ? "Web Deck" : name);
        int added = 0, skipped = 0;
        for (Token t : tokens) {
            if (t.isCardTokenForDeck() && t.getCard() != null) {
                DeckSection sec = t.getTokenSection();
                if (sec != DeckSection.Main && sec != DeckSection.Sideboard) sec = DeckSection.Main;
                d.getOrCreate(sec).add(t.getCard(), t.getQuantity());
                added += t.getQuantity();
            } else if (t.getType() == TokenType.UNKNOWN_CARD || t.getType() == TokenType.UNSUPPORTED_CARD) {
                System.out.println("[deck] skip unrecognized line: " + t.getText());
                skipped++;
            }
        }

        if (d.getMain().isEmpty()) {
            System.out.println("[deck] no usable main-deck cards parsed (skipped=" + skipped + ")");
            return null;
        }
        int side = d.has(DeckSection.Sideboard) ? d.get(DeckSection.Sideboard).countAll() : 0;
        System.out.println("[deck] parsed '" + d.getName() + "' main=" + d.getMain().countAll()
                + " side=" + side + " skipped=" + skipped);
        return d;
    }

    /** Build an AI opponent deck as a copy of the human deck (same 60 + sideboard). */
    public static Deck copyForAi(Deck human) {
        Deck ai = new Deck(human.getName() + " (AI)");
        ai.getOrCreate(DeckSection.Main).addAll(human.getMain());
        if (human.has(DeckSection.Sideboard)) {
            ai.getOrCreate(DeckSection.Sideboard).addAll(human.get(DeckSection.Sideboard));
        }
        return ai;
    }
}
