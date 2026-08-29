package io.xlogistx.jssh.terminal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Byte-level input/output robustness of {@link TerminalPanel}: malformed UTF-8
 * from the remote must never throw, and typed characters must leave as UTF-8.
 */
public class TerminalInputTest {

    @BeforeAll
    public static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static String firstLine(TerminalPanel term) {
        String text = term.getScreenText();
        int nl = text.indexOf('\n');
        return nl >= 0 ? text.substring(0, nl) : text;
    }

    private static TerminalPanel feed(TerminalPanel term, byte[] b) {
        term.write(b, 0, b.length);
        return term;
    }

    private static byte[] bytes(int... v) {
        byte[] b = new byte[v.length];
        for (int i = 0; i < v.length; i++) b[i] = (byte) v[i];
        return b;
    }

    // ---- #2: invalid UTF-8 must not kill the output path ----

    @Test
    public void leadByteAboveF4IsReplacedAndOutputContinues() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // 0xF5 0x80 0x80 0x80 would decode to > U+10FFFF; Character.toChars used to throw here
        assertDoesNotThrow(() -> feed(term, bytes(0xF5, 0x80, 0x80, 0x80, 'Z')));
        String line = firstLine(term);
        assertTrue(line.endsWith("Z"), "text after the bad sequence must still land: " + line);
        assertTrue(line.indexOf('�') >= 0, "bad sequence should show as U+FFFD");
    }

    @Test
    public void f4WithOutOfRangeContinuationIsReplaced() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // F4 90 80 80 = U+110000 (one past the last code point)
        assertDoesNotThrow(() -> feed(term, bytes(0xF4, 0x90, 0x80, 0x80, 'Z')));
        assertEquals("�Z", firstLine(term));
    }

    @Test
    public void encodedSurrogateIsReplaced() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // ED A0 80 = U+D800 (a lone high surrogate, illegal in UTF-8)
        feed(term, bytes(0xED, 0xA0, 0x80, 'Z'));
        assertEquals("�Z", firstLine(term));
    }

    @Test
    public void overlongEncodingIsReplaced() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // C0 80 is an overlong NUL; E0 80 80 is an overlong 3-byte form
        feed(term, bytes(0xC0, 0x80, 'A', 0xE0, 0x80, 0x80, 'B'));
        String line = firstLine(term);
        assertTrue(line.contains("A"), line);
        assertTrue(line.contains("B"), line);
        assertTrue(line.endsWith("B"), line);
    }

    @Test
    public void validFourByteSequenceStillDecodes() {
        TerminalPanel term = new TerminalPanel(80, 24);
        String emoji = new String(Character.toChars(0x1F600));
        feed(term, (emoji + "Z").getBytes(StandardCharsets.UTF_8));
        assertTrue(firstLine(term).startsWith(emoji + "Z"));
    }

    @Test
    public void sanitizeCodePointRules() {
        assertEquals('A', TerminalPanel.sanitizeCodePoint('A', 0));
        assertEquals(0x1F600, TerminalPanel.sanitizeCodePoint(0x1F600, 0x10000));
        assertEquals(0xFFFD, TerminalPanel.sanitizeCodePoint(0x110000, 0x10000));
        assertEquals(0xFFFD, TerminalPanel.sanitizeCodePoint(0xD800, 0x800));
        assertEquals(0xFFFD, TerminalPanel.sanitizeCodePoint(0x41, 0x80), "overlong");
    }

    @Test
    public void truncatedSequenceShowsReplacementAndKeepsFollowingByte() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // E2 82 is a 3-byte sequence cut short; 'Z' is not a continuation byte
        feed(term, bytes(0xE2, 0x82, 'Z'));
        assertEquals("�Z", firstLine(term), "partial sequence → U+FFFD, then the byte that ended it");
    }

    // ---- #3: typed characters leave as UTF-8 ----

    @Test
    public void typedNonAsciiIsUtf8Encoded() {
        assertArrayEquals(new byte[] { (byte) 0xC3, (byte) 0xA9 }, TerminalPanel.encodeForRemote("é"));
        assertArrayEquals(new byte[] { (byte) 0xE2, (byte) 0x82, (byte) 0xAC }, TerminalPanel.encodeForRemote("€"));
        assertArrayEquals("a".getBytes(StandardCharsets.US_ASCII), TerminalPanel.encodeForRemote("a"));
    }

    // ---- #4: AltGr detection ----

    private static KeyEvent key(TerminalPanel src, int modifiers, char ch) {
        return new KeyEvent(src, KeyEvent.KEY_TYPED, System.currentTimeMillis(), modifiers, KeyEvent.VK_UNDEFINED, ch);
    }

    @Test
    public void altGrIsRecognisedOnWindowsAndUnix() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // Windows reports AltGr as Ctrl+Alt
        assertTrue(TerminalPanel.isAltGr(key(term, KeyEvent.CTRL_DOWN_MASK | KeyEvent.ALT_DOWN_MASK, '@')));
        // Linux/macOS report it as ALT_GRAPH
        assertTrue(TerminalPanel.isAltGr(key(term, KeyEvent.ALT_GRAPH_DOWN_MASK, '@')));
        // A plain Ctrl or plain Alt chord is not AltGr
        assertFalse(TerminalPanel.isAltGr(key(term, KeyEvent.CTRL_DOWN_MASK, 'c')));
        assertFalse(TerminalPanel.isAltGr(key(term, KeyEvent.ALT_DOWN_MASK, 'f')));
        assertFalse(TerminalPanel.isAltGr(key(term, 0, 'x')));
    }

    // ---- Alt+key is Meta (ESC prefix) ----

    private static KeyEvent pressed(TerminalPanel src, int modifiers, int keyCode, char ch) {
        return new KeyEvent(src, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, keyCode, ch);
    }

    @Test
    public void altKeySendsEscapePrefixedKey() {
        TerminalPanel term = new TerminalPanel(80, 24);
        String esc = String.valueOf((char) 0x1B);
        assertEquals(esc + "b", TerminalPanel.metaSequence(pressed(term, KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_B, 'b')));
        // Shift+Alt+letter → upper case (M-B), regardless of what the event reports
        assertEquals(esc + "B", TerminalPanel.metaSequence(
                pressed(term, KeyEvent.ALT_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK, KeyEvent.VK_B, 'b')));
        assertEquals(esc + ".", TerminalPanel.metaSequence(pressed(term, KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_PERIOD, '.')));
        // Not Meta: plain key, Ctrl+Alt (AltGr), Alt with a non-printable key
        assertNull(TerminalPanel.metaSequence(pressed(term, 0, KeyEvent.VK_B, 'b')));
        assertNull(TerminalPanel.metaSequence(
                pressed(term, KeyEvent.ALT_DOWN_MASK | KeyEvent.CTRL_DOWN_MASK, KeyEvent.VK_Q, '@')));
        assertNull(TerminalPanel.metaSequence(
                pressed(term, KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_F4, KeyEvent.CHAR_UNDEFINED)));
        assertNull(TerminalPanel.metaSequence(pressed(term, KeyEvent.ALT_DOWN_MASK, KeyEvent.VK_ENTER, '\n')));
    }
}
