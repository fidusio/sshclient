package io.xlogistx.jssh.terminal;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the VT100/ANSI parser in {@link TerminalPanel}. Runs headless so no
 * window is shown; only the in-memory screen model is exercised.
 */
public class TerminalPanelTest {

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

    @Test
    public void plainTextLandsOnScreen() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, "hello world");
        assertEquals("hello world", firstLine(term));
    }

    @Test
    public void carriageReturnOverwrites() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, "hello\rHELLO");
        assertEquals("HELLO", firstLine(term));
    }

    @Test
    public void cursorPositionEscape() {
        TerminalPanel term = new TerminalPanel(80, 24);
        // Move to row 1 col 1, write X (ESC[1;1H)
        feed(term, "[1;1HX");
        assertTrue(firstLine(term).startsWith("X"));
    }

    @Test
    public void eraseDisplayClearsText() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, "some text");
        feed(term, "[2J");   // erase entire display
        assertEquals("", term.getScreenText().replace("\n", "").trim());
    }

    @Test
    public void nonBmpEmojiKeepsSurrogatePair() {
        TerminalPanel term = new TerminalPanel(80, 24);
        String emoji = new String(Character.toChars(0x1F600)); // grinning face
        feed(term, emoji + "Z");
        // The emoji must survive as its full code point, followed by Z
        assertTrue(firstLine(term).startsWith(emoji), "emoji code point lost");
        assertTrue(firstLine(term).startsWith(emoji + "Z"), "cell after emoji wrong");
    }

    @Test
    public void clearScrollbackResetsSize() {
        TerminalPanel term = new TerminalPanel(80, 24);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            sb.append("line").append(i).append("\r\n");
        }
        feed(term, sb.toString());
        assertTrue(term.getScrollbackSize() > 0, "expected scrollback to accumulate");

        term.clearScrollback();
        assertEquals(0, term.getScrollbackSize());
    }

    @Test
    public void resizeKeepsExistingContent() {
        TerminalPanel term = new TerminalPanel(80, 24);
        feed(term, "keepme");
        term.resize(100, 30);
        assertEquals(100, term.getCols());
        assertEquals(30, term.getRows());
        assertTrue(firstLine(term).startsWith("keepme"));
    }
}
