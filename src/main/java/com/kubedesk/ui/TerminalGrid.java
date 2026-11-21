package com.kubedesk.ui;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * A small VT100/xterm terminal emulator rendered on a Canvas. It sizes its character grid to the
 * available space (reporting every change through {@link #setOnGridResize}), supports the alternate
 * screen (top/vim/less), scroll regions, 16/256/true colour, scrollback with the mouse wheel and
 * mouse selection + copy/paste.
 */
public final class TerminalGrid extends Region {
    private static final int MAX_SCROLLBACK = 5000;

    private static final Color[] ANSI = {
            Color.web("#000000"), Color.web("#cd3131"), Color.web("#0dbc79"), Color.web("#e5e510"),
            Color.web("#2472c8"), Color.web("#bc3fbc"), Color.web("#11a8cd"), Color.web("#e5e5e5"),
            Color.web("#666666"), Color.web("#f14c4c"), Color.web("#23d18b"), Color.web("#f5f543"),
            Color.web("#3b8eea"), Color.web("#d670d6"), Color.web("#29b8db"), Color.web("#ffffff"),
    };
    private static final Color DEFAULT_FG = Color.web("#e0e0e0");
    private static final Color DEFAULT_BG = Color.web("#1e1e1e");
    private static final Color CURSOR_COLOR = Color.web("#4fc3f7");
    private static final Color SELECTION_COLOR = Color.web("#4fc3f7", 0.35);

    private static final int BOLD = 1, REVERSE = 2, UNDERLINE = 4;

    private static final int GROUND = 0, ESC = 1, ESC_INT = 2, CSI = 3, OSC = 4, OSC_ESC = 5;

    /** One screen buffer: parallel row arrays so scrolling is just reference rotation. */
    private static final class Screen {
        final int cols, rows;
        final char[][] ch;
        final Color[][] fg, bg;
        final byte[][] fl;

        Screen(int cols, int rows) {
            this.cols = cols;
            this.rows = rows;
            ch = new char[rows][cols];
            fg = new Color[rows][cols];
            bg = new Color[rows][cols];
            fl = new byte[rows][cols];
            for (int r = 0; r < rows; r++) {
                clear(r, 0, cols, null);
            }
        }

        void clear(int r, int from, int to, Color blankBg) {
            for (int c = Math.max(0, from); c < Math.min(cols, to); c++) {
                ch[r][c] = ' ';
                fg[r][c] = null;
                bg[r][c] = blankBg;
                fl[r][c] = 0;
            }
        }
    }

    private record Line(char[] ch, Color[] fg, Color[] bg, byte[] fl) { }

    private final Canvas canvas = new Canvas();
    private final Font font;
    private final Font fontBold;
    private double cellW, cellH, baseline;

    private Screen main, alt, s;
    private boolean altActive;
    private int cols = 80, rows = 24;
    private int row, col;
    private boolean wrapNext;
    private Color curFg, curBg;
    private int curFl;
    private int top, bot; // scroll region (inclusive)
    private boolean autowrap = true, appCursor = false, cursorVisible = true;

    private int sRow, sCol, sFl; // ESC 7 / CSI s
    private Color sFg, sBg;
    private int aRow, aCol;      // saved by ?1049h

    private final List<Line> scrollback = new ArrayList<>();
    private int viewOffset = 0;

    private int state = GROUND;
    private final StringBuilder csiBuf = new StringBuilder();

    // selection in absolute line coordinates (scrollback lines first, then screen rows)
    private int selAr = -1, selAc, selHr, selHc;
    private boolean dragged;

    private BiConsumer<Integer, Integer> onGridResize;
    private Consumer<String> onPaste;
    private boolean renderQueued;

    public TerminalGrid() {
        String family = Font.getFamilies().contains("Consolas") ? "Consolas" : "Monospaced";
        font = Font.font(family, FontWeight.NORMAL, 14);
        fontBold = Font.font(family, FontWeight.BOLD, 14);
        Text t = new Text("MMMMMMMMMM");
        t.setFont(font);
        cellW = t.getLayoutBounds().getWidth() / 10.0;
        cellH = Math.ceil(t.getLayoutBounds().getHeight());
        baseline = t.getBaselineOffset();

        main = new Screen(cols, rows);
        alt = new Screen(cols, rows);
        s = main;
        top = 0;
        bot = rows - 1;

        canvas.setManaged(false);
        getChildren().add(canvas);
        setMinSize(0, 0);
        setPrefSize(800, 400);
        setFocusTraversable(true);
        focusedProperty().addListener((o, a, b) -> requestRender());

        setOnMousePressed(this::mousePressed);
        setOnMouseDragged(this::mouseDragged);
        setOnMouseReleased(this::mouseReleased);
        setOnScroll(e -> {
            if (altActive || e.getDeltaY() == 0) {
                return;
            }
            int step = e.getDeltaY() > 0 ? 3 : -3;
            viewOffset = Math.max(0, Math.min(scrollback.size(), viewOffset + step));
            requestRender();
            e.consume();
        });
    }

    // ------------------------------------------------------------------ public API

    public int getCols() {
        return cols;
    }

    public int getRows() {
        return rows;
    }

    public void setOnGridResize(BiConsumer<Integer, Integer> cb) {
        this.onGridResize = cb;
    }

    public void setOnPaste(Consumer<String> cb) {
        this.onPaste = cb;
    }

    public boolean isAppCursorKeys() {
        return appCursor;
    }

    public void scrollToBottom() {
        if (viewOffset != 0) {
            viewOffset = 0;
            requestRender();
        }
    }

    /** Feed decoded terminal output. Must be called on the FX thread. */
    public void write(String text) {
        try {
            text.codePoints().forEach(this::feed);
        } catch (RuntimeException e) {
            state = GROUND; // never leave the parser wedged
        }
        requestRender();
    }

    public void clear() {
        for (int r = 0; r < rows; r++) {
            s.clear(r, 0, cols, null);
        }
        row = 0;
        col = 0;
        requestRender();
    }

    public boolean hasSelection() {
        return selAr >= 0 && !(selAr == selHr && selAc == selHc);
    }

    public void clearSelection() {
        selAr = -1;
        requestRender();
    }

    public String selectedText() {
        if (!hasSelection()) {
            return "";
        }
        int r1 = selAr, c1 = selAc, r2 = selHr, c2 = selHc;
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            int tr = r1, tc = c1;
            r1 = r2; c1 = c2; r2 = tr; c2 = tc;
        }
        StringBuilder sb = new StringBuilder();
        for (int r = r1; r <= r2; r++) {
            Line ln = lineAt(r);
            if (ln == null) {
                continue;
            }
            int from = (r == r1) ? c1 : 0;
            int to = (r == r2) ? c2 : ln.ch().length - 1;
            to = Math.min(to, ln.ch().length - 1);
            StringBuilder lineSb = new StringBuilder();
            for (int c = from; c <= to; c++) {
                lineSb.append(ln.ch()[c]);
            }
            int end = lineSb.length();
            while (end > 0 && lineSb.charAt(end - 1) == ' ') {
                end--;
            }
            sb.append(lineSb, 0, end);
            if (r < r2) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    /** Copy the selection to the system clipboard. Returns false when nothing was selected. */
    public boolean copySelection() {
        if (!hasSelection()) {
            return false;
        }
        ClipboardContent cc = new ClipboardContent();
        cc.putString(selectedText());
        Clipboard.getSystemClipboard().setContent(cc);
        return true;
    }

    /** Paste the clipboard text through the paste handler (as raw terminal input). */
    public void paste() {
        String txt = Clipboard.getSystemClipboard().getString();
        if (txt != null && !txt.isEmpty() && onPaste != null) {
            onPaste.accept(txt.replace("\r\n", "\r").replace('\n', '\r'));
        }
    }

    /**
     * Translate a KEY_PRESSED event into terminal input (or a copy/paste action) and hand the bytes
     * to {@code out}. Printable characters are NOT handled here: they arrive as KEY_TYPED events
     * ({@link #handleKeyTyped}). Consumes the event when it was handled.
     */
    public void handleKeyPressed(javafx.scene.input.KeyEvent e, Consumer<byte[]> out) {
        javafx.scene.input.KeyCode code = e.getCode();
        if (code == null) {
            return;
        }
        boolean ctrl = e.isControlDown();
        boolean shift = e.isShiftDown();
        boolean modifierOnly = code == javafx.scene.input.KeyCode.SHIFT
                || code == javafx.scene.input.KeyCode.CONTROL
                || code == javafx.scene.input.KeyCode.ALT
                || code == javafx.scene.input.KeyCode.META;

        // Copy / paste. Ctrl+C copies only when text is selected, so ^C still interrupts otherwise.
        if (ctrl && code == javafx.scene.input.KeyCode.C && (shift || hasSelection())
                || ctrl && code == javafx.scene.input.KeyCode.INSERT) {
            copySelection();
            clearSelection();
            e.consume();
            return;
        }
        if (ctrl && code == javafx.scene.input.KeyCode.V || shift && code == javafx.scene.input.KeyCode.INSERT) {
            paste();
            e.consume();
            return;
        }
        if (!modifierOnly) {
            scrollToBottom();
        }

        String csiP = appCursor ? "\u001bO" : "\u001b[";
        String seq = switch (code) {
            case ENTER -> "\r";
            case BACK_SPACE -> "\u007f";
            case TAB -> shift ? "\u001b[Z" : "\t";
            case ESCAPE -> "\u001b";
            case UP -> csiP + "A";
            case DOWN -> csiP + "B";
            case RIGHT -> csiP + "C";
            case LEFT -> csiP + "D";
            case HOME -> csiP + "H";
            case END -> csiP + "F";
            case PAGE_UP -> "\u001b[5~";
            case PAGE_DOWN -> "\u001b[6~";
            case INSERT -> "\u001b[2~";
            case DELETE -> "\u001b[3~";
            case F1 -> "\u001bOP";
            case F2 -> "\u001bOQ";
            case F3 -> "\u001bOR";
            case F4 -> "\u001bOS";
            case F5 -> "\u001b[15~";
            case F6 -> "\u001b[17~";
            case F7 -> "\u001b[18~";
            case F8 -> "\u001b[19~";
            case F9 -> "\u001b[20~";
            case F10 -> "\u001b[21~";
            case F11 -> "\u001b[23~";
            case F12 -> "\u001b[24~";
            default -> null;
        };
        if (seq == null && ctrl && !e.isAltDown() && code.isLetterKey()) {
            int c = Character.toLowerCase(code.getName().charAt(code.getName().length() - 1));
            seq = String.valueOf((char) ((c - 'a') + 1));
        }
        if (seq != null) {
            out.accept(seq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            e.consume();
        }
    }

    /** Forward printable characters (letters, digits, symbols, IME text) from KEY_TYPED events. */
    public void handleKeyTyped(javafx.scene.input.KeyEvent e, Consumer<byte[]> out) {
        if (e.isControlDown() || e.isMetaDown()) {
            return;
        }
        String ch = e.getCharacter();
        if (ch != null && !ch.isEmpty() && ch.chars().allMatch(c -> c >= 0x20 && c != 0x7f)) {
            out.accept(ch.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            e.consume();
        }
    }

    /** Whole buffer (scrollback + screen) as plain text. */
    public String toText() {
        StringBuilder sb = new StringBuilder();
        int total = (altActive ? 0 : scrollback.size()) + rows;
        for (int a = 0; a < total; a++) {
            Line ln = lineAt(a);
            int end = ln.ch().length;
            while (end > 0 && ln.ch()[end - 1] == ' ') {
                end--;
            }
            sb.append(ln.ch(), 0, end).append('\n');
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ layout / resize

    @Override
    protected void layoutChildren() {
        double w = getWidth(), h = getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        canvas.setWidth(Math.min(w, 8192));
        canvas.setHeight(Math.min(h, 8192));
        int nc = Math.max(2, (int) (w / cellW));
        int nr = Math.max(1, (int) (h / cellH));
        if (nc != cols || nr != rows) {
            resizeGrid(nc, nr);
            if (onGridResize != null) {
                onGridResize.accept(nc, nr);
            }
        }
        render();
    }

    private void resizeGrid(int nc, int nr) {
        int shift = altActive ? 0 : Math.max(0, row - nr + 1);
        Screen nm = new Screen(nc, nr);
        // lines that fall off the top of the main screen go to scrollback
        for (int r = 0; r < shift && r < main.rows; r++) {
            pushScrollback(main, r);
        }
        for (int r = shift; r < Math.min(main.rows, shift + nr); r++) {
            copyRow(main, r, nm, r - shift, Math.min(main.cols, nc));
        }
        Screen na = new Screen(nc, nr);
        for (int r = 0; r < Math.min(alt.rows, nr); r++) {
            copyRow(alt, r, na, r, Math.min(alt.cols, nc));
        }
        main = nm;
        alt = na;
        s = altActive ? alt : main;
        cols = nc;
        rows = nr;
        row = Math.max(0, Math.min(nr - 1, row - shift));
        col = Math.min(nc - 1, col);
        wrapNext = false;
        top = 0;
        bot = nr - 1;
        viewOffset = Math.min(viewOffset, scrollback.size());
        selAr = -1;
    }

    private static void copyRow(Screen from, int fr, Screen to, int tr, int n) {
        System.arraycopy(from.ch[fr], 0, to.ch[tr], 0, n);
        System.arraycopy(from.fg[fr], 0, to.fg[tr], 0, n);
        System.arraycopy(from.bg[fr], 0, to.bg[tr], 0, n);
        System.arraycopy(from.fl[fr], 0, to.fl[tr], 0, n);
    }

    // ------------------------------------------------------------------ parser

    private void feed(int c) {
        switch (state) {
            case GROUND -> ground(c);
            case ESC -> esc(c);
            case ESC_INT -> state = GROUND;
            case CSI -> csi(c);
            case OSC -> {
                if (c == 0x07) {
                    state = GROUND;
                } else if (c == 0x1b) {
                    state = OSC_ESC;
                }
            }
            case OSC_ESC -> state = GROUND;
            default -> state = GROUND;
        }
    }

    private void ground(int c) {
        switch (c) {
            case 0x1b -> state = ESC;
            case '\r' -> { col = 0; wrapNext = false; }
            case '\n', 0x0b, 0x0c -> index();
            case '\b' -> { col = Math.max(0, col - 1); wrapNext = false; }
            case '\t' -> { col = Math.min(cols - 1, ((col / 8) + 1) * 8); wrapNext = false; }
            default -> {
                if (c >= 0x20 && c != 0x7f) {
                    put(c);
                }
            }
        }
    }

    private void esc(int c) {
        state = GROUND;
        switch (c) {
            case '[' -> { state = CSI; csiBuf.setLength(0); }
            case ']' -> state = OSC;
            case '(', ')', '*', '+', '#', '%', ' ' -> state = ESC_INT;
            case '7' -> saveCursor();
            case '8' -> restoreCursor();
            case 'M' -> reverseIndex();
            case 'D' -> index();
            case 'E' -> { col = 0; index(); }
            case 'c' -> fullReset();
            default -> { /* ESC = / ESC > etc: ignored */ }
        }
    }

    private void csi(int c) {
        if (c >= 0x40 && c <= 0x7e) {
            state = GROUND;
            try {
                dispatch((char) c);
            } catch (RuntimeException e) {
                // malformed sequence: drop it, keep going
            }
        } else if (c == 0x1b) {
            state = ESC;
        } else if (csiBuf.length() < 64) {
            csiBuf.append((char) c);
        }
    }

    private static int[] parseParams(String str) {
        StringBuilder clean = new StringBuilder();
        for (char ch : str.toCharArray()) {
            if (ch >= '0' && ch <= '9' || ch == ';') {
                clean.append(ch);
            } else if (ch == ':') {
                clean.append(';');
            }
        }
        if (clean.length() == 0) {
            return new int[0];
        }
        String[] parts = clean.toString().split(";", -1);
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty()) {
                out[i] = 0;
            } else {
                out[i] = p.length() > 5 ? 65535 : Integer.parseInt(p);
            }
        }
        return out;
    }

    private static int arg(int[] p, int i, int def) {
        return i < p.length && p[i] > 0 ? p[i] : def;
    }

    private void dispatch(char f) {
        String raw = csiBuf.toString();
        char prefix = 0;
        if (!raw.isEmpty() && "<=>?".indexOf(raw.charAt(0)) >= 0) {
            prefix = raw.charAt(0);
            raw = raw.substring(1);
        }
        int[] p = parseParams(raw);
        if (prefix == '?') {
            if (f == 'h' || f == 'l') {
                for (int v : p) {
                    privateMode(v, f == 'h');
                }
            }
            return;
        }
        if (prefix != 0) {
            return; // secondary DA, kitty keyboard etc.
        }
        int n = arg(p, 0, 1);
        switch (f) {
            case 'A' -> { row = Math.max(row >= top ? top : 0, row - n); wrapNext = false; }
            case 'B' -> { row = Math.min(row <= bot ? bot : rows - 1, row + n); wrapNext = false; }
            case 'C', 'a' -> { col = Math.min(cols - 1, col + n); wrapNext = false; }
            case 'D' -> { col = Math.max(0, col - n); wrapNext = false; }
            case 'E' -> { col = 0; row = Math.min(rows - 1, row + n); wrapNext = false; }
            case 'F' -> { col = 0; row = Math.max(0, row - n); wrapNext = false; }
            case 'G', '`' -> { col = Math.max(0, Math.min(cols - 1, n - 1)); wrapNext = false; }
            case 'd', 'e' -> { row = Math.max(0, Math.min(rows - 1, n - 1)); wrapNext = false; }
            case 'H', 'f' -> {
                row = Math.max(0, Math.min(rows - 1, arg(p, 0, 1) - 1));
                col = Math.max(0, Math.min(cols - 1, arg(p, 1, 1) - 1));
                wrapNext = false;
            }
            case 'J' -> eraseDisplay(p.length > 0 ? p[0] : 0);
            case 'K' -> eraseLine(p.length > 0 ? p[0] : 0);
            case 'L' -> { if (row >= top && row <= bot) scrollDown(row, bot, n); }
            case 'M' -> { if (row >= top && row <= bot) scrollUp(row, bot, n, false); }
            case 'P' -> deleteChars(n);
            case '@' -> insertChars(n);
            case 'X' -> s.clear(row, col, Math.min(cols, col + n), curBg);
            case 'S' -> scrollUp(top, bot, n, true);
            case 'T' -> scrollDown(top, bot, n);
            case 'r' -> {
                int t = arg(p, 0, 1) - 1;
                int b = arg(p, 1, rows) - 1;
                if (t < b && b < rows) {
                    top = t;
                    bot = b;
                } else {
                    top = 0;
                    bot = rows - 1;
                }
                row = 0;
                col = 0;
                wrapNext = false;
            }
            case 'm' -> sgr(p);
            case 's' -> saveCursor();
            case 'u' -> restoreCursor();
            default -> { /* n, c, t, q, h, l (ANSI modes) ... ignored */ }
        }
    }

    private void privateMode(int mode, boolean set) {
        switch (mode) {
            case 1 -> appCursor = set;
            case 7 -> autowrap = set;
            case 25 -> cursorVisible = set;
            case 47, 1047 -> switchScreen(set);
            case 1048 -> { if (set) saveCursor(); else restoreCursor(); }
            case 1049 -> {
                if (set) {
                    aRow = row;
                    aCol = col;
                    switchScreen(true);
                    for (int r = 0; r < rows; r++) {
                        alt.clear(r, 0, cols, null);
                    }
                } else {
                    switchScreen(false);
                    row = Math.min(rows - 1, aRow);
                    col = Math.min(cols - 1, aCol);
                }
            }
            default -> { }
        }
    }

    private void switchScreen(boolean toAlt) {
        if (toAlt == altActive) {
            return;
        }
        altActive = toAlt;
        s = toAlt ? alt : main;
        top = 0;
        bot = rows - 1;
        wrapNext = false;
        viewOffset = 0;
        selAr = -1;
    }

    private void saveCursor() {
        sRow = row; sCol = col; sFg = curFg; sBg = curBg; sFl = curFl;
    }

    private void restoreCursor() {
        row = Math.max(0, Math.min(rows - 1, sRow));
        col = Math.max(0, Math.min(cols - 1, sCol));
        curFg = sFg; curBg = sBg; curFl = sFl;
        wrapNext = false;
    }

    private void fullReset() {
        for (int r = 0; r < rows; r++) {
            main.clear(r, 0, cols, null);
            alt.clear(r, 0, cols, null);
        }
        altActive = false;
        s = main;
        row = 0; col = 0; top = 0; bot = rows - 1;
        curFg = null; curBg = null; curFl = 0;
        autowrap = true; appCursor = false; cursorVisible = true; wrapNext = false;
    }

    private void sgr(int[] p) {
        if (p.length == 0) {
            curFg = null; curBg = null; curFl = 0;
            return;
        }
        for (int i = 0; i < p.length; i++) {
            int v = p[i];
            if (v == 0) { curFg = null; curBg = null; curFl = 0; }
            else if (v == 1) curFl |= BOLD;
            else if (v == 4) curFl |= UNDERLINE;
            else if (v == 7) curFl |= REVERSE;
            else if (v == 22) curFl &= ~BOLD;
            else if (v == 24) curFl &= ~UNDERLINE;
            else if (v == 27) curFl &= ~REVERSE;
            else if (v >= 30 && v <= 37) curFg = ANSI[v - 30];
            else if (v == 39) curFg = null;
            else if (v >= 40 && v <= 47) curBg = ANSI[v - 40];
            else if (v == 49) curBg = null;
            else if (v >= 90 && v <= 97) curFg = ANSI[v - 90 + 8];
            else if (v >= 100 && v <= 107) curBg = ANSI[v - 100 + 8];
            else if (v == 38 || v == 48) {
                Color c = null;
                if (i + 2 < p.length && p[i + 1] == 5) {
                    c = xterm256(p[i + 2]);
                    i += 2;
                } else if (i + 4 < p.length && p[i + 1] == 2) {
                    c = Color.rgb(clamp255(p[i + 2]), clamp255(p[i + 3]), clamp255(p[i + 4]));
                    i += 4;
                }
                if (c != null) {
                    if (v == 38) curFg = c; else curBg = c;
                }
            }
        }
    }

    private static int clamp255(int v) {
        return Math.max(0, Math.min(255, v));
    }

    private static Color xterm256(int n) {
        n = clamp255(n);
        if (n < 16) {
            return ANSI[n];
        }
        if (n < 232) {
            int i = n - 16;
            int r = i / 36, g = (i / 6) % 6, b = i % 6;
            return Color.rgb(r == 0 ? 0 : 55 + r * 40, g == 0 ? 0 : 55 + g * 40, b == 0 ? 0 : 55 + b * 40);
        }
        int v = 8 + (n - 232) * 10;
        return Color.rgb(v, v, v);
    }

    // ------------------------------------------------------------------ screen ops

    private void put(int cp) {
        char c = cp < 0x10000 ? (char) cp : '?';
        if (wrapNext && autowrap) {
            col = 0;
            index();
        }
        wrapNext = false;
        s.ch[row][col] = c;
        s.fg[row][col] = curFg;
        s.bg[row][col] = curBg;
        s.fl[row][col] = (byte) curFl;
        if (col == cols - 1) {
            if (autowrap) {
                wrapNext = true;
            }
        } else {
            col++;
        }
    }

    private void index() {
        wrapNext = false;
        if (row == bot) {
            scrollUp(top, bot, 1, true);
        } else if (row < rows - 1) {
            row++;
        }
    }

    private void reverseIndex() {
        wrapNext = false;
        if (row == top) {
            scrollDown(top, bot, 1);
        } else if (row > 0) {
            row--;
        }
    }

    private void pushScrollback(Screen scr, int r) {
        scrollback.add(new Line(scr.ch[r].clone(), scr.fg[r].clone(), scr.bg[r].clone(), scr.fl[r].clone()));
        if (scrollback.size() > MAX_SCROLLBACK + 200) {
            scrollback.subList(0, 200).clear();
            if (selAr >= 0) {
                selAr -= 200;
                selHr -= 200;
                if (selAr < 0 || selHr < 0) {
                    selAr = -1;
                }
            }
        }
    }

    private void scrollUp(int t, int b, int n, boolean record) {
        n = Math.min(n, b - t + 1);
        for (int k = 0; k < n; k++) {
            if (record && !altActive && t == 0) {
                pushScrollback(s, 0);
                if (viewOffset > 0) {
                    viewOffset = Math.min(scrollback.size(), viewOffset + 1);
                }
            }
            char[] c0 = s.ch[t];
            Color[] f0 = s.fg[t];
            Color[] b0 = s.bg[t];
            byte[] l0 = s.fl[t];
            for (int r = t; r < b; r++) {
                s.ch[r] = s.ch[r + 1];
                s.fg[r] = s.fg[r + 1];
                s.bg[r] = s.bg[r + 1];
                s.fl[r] = s.fl[r + 1];
            }
            s.ch[b] = c0; s.fg[b] = f0; s.bg[b] = b0; s.fl[b] = l0;
            s.clear(b, 0, cols, curBg);
        }
    }

    private void scrollDown(int t, int b, int n) {
        n = Math.min(n, b - t + 1);
        for (int k = 0; k < n; k++) {
            char[] c0 = s.ch[b];
            Color[] f0 = s.fg[b];
            Color[] b0 = s.bg[b];
            byte[] l0 = s.fl[b];
            for (int r = b; r > t; r--) {
                s.ch[r] = s.ch[r - 1];
                s.fg[r] = s.fg[r - 1];
                s.bg[r] = s.bg[r - 1];
                s.fl[r] = s.fl[r - 1];
            }
            s.ch[t] = c0; s.fg[t] = f0; s.bg[t] = b0; s.fl[t] = l0;
            s.clear(t, 0, cols, curBg);
        }
    }

    private void insertChars(int n) {
        n = Math.min(n, cols - col);
        int len = cols - col - n;
        if (len > 0) {
            System.arraycopy(s.ch[row], col, s.ch[row], col + n, len);
            System.arraycopy(s.fg[row], col, s.fg[row], col + n, len);
            System.arraycopy(s.bg[row], col, s.bg[row], col + n, len);
            System.arraycopy(s.fl[row], col, s.fl[row], col + n, len);
        }
        s.clear(row, col, col + n, curBg);
    }

    private void deleteChars(int n) {
        n = Math.min(n, cols - col);
        int len = cols - col - n;
        if (len > 0) {
            System.arraycopy(s.ch[row], col + n, s.ch[row], col, len);
            System.arraycopy(s.fg[row], col + n, s.fg[row], col, len);
            System.arraycopy(s.bg[row], col + n, s.bg[row], col, len);
            System.arraycopy(s.fl[row], col + n, s.fl[row], col, len);
        }
        s.clear(row, cols - n, cols, curBg);
    }

    private void eraseDisplay(int mode) {
        switch (mode) {
            case 0 -> {
                s.clear(row, col, cols, curBg);
                for (int r = row + 1; r < rows; r++) s.clear(r, 0, cols, curBg);
            }
            case 1 -> {
                for (int r = 0; r < row; r++) s.clear(r, 0, cols, curBg);
                s.clear(row, 0, col + 1, curBg);
            }
            case 3 -> { scrollback.clear(); viewOffset = 0; }
            default -> { for (int r = 0; r < rows; r++) s.clear(r, 0, cols, curBg); }
        }
    }

    private void eraseLine(int mode) {
        switch (mode) {
            case 0 -> s.clear(row, col, cols, curBg);
            case 1 -> s.clear(row, 0, col + 1, curBg);
            default -> s.clear(row, 0, cols, curBg);
        }
    }

    // ------------------------------------------------------------------ selection / mouse

    private int sbSize() {
        return altActive ? 0 : scrollback.size();
    }

    private Line lineAt(int abs) {
        int sb = sbSize();
        if (abs < 0) {
            return null;
        }
        if (abs < sb) {
            return scrollback.get(abs);
        }
        int r = abs - sb;
        if (r >= rows) {
            return null;
        }
        return new Line(s.ch[r], s.fg[r], s.bg[r], s.fl[r]);
    }

    private int absRowAt(MouseEvent e) {
        int r = Math.max(0, Math.min(rows - 1, (int) (e.getY() / cellH)));
        return sbSize() - viewOffset + r;
    }

    private int colAt(MouseEvent e) {
        return Math.max(0, Math.min(cols - 1, (int) (e.getX() / cellW)));
    }

    private void mousePressed(MouseEvent e) {
        requestFocus();
        if (e.getButton() == MouseButton.SECONDARY) {
            if (hasSelection()) {
                copySelection();
                clearSelection();
            } else {
                paste();
            }
            return;
        }
        if (e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragged = false;
        selAr = absRowAt(e);
        selAc = colAt(e);
        selHr = selAr;
        selHc = selAc;
        if (e.getClickCount() == 2) {
            selectWord();
        }
        requestRender();
    }

    private void mouseDragged(MouseEvent e) {
        if (selAr < 0 || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        dragged = true;
        selHr = absRowAt(e);
        selHc = colAt(e);
        requestRender();
    }

    private void mouseReleased(MouseEvent e) {
        if (e.getButton() == MouseButton.PRIMARY && !dragged && e.getClickCount() < 2) {
            selAr = -1;
            requestRender();
        }
    }

    private void selectWord() {
        Line ln = lineAt(selAr);
        if (ln == null) {
            return;
        }
        int a = selAc, b = selAc;
        while (a > 0 && ln.ch()[a - 1] != ' ') a--;
        while (b < ln.ch().length - 1 && ln.ch()[b + 1] != ' ') b++;
        selAc = a;
        selHr = selAr;
        selHc = b;
    }

    // ------------------------------------------------------------------ rendering

    private void requestRender() {
        if (!renderQueued) {
            renderQueued = true;
            Platform.runLater(this::render);
        }
    }

    private void render() {
        renderQueued = false;
        double w = canvas.getWidth(), h = canvas.getHeight();
        if (w < 1 || h < 1) {
            return;
        }
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(DEFAULT_BG);
        g.fillRect(0, 0, w, h);

        int baseAbs = sbSize() - viewOffset;
        int sr1 = -1, sc1 = 0, sr2 = -1, sc2 = 0;
        if (hasSelection()) {
            sr1 = selAr; sc1 = selAc; sr2 = selHr; sc2 = selHc;
            if (sr1 > sr2 || (sr1 == sr2 && sc1 > sc2)) {
                int tr = sr1, tc = sc1;
                sr1 = sr2; sc1 = sc2; sr2 = tr; sc2 = tc;
            }
        }

        for (int r = 0; r < rows; r++) {
            Line ln = lineAt(baseAbs + r);
            if (ln == null) {
                continue;
            }
            int n = Math.min(cols, ln.ch().length);
            double y = r * cellH;
            for (int c = 0; c < n; c++) {
                Color f = ln.fg()[c];
                Color b = ln.bg()[c];
                int fl = ln.fl()[c];
                if ((fl & REVERSE) != 0) {
                    Color nf = b != null ? b : DEFAULT_BG;
                    b = f != null ? f : DEFAULT_FG;
                    f = nf;
                }
                if (b != null) {
                    g.setFill(b);
                    g.fillRect(c * cellW, y, cellW + 0.5, cellH);
                }
                char ch = ln.ch()[c];
                if (ch != ' ') {
                    g.setFont((fl & BOLD) != 0 ? fontBold : font);
                    g.setFill(f != null ? f : DEFAULT_FG);
                    g.fillText(String.valueOf(ch), c * cellW, y + baseline);
                }
                if ((fl & UNDERLINE) != 0) {
                    g.setFill(f != null ? f : DEFAULT_FG);
                    g.fillRect(c * cellW, y + cellH - 2, cellW, 1);
                }
            }
            int abs = baseAbs + r;
            if (sr1 >= 0 && abs >= sr1 && abs <= sr2) {
                int from = (abs == sr1) ? sc1 : 0;
                int to = (abs == sr2) ? sc2 : cols - 1;
                g.setFill(SELECTION_COLOR);
                g.fillRect(from * cellW, y, (to - from + 1) * cellW, cellH);
            }
        }

        if (cursorVisible && viewOffset == 0) {
            double cx = col * cellW, cy = row * cellH;
            if (isFocused()) {
                g.setFill(CURSOR_COLOR);
                g.setGlobalAlpha(0.65);
                g.fillRect(cx, cy, cellW, cellH);
                g.setGlobalAlpha(1.0);
            } else {
                g.setStroke(CURSOR_COLOR);
                g.strokeRect(cx + 0.5, cy + 0.5, cellW - 1, cellH - 1);
            }
        }
    }
}
