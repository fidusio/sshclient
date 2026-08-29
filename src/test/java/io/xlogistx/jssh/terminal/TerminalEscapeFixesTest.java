package io.xlogistx.jssh.terminal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for escape-sequence handling in {@link TerminalPanel}:
 * truecolor SGR, DECSTBM validation, multi-parameter DEC private modes,
 * attribute bookkeeping in DCH/ECH, partial-region scrolling, the cursor blink
 * timer lifecycle and the Ctrl+C copy-vs-interrupt decision. All headless.
 */
public class TerminalEscapeFixesTest {

    private static final String ESC = "";

    @BeforeAll
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static TerminalPanel feed(TerminalPanel term, String s) {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        term.write(b, 0, b.length);
        return term;
    }

    private static String firstLine(TerminalPanel term) {
        String text = term.getScreenText();
        int nl = text.indexOf('\n');
        return nl >= 0 ? text.substring(0, nl) : text;
    }

    // ---- 1: truecolor / 256-colour SGR ----

    @Test
    public void truecolorForegroundDoesNotLeakSubParamsAsSgrCodes() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // Bold on, then a truecolor black foreground. Before the fix the trailing
        // "0;0;0" were interpreted as SGR 0 (reset), wiping bold.
        feed(term, ESC + "[1m" + ESC + "[38;2;0;0;0mX");
        assertEquals("X", firstLine(term));
        assertTrue(term.isBoldAt(0, 0), "bold must survive a 38;2;r;g;b sequence");
        assertEquals(0 + 8, term.getFgAt(0, 0), "black + bold -> bright black index");
    }

    @Test
    public void truecolorMapsToNearestPaletteEntry() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, ESC + "[38;2;255;255;255mA" + ESC + "[0m");
        assertEquals(15, term.getFgAt(0, 0), "pure white -> bright white");

        feed(term, ESC + "[48;2;0;0;170mB" + ESC + "[0m");
        assertEquals(4, term.getBgAt(1, 0), "(0,0,170) is exactly ANSI blue");

        // "1" after the colour must be honoured as bold, i.e. the 5 args were consumed
        feed(term, ESC + "[38;2;170;0;0;1mC" + ESC + "[0m");
        assertTrue(term.isBoldAt(2, 0));
        assertEquals(1 + 8, term.getFgAt(2, 0));
        assertEquals("ABC", firstLine(term));
    }

    @Test
    public void nearestPaletteColorHelper() {
        assertEquals(0, TerminalPanel.nearestPaletteColor(0, 0, 0));
        assertEquals(15, TerminalPanel.nearestPaletteColor(255, 255, 255));
        assertEquals(2, TerminalPanel.nearestPaletteColor(0, 170, 0));
        assertEquals(10, TerminalPanel.nearestPaletteColor(85, 255, 85));
        // Out-of-range components are clamped rather than thrown on
        assertEquals(15, TerminalPanel.nearestPaletteColor(999, 999, 999));
        assertEquals(0, TerminalPanel.nearestPaletteColor(-5, -5, -5));
    }

    @Test
    public void ansi256IndexMapsThroughCubeAndGreyRamp() {
        for (int i = 0; i < 16; i++) {
            assertEquals(i, TerminalPanel.ansi256ToPalette(i));
        }
        assertEquals(1, TerminalPanel.ansi256ToPalette(196), "196 = (255,0,0) -> red");
        assertEquals(4, TerminalPanel.ansi256ToPalette(21), "21 = (0,0,255) -> blue");
        assertEquals(0, TerminalPanel.ansi256ToPalette(16), "16 = (0,0,0) -> black");
        assertEquals(15, TerminalPanel.ansi256ToPalette(231), "231 = (255,255,255) -> bright white");
        assertEquals(15, TerminalPanel.ansi256ToPalette(255), "grey ramp top -> bright white");
        assertEquals(0, TerminalPanel.ansi256ToPalette(232), "grey ramp bottom -> black");
        // Clamped, never out of bounds
        assertEquals(15, TerminalPanel.ansi256ToPalette(9999));
        assertEquals(0, TerminalPanel.ansi256ToPalette(-1));
    }

    @Test
    public void truncated256ColorSequenceDoesNotThrow() {
        TerminalPanel term = new TerminalPanel(80, 24);
        assertDoesNotThrow(() -> feed(term, ESC + "[38;5mA"));
        assertDoesNotThrow(() -> feed(term, ESC + "[48;5mB"));
        assertDoesNotThrow(() -> feed(term, ESC + "[38;2;1;2mC"));
        assertDoesNotThrow(() -> feed(term, ESC + "[38mD"));
        assertDoesNotThrow(() -> feed(term, ESC + "[38;5;300mE"));
        assertEquals("ABCDE", firstLine(term));
    }

    // ---- 2: DECSTBM validation ----

    @Test
    public void zeroScrollRegionThenDeleteLineDoesNotThrow() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, "one\r\ntwo\r\nthree");
        assertDoesNotThrow(() -> feed(term, ESC + "[0;0r" + ESC + "[M"));
        assertEquals(0, term.getScrollTop());
        assertEquals(23, term.getScrollBottom());

        assertDoesNotThrow(() -> feed(term, ESC + "[1;0r" + ESC + "[M" + ESC + "[L"));
        assertEquals(0, term.getScrollTop());
        assertEquals(23, term.getScrollBottom());
    }

    @Test
    public void scrollRegionIsClampedAndInvalidOrderIgnored() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, ESC + "[5;10r");
        assertEquals(4, term.getScrollTop());
        assertEquals(9, term.getScrollBottom());
        assertEquals(0, term.getCursorX());
        assertEquals(0, term.getCursorY());

        // top >= bottom is ignored: region unchanged
        feed(term, ESC + "[10;5r");
        assertEquals(4, term.getScrollTop());
        assertEquals(9, term.getScrollBottom());
        feed(term, ESC + "[7;7r");
        assertEquals(4, term.getScrollTop());
        assertEquals(9, term.getScrollBottom());

        // Out-of-range bottom is clamped to the screen
        feed(term, ESC + "[2;999r");
        assertEquals(1, term.getScrollTop());
        assertEquals(23, term.getScrollBottom());

        // Bare ESC[r resets to the full screen
        feed(term, ESC + "[r");
        assertEquals(0, term.getScrollTop());
        assertEquals(23, term.getScrollBottom());
    }

    @Test
    public void insertAndDeleteLinesRespectRegion() {
        TerminalPanel term = new TerminalPanel(80, 5);
        feed(term, "L1\r\nL2\r\nL3\r\nL4\r\nL5");
        // Region rows 2..4; cursor to row 2 and delete one line
        feed(term, ESC + "[2;4r" + ESC + "[2;1H" + ESC + "[M");
        assertEquals("L1\nL3\nL4\n\nL5", term.getScreenText());
        // Insert a line back at row 2
        feed(term, ESC + "[2;1H" + ESC + "[L");
        assertEquals("L1\n\nL3\nL4\nL5", term.getScreenText());
        // Cursor outside the region: IL/DL do nothing
        feed(term, ESC + "[5;1H" + ESC + "[M");
        assertEquals("L1\n\nL3\nL4\nL5", term.getScreenText());
        // Huge counts do not throw
        assertDoesNotThrow(() -> feed(term, ESC + "[3;1H" + ESC + "[999M" + ESC + "[999L"));
    }

    // ---- 3: multi-parameter DEC private modes ----

    @Test
    public void multiParameterPrivateModeRestoresCursor() {
        TerminalPanel term = new TerminalPanel(80, 24);
        assertTrue(term.isCursorVisible());
        feed(term, ESC + "[?25l");
        assertFalse(term.isCursorVisible());
        // xterm "cnorm" as sent by vim/less on exit
        feed(term, ESC + "[?12;25h");
        assertTrue(term.isCursorVisible(), "ESC[?12;25h must re-show the cursor");

        feed(term, ESC + "[?12;25l");
        assertFalse(term.isCursorVisible());
        feed(term, ESC + "[?25;12h");
        assertTrue(term.isCursorVisible());
    }

    @Test
    public void multiParameterPrivateModeAppliesEachMode() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, ESC + "[?25;2004h");
        assertTrue(term.isCursorVisible());
        assertTrue(term.isBracketedPaste());
        feed(term, ESC + "[?2004;25l");
        assertFalse(term.isCursorVisible());
        assertFalse(term.isBracketedPaste());
        // Garbage parameters are skipped without throwing
        assertDoesNotThrow(() -> feed(term, ESC + "[?;;25h"));
        assertTrue(term.isCursorVisible());
    }

    // ---- 6: attribute drift ----

    @Test
    public void deleteCharsShiftsReverseAndBold() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // "AB" plain, "CD" reverse+bold, "EF" plain
        feed(term, "AB" + ESC + "[7;1mCD" + ESC + "[0mEF");
        assertFalse(term.isReverseAt(0, 0));
        assertTrue(term.isReverseAt(2, 0));
        assertTrue(term.isBoldAt(3, 0));

        feed(term, ESC + "[1;1H" + ESC + "[2P");   // delete 2 chars at column 0
        assertEquals("CDEF", firstLine(term));
        assertTrue(term.isReverseAt(0, 0), "reverse attribute must shift with its char");
        assertTrue(term.isBoldAt(1, 0), "bold attribute must shift with its char");
        assertFalse(term.isReverseAt(2, 0));
        assertFalse(term.isReverseAt(78, 0));
        assertFalse(term.isReverseAt(79, 0));
    }

    @Test
    public void eraseCharsClearsReverseAndBold() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, ESC + "[7;1mABCD" + ESC + "[0m");
        feed(term, ESC + "[1;2H" + ESC + "[2X");   // erase B and C
        assertEquals("A  D", firstLine(term));
        assertTrue(term.isReverseAt(0, 0));
        assertFalse(term.isReverseAt(1, 0));
        assertFalse(term.isBoldAt(1, 0));
        assertFalse(term.isReverseAt(2, 0));
        assertTrue(term.isReverseAt(3, 0));
        assertEquals(7, term.getFgAt(1, 0));
    }

    @Test
    public void partialRegionScrollDoesNotFeedScrollback() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, ESC + "[1;10r");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("line").append(i).append("\r\n");
        feed(term, sb.toString());
        assertEquals(0, term.getScrollbackSize(), "scrolling a partial region must not push into scrollback");
        // Rows below the region are untouched
        assertTrue(term.getScreenText().split("\n", -1)[23].isEmpty());

        // Full-screen region scrolls into scrollback as before
        feed(term, ESC + "[r");
        feed(term, sb.toString());
        assertTrue(term.getScrollbackSize() > 0);
    }

    @Test
    public void alternateScreenNeverFeedsScrollback() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, ESC + "[?1049h");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 40; i++) sb.append("alt").append(i).append("\r\n");
        feed(term, sb.toString());
        assertEquals(0, term.getScrollbackSize());
        feed(term, ESC + "[?1049l");
    }

    // ---- 4: blink timer lifecycle ----

    @Test
    public void blinkTimerNotRunningUntilShownAndDisposeIsIdempotent() {
        TerminalPanel term = new TerminalPanel(80, 24);
        assertFalse(term.isBlinkTimerRunning(), "timer must not run for a panel that is not displayed");
        assertDoesNotThrow(term::dispose);
        assertDoesNotThrow(term::dispose);
        assertFalse(term.isBlinkTimerRunning());
        // removeNotify without a prior addNotify is harmless
        assertDoesNotThrow(term::removeNotify);
        assertFalse(term.isBlinkTimerRunning());
    }

    // ---- 5: Ctrl+C after an auto-copied selection ----

    @Test
    public void ctrlCCopiesOnlyWhenSelectionNotYetCopied() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, "hello world");
        assertFalse(term.ctrlCShouldCopy(), "no selection -> interrupt");

        term.setSelection(0, 0, 4, 0);
        assertTrue(term.ctrlCShouldCopy(), "fresh selection -> copy");

        term.markSelectionCopied();   // what mouseReleased does after auto-copy
        assertFalse(term.ctrlCShouldCopy(), "already copied -> interrupt");

        term.setSelection(6, 0, 10, 0);
        assertTrue(term.ctrlCShouldCopy(), "a new selection is copy-able again");
    }

    @Test
    public void ctrlCAfterAutoCopiedSelectionSendsInterruptAndClearsSelection() {
        TerminalPanel term = new TerminalPanel(80, 24);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        term.setOutputStream(out);
        feed(term, "hello world");

        term.setSelection(0, 0, 4, 0);
        term.markSelectionCopied();

        KeyEvent ctrlC = new KeyEvent(term, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_C, KeyEvent.CHAR_UNDEFINED);
        term.keyPressed(ctrlC);

        assertArrayEquals(new byte[] { 0x03 }, out.toByteArray(), "must send SIGINT on first Ctrl+C");
        assertFalse(term.ctrlCShouldCopy());
        assertFalse(term.isSelectionCopied(), "selection state is reset once cleared");
    }
}
