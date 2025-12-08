package io.xlogistx.jssh.terminal;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.datatransfer.*;
import java.io.*;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * VT100/ANSI Terminal Emulator Component with scrollback
 */
public class TerminalPanel extends JPanel implements KeyListener, MouseListener, MouseMotionListener, MouseWheelListener {
    
    private int cols = 80;
    private int rows = 24;
    private int charWidth = 8;
    private int charHeight = 16;
    
    private char[][] screen;
    private int[][] colors;
    private int[][] bgColors;
    private boolean[][] bold;
    private boolean[][] reverse;  // Reverse video attribute
    
    // Scrollback buffer
    private static final int MAX_SCROLLBACK = 10000;  // Maximum lines to keep
    private List<char[]> scrollbackChars = new ArrayList<>();
    private List<int[]> scrollbackColors = new ArrayList<>();
    private List<int[]> scrollbackBgColors = new ArrayList<>();
    private List<boolean[]> scrollbackBold = new ArrayList<>();
    private List<boolean[]> scrollbackReverse = new ArrayList<>();
    private int scrollOffset = 0;  // How many lines we're scrolled back (0 = at bottom)
    
    private int cursorX = 0;
    private int cursorY = 0;
    private boolean cursorVisible = true;
    private boolean cursorBlink = true;
    
    private int scrollTop = 0;
    private int scrollBottom;
    
    private int savedCursorX = 0;
    private int savedCursorY = 0;
    
    private int currentFg = 7;  // White
    private int currentBg = 0;  // Black
    private boolean currentBold = false;
    private boolean currentReverse = false;  // Reverse video mode
    
    private boolean applicationCursorKeys = false;
    private boolean alternateScreen = false;
    
    // Saved screen for alternate buffer
    private char[][] savedScreen;
    private int[][] savedColors;
    private int[][] savedBgColors;
    private boolean[][] savedBold;
    private boolean[][] savedReverse;
    
    // Selection
    private int selStartX = -1, selStartY = -1;
    private int selEndX = -1, selEndY = -1;
    private boolean selecting = false;
    
    // ANSI escape sequence parsing
    private StringBuilder escapeBuffer = new StringBuilder();
    private boolean inEscape = false;
    private boolean inCSI = false;
    
    // Colors (ANSI 16 colors)
    private static final Color[] ANSI_COLORS = {
        new Color(0, 0, 0),        // 0 Black
        new Color(170, 0, 0),      // 1 Red
        new Color(0, 170, 0),      // 2 Green
        new Color(170, 85, 0),     // 3 Yellow/Brown
        new Color(0, 0, 170),      // 4 Blue
        new Color(170, 0, 170),    // 5 Magenta
        new Color(0, 170, 170),    // 6 Cyan
        new Color(170, 170, 170),  // 7 White
        new Color(85, 85, 85),     // 8 Bright Black
        new Color(255, 85, 85),    // 9 Bright Red
        new Color(85, 255, 85),    // 10 Bright Green
        new Color(255, 255, 85),   // 11 Bright Yellow
        new Color(85, 85, 255),    // 12 Bright Blue
        new Color(255, 85, 255),   // 13 Bright Magenta
        new Color(85, 255, 255),   // 14 Bright Cyan
        new Color(255, 255, 255),  // 15 Bright White
    };
    
    private OutputStream outputStream;
    private TerminalListener listener;
    
    public interface TerminalListener {
        void onTitleChange(String title);
        void onBell();
        void onResize(int cols, int rows);
    }
    
    public TerminalPanel() {
        this(80, 24);
    }
    
