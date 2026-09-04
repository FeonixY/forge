package forge.web;

import java.util.List;

import forge.GuiDesktop;
import forge.gui.interfaces.IGuiGame;
import forge.localinstance.skin.FSkinProp;
import forge.localinstance.skin.ISkinImage;

/**
 * Headless GuiBase for the web bridge. Extends {@link GuiDesktop} (reusing its
 * working Swing EDT — required because the Input framework posts prompts via
 * FThreads.invokeInEdtLater) and overrides the dialog/audio/shop hooks with
 * no-ops so nothing tries to open a window. Modeled on the desktop test's
 * HeadlessGuiDesktop.
 *
 * <p>{@link #getNewGuiGame()} hands out a shared {@link WebGuiGame} so the bridge
 * can attach a WebSocket sink to the same instance the engine drives.
 */
public class WebGuiBase extends GuiDesktop {

    public WebGuiBase() {}

    /**
     * Each match uses its own {@link WebGuiGame} instance (passed explicitly to
     * {@code HostedMatch.startMatch}), so this factory only needs to return a fresh
     * throwaway for the rare engine paths that ask GuiBase for a new gui (e.g. a
     * spectator with no local players). Per-connection games never rely on it.
     */
    @Override
    public IGuiGame getNewGuiGame() {
        return new WebGuiGame();
    }

    /**
     * Forge resolves its resource root (card DB, editions, etc.) as
     * {@code getAssetsDir() + "res/"}. GuiDesktop only returns the dev path for
     * "git" version strings, so we pin it explicitly: honor an
     * {@code -Dmdc.assetsDir=...} override, else locate the sibling
     * {@code forge-gui} directory by walking up from the process working dir.
     */
    @Override
    public String getAssetsDir() {
        String override = System.getProperty("mdc.assetsDir");
        if (override != null && !override.isEmpty()) {
            return override.endsWith("/") ? override : override + "/";
        }
        java.io.File cwd = new java.io.File("").getAbsoluteFile();
        for (java.io.File d = cwd; d != null; d = d.getParentFile()) {
            java.io.File res = new java.io.File(d, "forge-gui/res");
            if (res.isDirectory()) {
                return new java.io.File(d, "forge-gui").getPath() + "/";
            }
            // running from inside a module dir (e.g. forge-web/): check the sibling
            java.io.File sibling = new java.io.File(d.getParentFile(), "forge-gui/res");
            if (d.getParentFile() != null && sibling.isDirectory()) {
                return new java.io.File(d.getParentFile(), "forge-gui").getPath() + "/";
            }
        }
        return "../forge-gui/"; // last-resort dev default
    }

    @Override public void showSpellShop() {}
    @Override public void showBazaar() {}

    @Override
    public int showOptionDialog(String message, String title, FSkinProp icon,
                                List<String> options, int defaultOption) {
        System.err.println("[WebGuiBase] " + title + ": " + message);
        return -1;
    }

    @Override
    public void showImageDialog(ISkinImage image, String message, String title) {
        System.err.println("[WebGuiBase] " + title + ": " + message);
    }

    @Override
    public String showInputDialog(String message, String title, FSkinProp icon,
                                  String initialInput, List<String> inputOptions, boolean isNumeric) {
        if (initialInput != null) return initialInput;
        if (inputOptions != null && !inputOptions.isEmpty()) return inputOptions.get(0);
        return isNumeric ? "0" : "";
    }

    @Override
    public String showFileDialog(String title, String defaultDir) { return null; }

    @Override
    public void showBugReportDialog(String title, String text, boolean showExitAppBtn) {
        System.err.println("[WebGuiBase] Bug Report - " + title);
    }

    @Override
    public forge.sound.IAudioClip createAudioClip(String filename) { return null; }
    @Override
    public forge.sound.IAudioMusic createAudioMusic(String filename) { return null; }
    @Override
    public void startAltSoundSystem(String filename, boolean isSynchronized) {}
}