    public TerminalPanel(int cols, int rows) {
        this.cols = cols;
        this.rows = rows;
        this.scrollBottom = rows - 1;
        
        initScreen();
        
        setBackground(Color.BLACK);
        setForeground(Color.WHITE);
        setOpaque(true);
        
        // Try to find a good monospace font with Unicode support
        Font terminalFont = findTerminalFont();
        setFont(terminalFont);
        
        // Calculate char dimensions
        FontMetrics fm = getFontMetrics(getFont());
        charWidth = fm.charWidth('M');
        charHeight = fm.getHeight();
        
        // Ensure minimum size
        if (charWidth < 1) charWidth = 8;
        if (charHeight < 1) charHeight = 16;
        
        setPreferredSize(new Dimension(cols * charWidth, rows * charHeight));
        setMinimumSize(new Dimension(40 * charWidth, 10 * charHeight));
        setFocusable(true);
        
        // Disable Tab focus traversal - we want Tab to go to the terminal
        setFocusTraversalKeysEnabled(false);
        
        addKeyListener(this);
        addMouseListener(this);
        addMouseMotionListener(this);
        addMouseWheelListener(this);
        
        // Cursor blink timer
        Timer blinkTimer = new Timer(500, e -> {
            cursorBlink = !cursorBlink;
            repaint();
        });
        blinkTimer.start();
        
        // Request focus when clicked
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                requestFocusInWindow();
            }
        });
    }
    
    /**
     * Find a good monospace font with Unicode/box-drawing character support
     */
    private Font findTerminalFont() {
        // Preferred fonts with good Unicode coverage (in order of preference)
        String[] preferredFonts = {
            "Consolas",                    // Windows - excellent Unicode support
            "DejaVu Sans Mono",            // Linux - great Unicode coverage
            "Liberation Mono",             // Linux alternative
            "Ubuntu Mono",                 // Ubuntu
            "Menlo",                       // macOS
            "Monaco",                      // macOS alternative
            "SF Mono",                     // macOS modern
            "Lucida Console",              // Windows fallback
            "Courier New",                 // Universal fallback
            Font.MONOSPACED                // Java default monospace
        };
        
        java.awt.GraphicsEnvironment ge = java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment();
        java.util.Set<String> availableFonts = new java.util.HashSet<>();
        for (String fontName : ge.getAvailableFontFamilyNames()) {
            availableFonts.add(fontName);
        }
        
        for (String fontName : preferredFonts) {
            if (availableFonts.contains(fontName)) {
                Font font = new Font(fontName, Font.PLAIN, 14);
                // Verify the font can display box-drawing characters
                if (font.canDisplay('─') && font.canDisplay('│') && font.canDisplay('┌')) {
                    return font;
                }
            }
        }
        
        // Fallback to Java's default monospace
        return new Font(Font.MONOSPACED, Font.PLAIN, 14);
    }
    
    private void initScreen() {
        screen = new char[rows][cols];
        colors = new int[rows][cols];
        bgColors = new int[rows][cols];
        bold = new boolean[rows][cols];
        reverse = new boolean[rows][cols];
        
        for (int y = 0; y < rows; y++) {
            Arrays.fill(screen[y], ' ');
            Arrays.fill(colors[y], 7);
            Arrays.fill(bgColors[y], 0);
            Arrays.fill(bold[y], false);
            Arrays.fill(reverse[y], false);
        }
    }
    
    public void setOutputStream(OutputStream out) {
        this.outputStream = out;
    }
    
    public void setTerminalListener(TerminalListener listener) {
        this.listener = listener;
    }
    
    /**
     * Display a message in the terminal (not sent to remote)
     * Used for local notifications like disconnect messages
     */
    public void displayMessage(String message) {
        displayMessage(message, 1); // Default red color
    }
    
    /**
     * Display a message with specified color
     * @param message the message to display
     * @param color ANSI color code (0-15)
     */
    public void displayMessage(String message, int color) {
        // Save current colors
        int savedFg = currentFg;
        int savedBg = currentBg;
        
        // Set message color
        currentFg = color;
        
        // Add newlines before and after for visibility
        processChar('\r');
        processChar('\n');
        
        for (char c : message.toCharArray()) {
            processChar(c);
        }
        
        processChar('\r');
        processChar('\n');
        
        // Restore colors
        currentFg = savedFg;
        currentBg = savedBg;
        
        repaint();
    }
    
    // UTF-8 decoding state
    private int utf8State = 0;      // 0 = expecting first byte, 1-3 = expecting continuation bytes
    private int utf8Char = 0;       // Character being built
    private int utf8Remaining = 0;  // Remaining continuation bytes expected
    
    /**
     * Write data to terminal (from SSH)
     */
    public void write(byte[] data, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            int b = data[i] & 0xff;
            
            // UTF-8 decoding
            if (utf8Remaining > 0) {
                // Expecting continuation byte (10xxxxxx)
                if ((b & 0xC0) == 0x80) {
                    utf8Char = (utf8Char << 6) | (b & 0x3F);
                    utf8Remaining--;
                    if (utf8Remaining == 0) {
                        // Complete character
                        processChar((char) utf8Char);
                    }
                } else {
                    // Invalid continuation, reset and process as new byte
                    utf8Remaining = 0;
                    processUtf8Start(b);
                }
            } else {
                processUtf8Start(b);
            }
        }
        repaint();
    }
    
    private void processUtf8Start(int b) {
        if ((b & 0x80) == 0) {
            // ASCII (0xxxxxxx)
            processChar((char) b);
        } else if ((b & 0xE0) == 0xC0) {
            // 2-byte sequence (110xxxxx)
            utf8Char = b & 0x1F;
            utf8Remaining = 1;
        } else if ((b & 0xF0) == 0xE0) {
            // 3-byte sequence (1110xxxx)
            utf8Char = b & 0x0F;
            utf8Remaining = 2;
        } else if ((b & 0xF8) == 0xF0) {
            // 4-byte sequence (11110xxx)
            utf8Char = b & 0x07;
            utf8Remaining = 3;
        } else {
            // Invalid UTF-8 start byte, display replacement character or skip
            processChar('\uFFFD');
        }
    }
    
    public void write(int c) {
        processChar((char) c);
        repaint();
    }
    
    private void processChar(char c) {
        if (inEscape) {
            processEscape(c);
            return;
        }
        
        switch (c) {
            case 0x07: // Bell
                if (listener != null) listener.onBell();
                break;
            case 0x08: // Backspace
                if (cursorX > 0) cursorX--;
                break;
            case 0x09: // Tab
                cursorX = ((cursorX / 8) + 1) * 8;
                if (cursorX >= cols) cursorX = cols - 1;
                break;
            case 0x0A: // Line feed
                lineFeed();
                break;
            case 0x0D: // Carriage return
                cursorX = 0;
                break;
            case 0x1B: // Escape
                inEscape = true;
                escapeBuffer.setLength(0);
                break;
            default:
                if (c >= 32) {
                    putChar(c);
                }
                break;
        }
    }
    
    private void processEscape(char c) {
        escapeBuffer.append(c);
        String seq = escapeBuffer.toString();
        
        if (seq.length() == 1) {
            if (c == '[') {
                inCSI = true;
                return;
            } else if (c == ']') {
                // OSC sequence
                return;
            } else if (c == '(' || c == ')' || c == '*' || c == '+') {
                // Character set designation - wait for next char
                return;
            } else if (c == '7') {
                // Save cursor
                savedCursorX = cursorX;
                savedCursorY = cursorY;
                inEscape = false;
            } else if (c == '8') {
                // Restore cursor
                cursorX = savedCursorX;
                cursorY = savedCursorY;
                inEscape = false;
            } else if (c == 'M') {
                // Reverse index
                if (cursorY > scrollTop) {
                    cursorY--;
                } else {
                    scrollDown();
                }
                inEscape = false;
            } else if (c == 'D') {
                // Index
                lineFeed();
                inEscape = false;
            } else if (c == 'E') {
                // Next line
                cursorX = 0;
                lineFeed();
                inEscape = false;
            } else if (c == '=') {
                // Application keypad mode
                inEscape = false;
            } else if (c == '>') {
                // Normal keypad mode
                inEscape = false;
            } else if (c == 'c') {
                // Reset terminal
                initScreen();
                cursorX = 0;
                cursorY = 0;
                currentFg = 7;
                currentBg = 0;
                currentBold = false;
                currentReverse = false;
                scrollTop = 0;
                scrollBottom = rows - 1;
                inEscape = false;
            } else {
                inEscape = false;
            }
            return;
        }
        
        // Character set designation sequences (ESC ( X, ESC ) X, etc.)
        if (seq.length() == 2 && (seq.charAt(0) == '(' || seq.charAt(0) == ')' || 
                                   seq.charAt(0) == '*' || seq.charAt(0) == '+')) {
            // Character set selected - we ignore but consume the sequence
            // B = US ASCII, 0 = DEC Special Graphics, etc.
            inEscape = false;
            return;
        }
        
        // OSC sequences (title, etc.)
        if (seq.startsWith("]")) {
            if (c == 0x07 || seq.endsWith("\u001b\\")) {
                processOSC(seq);
                inEscape = false;
            }
            return;
        }
        
        // CSI sequences
        if (inCSI) {
            if (Character.isLetter(c) || c == '@' || c == '`') {
                processCSI(seq);
                inEscape = false;
                inCSI = false;
            }
        }
    }
    
    private void processCSI(String seq) {
        // Remove leading [
        seq = seq.substring(1);
        char cmd = seq.charAt(seq.length() - 1);
        String params = seq.substring(0, seq.length() - 1);
        
        int[] args = parseArgs(params);
        
        switch (cmd) {
            case 'A': // Cursor up
                cursorY = Math.max(0, cursorY - Math.max(1, args[0]));
                break;
            case 'B': // Cursor down
                cursorY = Math.min(rows - 1, cursorY + Math.max(1, args[0]));
                break;
            case 'C': // Cursor forward
                cursorX = Math.min(cols - 1, cursorX + Math.max(1, args[0]));
                break;
            case 'D': // Cursor back
                cursorX = Math.max(0, cursorX - Math.max(1, args[0]));
                break;
            case 'E': // Cursor next line
                cursorX = 0;
                cursorY = Math.min(rows - 1, cursorY + Math.max(1, args[0]));
                break;
            case 'F': // Cursor previous line
                cursorX = 0;
                cursorY = Math.max(0, cursorY - Math.max(1, args[0]));
                break;
            case 'G': // Cursor horizontal absolute
                cursorX = Math.max(0, Math.min(cols - 1, args[0] - 1));
                break;
            case 'H': case 'f': // Cursor position
                cursorY = Math.max(0, Math.min(rows - 1, args[0] - 1));
                cursorX = Math.max(0, Math.min(cols - 1, (args.length > 1 ? args[1] : 1) - 1));
                break;
            case 'J': // Erase display
                eraseDisplay(args[0]);
                break;
            case 'K': // Erase line
                eraseLine(args[0]);
                break;
            case 'L': // Insert lines
                insertLines(Math.max(1, args[0]));
                break;
            case 'M': // Delete lines
                deleteLines(Math.max(1, args[0]));
                break;
            case 'P': // Delete chars
                deleteChars(Math.max(1, args[0]));
                break;
            case 'S': // Scroll up
                for (int i = 0; i < Math.max(1, args[0]); i++) {
                    scrollUp();
                }
                break;
            case 'T': // Scroll down
                for (int i = 0; i < Math.max(1, args[0]); i++) {
                    scrollDown();
                }
                break;
            case 'X': // Erase characters
                int count = Math.max(1, args[0]);
                for (int i = 0; i < count && cursorX + i < cols; i++) {
                    if (cursorY >= 0 && cursorY < rows) {
                        screen[cursorY][cursorX + i] = ' ';
                        colors[cursorY][cursorX + i] = 7;
                        bgColors[cursorY][cursorX + i] = 0;
                        reverse[cursorY][cursorX + i] = false;
                    }
                }
                break;
            case '@': // Insert chars
                insertChars(Math.max(1, args[0]));
                break;
            case 'd': // Cursor vertical absolute
                cursorY = Math.max(0, Math.min(rows - 1, args[0] - 1));
                break;
            case 'm': // SGR - Set Graphics Rendition
                processSGR(args);
                break;
            case 'r': // Set scroll region
                scrollTop = Math.max(0, args[0] - 1);
                scrollBottom = Math.min(rows - 1, (args.length > 1 ? args[1] : rows) - 1);
                cursorX = 0;
                cursorY = 0;
                break;
            case 's': // Save cursor position
                savedCursorX = cursorX;
                savedCursorY = cursorY;
                break;
            case 'u': // Restore cursor position
                cursorX = savedCursorX;
                cursorY = savedCursorY;
                break;
            case 'h': // Set mode
                if (params.startsWith("?")) {
                    processPrivateMode(params.substring(1), true);
                }
                break;
            case 'l': // Reset mode
                if (params.startsWith("?")) {
                    processPrivateMode(params.substring(1), false);
                }
                break;
            case 'c': // Device attributes
                sendResponse("\u001b[?1;2c");
                break;
            case 'n': // Device status
                if (args[0] == 6) {
                    sendResponse("\u001b[" + (cursorY + 1) + ";" + (cursorX + 1) + "R");
                }
                break;
        }
    }
    
    private void processPrivateMode(String params, boolean set) {
        int mode = 0;
        try {
            mode = Integer.parseInt(params.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException e) {
            return;
        }
        switch (mode) {
            case 1: // Application cursor keys
                applicationCursorKeys = set;
                break;
            case 7: // Auto-wrap mode
                // Auto-wrap is always on in our implementation
                break;
            case 25: // Cursor visibility
                cursorVisible = set;
                break;
            case 47: // Alternate screen buffer (older)
            case 1047: // Alternate screen buffer
                if (set && !alternateScreen) {
                    // Save main screen and switch to alternate
                    savedScreen = screen;
                    savedColors = colors;
                    savedBgColors = bgColors;
                    savedBold = bold;
                    savedReverse = reverse;
                    initScreen();
                    alternateScreen = true;
                } else if (!set && alternateScreen) {
                    // Restore main screen
                    if (savedScreen != null) {
                        screen = savedScreen;
                        colors = savedColors;
                        bgColors = savedBgColors;
                        bold = savedBold;
                        reverse = savedReverse;
                    }
                    alternateScreen = false;
                }
                break;
            case 1048: // Save/restore cursor
                if (set) {
                    savedCursorX = cursorX;
                    savedCursorY = cursorY;
                } else {
                    cursorX = savedCursorX;
                    cursorY = savedCursorY;
                }
                break;
            case 1049: // Alternate screen buffer with cursor save/restore
                if (set && !alternateScreen) {
                    // Save cursor and main screen, switch to alternate
                    savedCursorX = cursorX;
                    savedCursorY = cursorY;
                    savedScreen = screen;
                    savedColors = colors;
                    savedBgColors = bgColors;
                    savedBold = bold;
                    savedReverse = reverse;
                    initScreen();
                    alternateScreen = true;
                } else if (!set && alternateScreen) {
                    // Restore main screen and cursor
                    if (savedScreen != null) {
                        screen = savedScreen;
                        colors = savedColors;
                        bgColors = savedBgColors;
                        bold = savedBold;
                        reverse = savedReverse;
                    }
                    cursorX = savedCursorX;
                    cursorY = savedCursorY;
                    alternateScreen = false;
                }
                break;
            case 2004: // Bracketed paste mode - just acknowledge it
                break;
        }
    }
    
    private void processOSC(String seq) {
        // OSC sequences: ]Ps;Pt BEL or ]Ps;Pt ST
        int semicolon = seq.indexOf(';');
        if (semicolon > 0) {
            String code = seq.substring(1, semicolon);
            String text = seq.substring(semicolon + 1);
            // Remove terminator
            text = text.replace("\u0007", "").replace("\u001b\\", "");
            
            if ("0".equals(code) || "2".equals(code)) {
                // Set title
                if (listener != null) {
                    listener.onTitleChange(text);
                }
            }
        }
    }
    
    private int[] parseArgs(String params) {
        if (params.isEmpty() || params.equals("?")) {
            return new int[] { 0 };
        }
        params = params.replace("?", "");
        String[] parts = params.split(";");
        int[] args = new int[Math.max(parts.length, 1)];
        for (int i = 0; i < parts.length; i++) {
            try {
                args[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                args[i] = 0;
            }
        }
        return args;
    }
    
    private void processSGR(int[] args) {
        for (int i = 0; i < args.length; i++) {
            int arg = args[i];
            if (arg == 0) {
                // Reset all attributes
                currentFg = 7;
                currentBg = 0;
                currentBold = false;
                currentReverse = false;
            } else if (arg == 1) {
                currentBold = true;
            } else if (arg == 7) {
                // Reverse video (swap fg/bg when rendering)
                currentReverse = true;
            } else if (arg == 22) {
                currentBold = false;
            } else if (arg == 27) {
                // Reverse off
                currentReverse = false;
            } else if (arg >= 30 && arg <= 37) {
                currentFg = arg - 30;
            } else if (arg == 39) {
                currentFg = 7;
            } else if (arg >= 40 && arg <= 47) {
                currentBg = arg - 40;
            } else if (arg == 49) {
                currentBg = 0;
            } else if (arg >= 90 && arg <= 97) {
                currentFg = arg - 90 + 8;
            } else if (arg >= 100 && arg <= 107) {
                currentBg = arg - 100 + 8;
            } else if (arg == 38 && i + 2 < args.length && args[i + 1] == 5) {
                // 256 color foreground
                currentFg = Math.min(15, args[i + 2]);
                i += 2;
            } else if (arg == 48 && i + 2 < args.length && args[i + 1] == 5) {
                // 256 color background
                currentBg = Math.min(15, args[i + 2]);
                i += 2;
            }
        }
    }
    
    private void putChar(char c) {
        // Ensure cursor is within bounds
        if (cursorY < 0) cursorY = 0;
        if (cursorY >= rows) cursorY = rows - 1;
        
        if (cursorX >= cols) {
            cursorX = 0;
            lineFeed();
        }
        if (cursorX < 0) cursorX = 0;
        
        // Double-check after lineFeed
        if (cursorY >= rows) cursorY = rows - 1;
        
        screen[cursorY][cursorX] = c;
        colors[cursorY][cursorX] = currentBold ? currentFg + 8 : currentFg;
        bgColors[cursorY][cursorX] = currentBg;
        bold[cursorY][cursorX] = currentBold;
        reverse[cursorY][cursorX] = currentReverse;
        
        cursorX++;
    }
    
    private void lineFeed() {
        if (cursorY >= scrollBottom) {
            scrollUp();
        } else {
            cursorY++;
        }
    }
    
    private void scrollUp() {
        // Save top line to scrollback buffer (only if scrolling the whole screen)
        if (scrollTop == 0 && !alternateScreen) {
            scrollbackChars.add(screen[0].clone());
            scrollbackColors.add(colors[0].clone());
            scrollbackBgColors.add(bgColors[0].clone());
            scrollbackBold.add(bold[0].clone());
            scrollbackReverse.add(reverse[0].clone());
            
            // Trim scrollback if too large
            while (scrollbackChars.size() > MAX_SCROLLBACK) {
                scrollbackChars.remove(0);
                scrollbackColors.remove(0);
                scrollbackBgColors.remove(0);
                scrollbackBold.remove(0);
                scrollbackReverse.remove(0);
            }
        }
        
        int safeTop = Math.max(0, Math.min(scrollTop, rows - 1));
        int safeBottom = Math.max(0, Math.min(scrollBottom, rows - 1));
        
        for (int y = safeTop; y < safeBottom && y + 1 < rows; y++) {
            screen[y] = screen[y + 1];
            colors[y] = colors[y + 1];
            bgColors[y] = bgColors[y + 1];
            bold[y] = bold[y + 1];
            reverse[y] = reverse[y + 1];
        }
        if (safeBottom < rows) {
            screen[safeBottom] = new char[cols];
            colors[safeBottom] = new int[cols];
            bgColors[safeBottom] = new int[cols];
            bold[safeBottom] = new boolean[cols];
            reverse[safeBottom] = new boolean[cols];
            Arrays.fill(screen[safeBottom], ' ');
            Arrays.fill(colors[safeBottom], 7);
        }
    }
    
    private void scrollDown() {
        int safeTop = Math.max(0, Math.min(scrollTop, rows - 1));
        int safeBottom = Math.max(0, Math.min(scrollBottom, rows - 1));
        
        for (int y = safeBottom; y > safeTop && y > 0; y--) {
            screen[y] = screen[y - 1];
            colors[y] = colors[y - 1];
            bgColors[y] = bgColors[y - 1];
            bold[y] = bold[y - 1];
            reverse[y] = reverse[y - 1];
        }
        if (safeTop < rows) {
            screen[safeTop] = new char[cols];
            colors[safeTop] = new int[cols];
            bgColors[safeTop] = new int[cols];
            bold[safeTop] = new boolean[cols];
            reverse[safeTop] = new boolean[cols];
            Arrays.fill(screen[safeTop], ' ');
            Arrays.fill(colors[safeTop], 7);
        }
    }
    
    private void eraseDisplay(int mode) {
        // Ensure cursor is within bounds
        int safeY = Math.max(0, Math.min(cursorY, rows - 1));
        
        switch (mode) {
            case 0: // Cursor to end
                eraseLine(0);
                for (int y = safeY + 1; y < rows; y++) {
                    Arrays.fill(screen[y], ' ');
                    Arrays.fill(colors[y], 7);
                    Arrays.fill(bgColors[y], 0);
                    Arrays.fill(reverse[y], false);
                }
                break;
            case 1: // Start to cursor
                eraseLine(1);
                for (int y = 0; y < safeY; y++) {
                    Arrays.fill(screen[y], ' ');
                    Arrays.fill(colors[y], 7);
                    Arrays.fill(bgColors[y], 0);
                    Arrays.fill(reverse[y], false);
                }
                break;
            case 2: case 3: // Entire screen
                for (int y = 0; y < rows; y++) {
                    Arrays.fill(screen[y], ' ');
                    Arrays.fill(colors[y], 7);
                    Arrays.fill(bgColors[y], 0);
                    Arrays.fill(reverse[y], false);
                }
                break;
        }
    }
    
    private void eraseLine(int mode) {
        // Ensure cursor is within bounds
        if (cursorY < 0 || cursorY >= rows) return;
        int safeX = Math.max(0, Math.min(cursorX, cols - 1));
        
        switch (mode) {
            case 0: // Cursor to end
                for (int x = safeX; x < cols; x++) {
                    screen[cursorY][x] = ' ';
                    colors[cursorY][x] = 7;
                    bgColors[cursorY][x] = 0;
                    reverse[cursorY][x] = false;
                }
                break;
            case 1: // Start to cursor
                for (int x = 0; x <= safeX; x++) {
                    screen[cursorY][x] = ' ';
                    colors[cursorY][x] = 7;
                    bgColors[cursorY][x] = 0;
                    reverse[cursorY][x] = false;
                }
                break;
            case 2: // Entire line
                Arrays.fill(screen[cursorY], ' ');
                Arrays.fill(colors[cursorY], 7);
                Arrays.fill(bgColors[cursorY], 0);
                Arrays.fill(reverse[cursorY], false);
                break;
        }
    }
    
    private void insertLines(int n) {
        if (cursorY < 0 || cursorY >= rows) return;
        int safeBottom = Math.min(scrollBottom, rows - 1);
        
        for (int i = 0; i < n; i++) {
            for (int y = safeBottom; y > cursorY && y > 0; y--) {
                screen[y] = screen[y - 1];
                colors[y] = colors[y - 1];
                bgColors[y] = bgColors[y - 1];
                reverse[y] = reverse[y - 1];
            }
            screen[cursorY] = new char[cols];
            colors[cursorY] = new int[cols];
            bgColors[cursorY] = new int[cols];
            reverse[cursorY] = new boolean[cols];
            Arrays.fill(screen[cursorY], ' ');
            Arrays.fill(colors[cursorY], 7);
        }
    }
    
    private void deleteLines(int n) {
        if (cursorY < 0 || cursorY >= rows) return;
        int safeBottom = Math.min(scrollBottom, rows - 1);
        
        for (int i = 0; i < n; i++) {
            for (int y = cursorY; y < safeBottom && y + 1 < rows; y++) {
                screen[y] = screen[y + 1];
                colors[y] = colors[y + 1];
                bgColors[y] = bgColors[y + 1];
                reverse[y] = reverse[y + 1];
            }
            if (safeBottom < rows) {
                screen[safeBottom] = new char[cols];
                colors[safeBottom] = new int[cols];
                bgColors[safeBottom] = new int[cols];
                reverse[safeBottom] = new boolean[cols];
                Arrays.fill(screen[safeBottom], ' ');
                Arrays.fill(colors[safeBottom], 7);
            }
        }
    }
    
    private void insertChars(int n) {
        if (cursorY < 0 || cursorY >= rows) return;
        int safeX = Math.max(0, Math.min(cursorX, cols - 1));
        
        for (int x = cols - 1; x >= safeX + n && x >= n; x--) {
            screen[cursorY][x] = screen[cursorY][x - n];
            colors[cursorY][x] = colors[cursorY][x - n];
            bgColors[cursorY][x] = bgColors[cursorY][x - n];
            reverse[cursorY][x] = reverse[cursorY][x - n];
        }
        for (int x = safeX; x < safeX + n && x < cols; x++) {
            screen[cursorY][x] = ' ';
            colors[cursorY][x] = 7;
            bgColors[cursorY][x] = 0;
            reverse[cursorY][x] = false;
        }
    }
    
    private void deleteChars(int n) {
        if (cursorY < 0 || cursorY >= rows) return;
        int safeX = Math.max(0, Math.min(cursorX, cols - 1));
        
        for (int x = safeX; x < cols - n && x + n < cols; x++) {
            screen[cursorY][x] = screen[cursorY][x + n];
            colors[cursorY][x] = colors[cursorY][x + n];
            bgColors[cursorY][x] = bgColors[cursorY][x + n];
        }
        for (int x = Math.max(safeX, cols - n); x < cols; x++) {
            screen[cursorY][x] = ' ';
            colors[cursorY][x] = 7;
            bgColors[cursorY][x] = 0;
        }
    }
    
    private void sendResponse(String response) {
        if (outputStream != null) {
            try {
                outputStream.write(response.getBytes());
                outputStream.flush();
            } catch (IOException e) { }
        }
    }
    

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, 
                           RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        
        FontMetrics fm = g.getFontMetrics();
        int ascent = fm.getAscent();
        
        int scrollbackSize = scrollbackChars.size();
        
        for (int y = 0; y < rows; y++) {
            // Calculate which line to display based on scroll offset
            int displayLine = y - scrollOffset;
            
            char[] lineChars;
            int[] lineColors;
            int[] lineBgColors;
            boolean[] lineBold;
            boolean[] lineReverse;
            
            if (displayLine < 0) {
                // Drawing from scrollback buffer
                int scrollbackIndex = scrollbackSize + displayLine;
                if (scrollbackIndex >= 0 && scrollbackIndex < scrollbackSize) {
                    lineChars = scrollbackChars.get(scrollbackIndex);
                    lineColors = scrollbackColors.get(scrollbackIndex);
                    lineBgColors = scrollbackBgColors.get(scrollbackIndex);
                    lineBold = scrollbackBold.get(scrollbackIndex);
                    lineReverse = scrollbackReverse.size() > scrollbackIndex ? 
                                  scrollbackReverse.get(scrollbackIndex) : new boolean[cols];
                } else {
                    // Empty line
                    lineChars = new char[cols];
                    lineColors = new int[cols];
                    lineBgColors = new int[cols];
                    lineBold = new boolean[cols];
                    lineReverse = new boolean[cols];
                    Arrays.fill(lineChars, ' ');
                    Arrays.fill(lineColors, 7);
                }
            } else if (displayLine >= 0 && displayLine < rows && 
                       screen != null && reverse != null &&
                       displayLine < screen.length && displayLine < reverse.length) {
                // Drawing from current screen
                lineChars = screen[displayLine];
                lineColors = colors[displayLine];
                lineBgColors = bgColors[displayLine];
                lineBold = bold[displayLine];
                lineReverse = reverse[displayLine];
            } else {
                // Beyond screen or arrays not ready - skip this line
                continue;
            }
            
            for (int x = 0; x < cols && x < lineChars.length; x++) {
                int px = x * charWidth;
                int py = y * charHeight;
                
                // Check selection
                boolean selected = isSelected(x, y);
                
                // Get colors, handling reverse video
                int fg = lineColors[x] % 16;
                int bg = lineBgColors[x] % 16;
                boolean isReverse = lineReverse != null && x < lineReverse.length && lineReverse[x];
                
                // Swap fg/bg if reverse video
                if (isReverse) {
                    int tmp = fg;
                    fg = bg;
                    bg = tmp;
                    // Make sure we have visible colors
                    if (fg == bg) {
                        fg = 0;  // Black text
                        bg = 7;  // White background
                    }
                }
                
                // Draw background
                Color bgColor = selected ? ANSI_COLORS[7] : ANSI_COLORS[bg];
                g.setColor(bgColor);
                g.fillRect(px, py, charWidth, charHeight);
                
                // Draw character
                char c = lineChars[x];
                if (c != ' ' && c != 0) {
                    Color fgColor = selected ? ANSI_COLORS[0] : ANSI_COLORS[fg];
                    g.setColor(fgColor);
                    g.drawString(String.valueOf(c), px, py + ascent);
                }
            }
        }
        
        // Draw cursor (only when not scrolled back)
        if (scrollOffset == 0 && cursorVisible && cursorBlink && cursorY < rows && cursorX < cols) {
            g.setColor(ANSI_COLORS[7]);
            g.fillRect(cursorX * charWidth, cursorY * charHeight, charWidth, charHeight);
            g.setColor(ANSI_COLORS[0]);
            char c = screen[cursorY][cursorX];
            g.drawString(String.valueOf(c), cursorX * charWidth, cursorY * charHeight + ascent);
        }
        
        // Draw scroll indicator if scrolled back
        if (scrollOffset > 0) {
            g.setColor(new Color(100, 100, 100, 200));
            String indicator = "↑ " + scrollOffset + " lines ↑";
            int indicatorWidth = fm.stringWidth(indicator);
            g.fillRect(getWidth() - indicatorWidth - 20, 5, indicatorWidth + 10, charHeight);
            g.setColor(Color.WHITE);
            g.drawString(indicator, getWidth() - indicatorWidth - 15, 5 + ascent);
        }
    }
    
    private boolean isSelected(int x, int y) {
        if (selStartX < 0 || selStartY < 0) return false;
        
        int startPos = selStartY * cols + selStartX;
        int endPos = selEndY * cols + selEndX;
        int pos = y * cols + x;
        
        if (startPos > endPos) {
            int tmp = startPos;
            startPos = endPos;
            endPos = tmp;
        }
        
        return pos >= startPos && pos <= endPos;
    }
    
    // Keyboard handling
    @Override
    public void keyTyped(KeyEvent e) {
        // Don't handle if control/alt is pressed (handled in keyPressed)
        if (e.isControlDown() || e.isAltDown()) {
            return;
        }
        
        char c = e.getKeyChar();
        
        // Only handle printable characters (space and above, excluding DEL 0x7F)
        // All special keys (Delete, Backspace, Enter, Tab, arrows, function keys, etc.)
        // are handled in keyPressed
        if (c >= ' ' && c != 0x7f && c != KeyEvent.CHAR_UNDEFINED && outputStream != null) {
            // Scroll to bottom when typing
            if (scrollOffset > 0) {
                scrollToBottom();
            }
            try {
                outputStream.write(c);
                outputStream.flush();
            } catch (IOException ex) { }
        }
    }
    
    @Override
    public void keyPressed(KeyEvent e) {
        String seq = null;
        String prefix = applicationCursorKeys ? "\u001bO" : "\u001b[";
        
        int keyCode = e.getKeyCode();
        
        // Handle Tab key - send to terminal for completion
        if (keyCode == KeyEvent.VK_TAB) {
            e.consume(); // Prevent focus traversal
            if (outputStream != null) {
                try {
                    outputStream.write('\t');
                    outputStream.flush();
                } catch (IOException ex) { }
            }
            return;
        }
        
        // Handle Enter key
        if (keyCode == KeyEvent.VK_ENTER) {
            e.consume();
            if (outputStream != null) {
                try {
                    outputStream.write('\r');
                    outputStream.flush();
                } catch (IOException ex) { }
            }
            return;
        }
        
        // Handle Backspace
        if (keyCode == KeyEvent.VK_BACK_SPACE) {
            e.consume();
            if (outputStream != null) {
                try {
                    outputStream.write(0x7f); // DEL character for backspace
                    outputStream.flush();
                } catch (IOException ex) { }
            }
            return;
        }
        
        // Handle Escape
        if (keyCode == KeyEvent.VK_ESCAPE) {
            if (outputStream != null) {
                try {
                    outputStream.write(0x1b);
                    outputStream.flush();
                } catch (IOException ex) { }
            }
            e.consume();
            return;
        }
        
        // Handle Delete key explicitly to prevent double-firing
        if (keyCode == KeyEvent.VK_DELETE) {
            if (outputStream != null) {
                if (scrollOffset > 0) {
                    scrollToBottom();
                }
                try {
                    outputStream.write("\u001b[3~".getBytes());
                    outputStream.flush();
                } catch (IOException ex) { }
            }
            e.consume();
            return;
        }
        
        // Handle Shift+PageUp/PageDown for scrollback
        if (e.isShiftDown()) {
            if (keyCode == KeyEvent.VK_PAGE_UP) {
                scrollUp(rows - 1);
                e.consume();
                return;
            } else if (keyCode == KeyEvent.VK_PAGE_DOWN) {
                scrollDown(rows - 1);
                e.consume();
                return;
            } else if (keyCode == KeyEvent.VK_HOME) {
                // Scroll to top of scrollback
                scrollOffset = scrollbackChars.size();
                repaint();
                e.consume();
                return;
            } else if (keyCode == KeyEvent.VK_END) {
                // Scroll to bottom (current)
                scrollToBottom();
                e.consume();
                return;
            }
        }
        
        switch (keyCode) {
            case KeyEvent.VK_UP:    seq = prefix + "A"; break;
            case KeyEvent.VK_DOWN:  seq = prefix + "B"; break;
            case KeyEvent.VK_RIGHT: seq = prefix + "C"; break;
            case KeyEvent.VK_LEFT:  seq = prefix + "D"; break;
            case KeyEvent.VK_HOME:  seq = "\u001b[H"; break;
            case KeyEvent.VK_END:   seq = "\u001b[F"; break;
            case KeyEvent.VK_PAGE_UP:   seq = "\u001b[5~"; break;
            case KeyEvent.VK_PAGE_DOWN: seq = "\u001b[6~"; break;
            case KeyEvent.VK_INSERT:    seq = "\u001b[2~"; break;
            case KeyEvent.VK_F1:  seq = "\u001bOP"; break;
            case KeyEvent.VK_F2:  seq = "\u001bOQ"; break;
            case KeyEvent.VK_F3:  seq = "\u001bOR"; break;
            case KeyEvent.VK_F4:  seq = "\u001bOS"; break;
            case KeyEvent.VK_F5:  seq = "\u001b[15~"; break;
            case KeyEvent.VK_F6:  seq = "\u001b[17~"; break;
            case KeyEvent.VK_F7:  seq = "\u001b[18~"; break;
            case KeyEvent.VK_F8:  seq = "\u001b[19~"; break;
            case KeyEvent.VK_F9:  seq = "\u001b[20~"; break;
            case KeyEvent.VK_F10: seq = "\u001b[21~"; break;
            case KeyEvent.VK_F11: seq = "\u001b[23~"; break;
            case KeyEvent.VK_F12: seq = "\u001b[24~"; break;
        }
        
        // Handle Ctrl+Shift+C for copy (always copy)
        if (e.isControlDown() && e.isShiftDown() && keyCode == KeyEvent.VK_C) {
            copySelection();
            e.consume();
            return;
        }
        
        // Handle Ctrl+Shift+V for paste
        if (e.isControlDown() && e.isShiftDown() && keyCode == KeyEvent.VK_V) {
            paste();
            e.consume();
            return;
        }
        
        // Handle Ctrl+C - copy if selection exists, otherwise send interrupt
        if (e.isControlDown() && !e.isShiftDown() && keyCode == KeyEvent.VK_C) {
            if (hasSelection()) {
                copySelection();
                clearSelection();
                e.consume();
                return;
            } else {
                // Send Ctrl+C (interrupt)
                seq = String.valueOf((char) 3);
            }
        }
        
        // Handle Ctrl+V for paste
        if (e.isControlDown() && !e.isShiftDown() && keyCode == KeyEvent.VK_V) {
            paste();
            e.consume();
            return;
        }
        
        // Handle other Ctrl+key combinations
        if (e.isControlDown() && !e.isShiftDown() && keyCode >= KeyEvent.VK_A && keyCode <= KeyEvent.VK_Z) {
            if (keyCode != KeyEvent.VK_C && keyCode != KeyEvent.VK_V) { // Already handled above
                char ctrlChar = (char)(keyCode - KeyEvent.VK_A + 1);
                seq = String.valueOf(ctrlChar);
            }
        }
        
        if (seq != null && outputStream != null) {
            // Scroll to bottom when interacting with terminal
            if (scrollOffset > 0) {
                scrollToBottom();
            }
            try {
                outputStream.write(seq.getBytes());
                outputStream.flush();
                e.consume();
            } catch (IOException ex) { }
        }
    }
    
    @Override
    public void keyReleased(KeyEvent e) { }
    
    // Mouse handling for selection
    @Override
    public void mousePressed(MouseEvent e) {
        requestFocusInWindow();
        
        // Right-click paste
        if (SwingUtilities.isRightMouseButton(e)) {
            paste();
            return;
        }
        
        // Middle-click paste
        if (SwingUtilities.isMiddleMouseButton(e)) {
            paste();
            return;
        }
        
        // Left-click starts selection
        if (SwingUtilities.isLeftMouseButton(e)) {
            // Clear previous selection on single click
            if (e.getClickCount() == 1) {
                selStartX = e.getX() / charWidth;
                selStartY = e.getY() / charHeight;
                selEndX = selStartX;
                selEndY = selStartY;
                selecting = true;
            }
            repaint();
        }
    }
    
    @Override
    public void mouseReleased(MouseEvent e) {
        if (selecting && SwingUtilities.isLeftMouseButton(e)) {
            selecting = false;
            // Auto-copy selection to clipboard when mouse is released
            if (hasSelection()) {
                copySelection();
            }
        }
    }
    
    @Override
    public void mouseDragged(MouseEvent e) {
        if (selecting) {
            selEndX = Math.min(cols - 1, Math.max(0, e.getX() / charWidth));
            selEndY = Math.min(rows - 1, Math.max(0, e.getY() / charHeight));
            repaint();
        }
    }
    
    @Override
    public void mouseClicked(MouseEvent e) {
        if (SwingUtilities.isLeftMouseButton(e)) {
            if (e.getClickCount() == 2) {
                // Double click - select word
                selectWord(e.getX() / charWidth, e.getY() / charHeight);
                copySelection(); // Auto-copy
            } else if (e.getClickCount() == 3) {
                // Triple click - select line
                selectLine(e.getY() / charHeight);
                copySelection(); // Auto-copy
            }
        }
    }
    
    @Override
    public void mouseEntered(MouseEvent e) { }
    @Override
    public void mouseExited(MouseEvent e) { }
    @Override
    public void mouseMoved(MouseEvent e) { }
    
    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        int notches = e.getWheelRotation();
        int scrollAmount = 3;  // Lines to scroll per notch
        
        if (notches < 0) {
            // Scroll up (back in history)
            scrollOffset = Math.min(scrollOffset + scrollAmount, scrollbackChars.size());
        } else {
            // Scroll down (toward current)
            scrollOffset = Math.max(scrollOffset - scrollAmount, 0);
        }
        repaint();
    }
    
    /**
     * Scroll to bottom (current output)
     */
    public void scrollToBottom() {
        scrollOffset = 0;
        repaint();
    }
    
    /**
     * Scroll up by specified number of lines
     */
    public void scrollUp(int lines) {
        scrollOffset = Math.min(scrollOffset + lines, scrollbackChars.size());
        repaint();
    }
    
    /**
     * Scroll down by specified number of lines
     */
    public void scrollDown(int lines) {
        scrollOffset = Math.max(scrollOffset - lines, 0);
        repaint();
    }
    
    /**
     * Get scrollback buffer size
     */
    public int getScrollbackSize() {
        return scrollbackChars.size();
    }
    
    /**
     * Clear scrollback buffer
     */
    public void clearScrollback() {
        scrollbackChars.clear();
        scrollbackColors.clear();
        scrollbackBgColors.clear();
        scrollbackBold.clear();
        scrollOffset = 0;
        repaint();
    }
    
    private void selectLine(int y) {
        if (y >= rows) return;
        selStartX = 0;
        selEndX = cols - 1;
        selStartY = selEndY = y;
        repaint();
    }
    
    private void selectWord(int x, int y) {
        if (y >= rows || x >= cols) return;
        
        // Find word boundaries
        int start = x, end = x;
        while (start > 0 && !Character.isWhitespace(screen[y][start - 1])) start--;
        while (end < cols - 1 && !Character.isWhitespace(screen[y][end + 1])) end++;
        
        selStartX = start;
        selEndX = end;
        selStartY = selEndY = y;
        repaint();
    }
    
    private boolean hasSelection() {
        if (selStartX < 0 || selStartY < 0) return false;
        return !(selStartX == selEndX && selStartY == selEndY);
    }
    
    private void clearSelection() {
        selStartX = selStartY = selEndX = selEndY = -1;
        repaint();
    }
    
    private void copySelection() {
        StringBuilder sb = new StringBuilder();
        
        int sy = selStartY, sx = selStartX;
        int ey = selEndY, ex = selEndX;
        
        if (sy > ey || (sy == ey && sx > ex)) {
            int ty = sy, tx = sx;
            sy = ey; sx = ex;
            ey = ty; ex = tx;
        }
        
        int scrollbackSize = scrollbackChars.size();
        
        for (int y = sy; y <= ey; y++) {
            int startX = (y == sy) ? sx : 0;
            int endX = (y == ey) ? ex : cols - 1;
            
            // Calculate which line to read based on scroll offset
            int displayLine = y - scrollOffset;
            char[] lineChars;
            
            if (displayLine < 0) {
                // Reading from scrollback buffer
                int scrollbackIndex = scrollbackSize + displayLine;
                if (scrollbackIndex >= 0 && scrollbackIndex < scrollbackSize) {
                    lineChars = scrollbackChars.get(scrollbackIndex);
                } else {
                    lineChars = new char[cols];
                    Arrays.fill(lineChars, ' ');
                }
            } else if (displayLine < rows) {
                // Reading from current screen
                lineChars = screen[displayLine];
            } else {
                continue;
            }
            
            for (int x = startX; x <= endX && x < lineChars.length; x++) {
                char c = lineChars[x];
                sb.append(c != 0 ? c : ' ');
            }
            if (y < ey) sb.append('\n');
        }
        
        String text = sb.toString().stripTrailing();
        
        if (!text.isEmpty()) {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(new StringSelection(text), null);
        }
    }
    
    private void paste() {
        try {
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            String text = (String) clipboard.getData(DataFlavor.stringFlavor);
            if (text != null && outputStream != null) {
                // Clear selection after paste
                clearSelection();
                // Scroll to bottom when pasting
                if (scrollOffset > 0) {
                    scrollToBottom();
                }
                outputStream.write(text.getBytes());
                outputStream.flush();
            }
        } catch (Exception e) { }
    }
    
    public void resize(int newCols, int newRows) {
        if (newCols == cols && newRows == rows) return;
        
        char[][] newScreen = new char[newRows][newCols];
        int[][] newColors = new int[newRows][newCols];
        int[][] newBgColors = new int[newRows][newCols];
        boolean[][] newBold = new boolean[newRows][newCols];
        boolean[][] newReverse = new boolean[newRows][newCols];
        
        for (int y = 0; y < newRows; y++) {
            Arrays.fill(newScreen[y], ' ');
            Arrays.fill(newColors[y], 7);
            Arrays.fill(newBgColors[y], 0);
        }
        
        int copyRows = Math.min(rows, newRows);
        int copyCols = Math.min(cols, newCols);
        
        for (int y = 0; y < copyRows; y++) {
            System.arraycopy(screen[y], 0, newScreen[y], 0, copyCols);
            System.arraycopy(colors[y], 0, newColors[y], 0, copyCols);
            System.arraycopy(bgColors[y], 0, newBgColors[y], 0, copyCols);
            System.arraycopy(bold[y], 0, newBold[y], 0, copyCols);
            System.arraycopy(reverse[y], 0, newReverse[y], 0, copyCols);
        }
        
        screen = newScreen;
        colors = newColors;
        bgColors = newBgColors;
        bold = newBold;
        reverse = newReverse;
        
        cols = newCols;
        rows = newRows;
        scrollBottom = rows - 1;
        
        cursorX = Math.min(cursorX, cols - 1);
        cursorY = Math.min(cursorY, rows - 1);
        
        setPreferredSize(new Dimension(cols * charWidth, rows * charHeight));
        
        if (listener != null) {
            listener.onResize(cols, rows);
        }
        
        repaint();
    }
    
    public int getCols() { return cols; }
    public int getRows() { return rows; }
    public int getCharWidth() { return charWidth; }
    public int getCharHeight() { return charHeight; }
    
    public void clear() {
        initScreen();
        cursorX = 0;
        cursorY = 0;
        repaint();
    }
    
    public String getScreenText() {
        StringBuilder sb = new StringBuilder();
        for (int y = 0; y < rows; y++) {
            sb.append(new String(screen[y]).stripTrailing());
            if (y < rows - 1) sb.append('\n');
        }
        return sb.toString();
    }
}
