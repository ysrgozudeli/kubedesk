package com.kubedesk.ui;

import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.util.ArrayList;
import java.util.List;

public final class TerminalGrid extends ScrollPane {
    public static final int CELL_W = 9;
    public static final int CELL_H = 18;
    private static final int MAX_SCROLLBACK = 5000;

    private static final Color[] PALETTE = {
            Color.web("#000000"), Color.web("#c62828"), Color.web("#2e7d32"), Color.web("#f9a825"),
            Color.web("#1565c0"), Color.web("#6a1b9a"), Color.web("#00838f"), Color.web("#e0e0e0"),
            Color.web("#757575"), Color.web("#ef5350"), Color.web("#66bb6a"), Color.web("#ffee58"),
            Color.web("#42a5f5"), Color.web("#ab47bc"), Color.web("#26c6da"), Color.web("#ffffff"),
    };
    private static final Color DEFAULT_FG = Color.web("#e0e0e0");
    private static final Color DEFAULT_BG = Color.web("#1e1e1e");
    private static final Color CURSOR_COLOR = Color.web("#4fc3f7");
    private static final Font FONT = Font.font("Consolas", FontWeight.NORMAL, 14);
    private static final Font FONT_BOLD = Font.font("Consolas", FontWeight.BOLD, 14);

    // Main screen grid
    private char[][] grid;
    private Color[][] fg;
    private Color[][] bg;
    private boolean[][] bold;
    private boolean[][] reverse;
    private int cols, rows;
    private int cursorRow = 0, cursorCol = 0;
    private Color curFg = DEFAULT_FG;
    private Color curBg = DEFAULT_BG;
    private boolean curBold = false;
    private boolean curReverse = false;

    // Alternate screen buffer (for top/vim/less)
    private char[][] altGrid;
    private Color[][] altFg;
    private Color[][] altBg;
    private boolean[][] altBold;
    private boolean[][] altReverse;
    private int altCursorRow = 0, altCursorCol = 0;
    private boolean altScreen = false;

    // Saved cursor state (ESC 7 / ESC 8)
    private int savedRow = 0, savedCol = 0;
    private Color savedFg = DEFAULT_FG;
    private Color savedBg = DEFAULT_BG;
    private boolean savedBold = false;
    private boolean savedReverse = false;

    // Scrollback (main screen only)
    private final List<char[]> scrollChars = new ArrayList<>();
    private final List<Color[]> scrollFg = new ArrayList<>();
    private final List<Color[]> scrollBg = new ArrayList<>();

    // ANSI state machine
    private int state = 0;
    private final StringBuilder csi = new StringBuilder();
    private final StringBuilder oscText = new StringBuilder();
    private boolean csiPrivate = false; // '?' prefix before args

    private final Canvas canvas;

    public TerminalGrid(int initialCols, int initialRows) {
        this.cols = initialCols;
        this.rows = initialRows;
        grid = new char[rows][cols];
        fg = new Color[rows][cols];
        bg = new Color[rows][cols];
        bold = new boolean[rows][cols];
        reverse = new boolean[rows][cols];
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                grid[r][c] = ' '; fg[r][c] = DEFAULT_FG; bg[r][c] = DEFAULT_BG;
            }
        // Alternate screen buffer
        altGrid = new char[rows][cols];
        altFg = new Color[rows][cols];
        altBg = new Color[rows][cols];
        altBold = new boolean[rows][cols];
        altReverse = new boolean[rows][cols];
        canvas = new Canvas(cols * CELL_W, rows * CELL_H);
        Pane pane = new Pane(canvas);
        setContent(pane);
        setFitToWidth(false);
        setFitToHeight(false);
        setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        render();
    }

    public void write(byte[] data) {
        Platform.runLater(() -> {
            for (byte b : data) feed(b & 0xff);
            render();
        });
    }

    private void feed(int b) {
        if (state == 0) feedGround(b);
        else if (state == 1) feedEsc(b);
        else if (state == 2) feedCsi(b);
        else if (state == 3) feedOsc(b);
        else if (state == 4) feedOscSt(b);
    }

    private void feedGround(int b) {
        if (b == 0x1b) { state = 1; csi.setLength(0); return; }
        if (b == '\r') { cursorCol = 0; return; }
        if (b == '\n') { lineFeed(); return; }
        if (b == '\t') { cursorCol = Math.min(((cursorCol / 8) + 1) * 8, cols - 1); return; }
        if (b < 0x20) return;
        putChar((char) b);
    }

    private void feedEsc(int b) {
        if (b == '[') { state = 2; csi.setLength(0); csiPrivate = false; return; }
        if (b == ']') { state = 3; oscText.setLength(0); return; }
        if (b == 'c') { reset(); state = 0; return; }
        if (b == '7') { saveCursor(); state = 0; return; }
        if (b == '8') { restoreCursor(); state = 0; return; }
        state = 0;
    }

    private void feedCsi(int b) {
        if (b >= 0x40 && b <= 0x7e) { dispatchCsi((char) b); state = 0; return; }
        if (b == '?' && csi.length() == 0) { csiPrivate = true; return; }
        csi.append((char) b);
    }

    private void feedOsc(int b) {
        if (b == 0x07) { state = 0; return; } // BEL terminates OSC
        if (b == 0x1b) { state = 4; return; } // wait for ST
        oscText.append((char) b);
    }

    private void feedOscSt(int b) {
        if (b == 0x5c) state = 0; // ST = ESC \
        else state = 0; // ignore malformed
    }

    private void dispatchC0(char f) {
        int[] n = parseArgs(csi.toString());
        switch (f) {
            case 'A': cursorRow = Math.max(0, cursorRow - (n.length > 0 ? n[0] : 1)); break;
            case 'B': cursorRow = Math.min(rows - 1, cursorRow + (n.length > 0 ? n[0] : 1)); break;
            case 'C': cursorCol = Math.min(cols - 1, cursorCol + (n.length > 0 ? n[0] : 1)); break;
            case 'D': cursorCol = Math.max(0, cursorCol - (n.length > 0 ? n[0] : 1)); break;
            case 'G': cursorCol = Math.max(0, Math.min(cols - 1, n.length > 0 ? n[0] - 1 : 0)); break;
            case 'H': case 'f': {
                int r = n.length > 0 ? n[0] - 1 : 0;
                int c = n.length > 1 ? n[1] - 1 : 0;
                cursorRow = Math.max(0, Math.min(rows - 1, r));
                cursorCol = Math.max(0, Math.min(cols - 1, c));
                break;
            }
            case 'J': eraseDisplay(n.length > 0 ? n[0] : 2); break;
            case 'K': eraseLine(n.length > 0 ? n[0] : 0); break;
            case 'm': applySgr(n); break;
        }
    }

    /** Handle private-mode CSI sequences (ESC[?...h / ESC[?...l). */
    private void dispatchCsiPrivate(char f) {
        int[] n = parseArgs(csi.toString());
        int param = (n.length > 0) ? n[0] : 0;
        if (f == 'h' || f == 'l') {
            boolean set = (f == 'h');
            switch (param) {
                case 1049: // alternate screen buffer
                    if (set) enterAltScreen(); else leaveAltScreen();
                    break;
                case 1047: case 1048: // similar alternate screen variants
                    if (set) enterAltScreen(); else leaveAltScreen();
                    break;
                case 25: // cursor visibility (ignore for simplicity)
                    break;
                default: break;
            }
        }
    }

    private void dispatchCsi(char f) {
        if (csiPrivate) {
            dispatchCsiPrivate(f);
            return;
        }
        dispatchC0(f);
    }

    /** Save cursor position + attributes (ESC 7). */
    private void saveCursor() {
        savedRow = cursorRow;
        savedCol = cursorCol;
        savedFg = curFg;
        savedBg = curBg;
        savedBold = curBold;
        savedReverse = curReverse;
    }

    /** Restore cursor position + attributes (ESC 8). */
    private void restoreCursor() {
        cursorRow = Math.max(0, Math.min(rows - 1, savedRow));
        cursorCol = Math.max(0, Math.min(cols - 1, savedCol));
        curFg = savedFg;
        curBg = savedBg;
        curBold = savedBold;
        curReverse = savedReverse;
    }

    /** Switch to the alternate screen buffer (top, vim, less use this). */
    private void enterAltScreen() {
        if (altScreen) return;
        altScreen = true;
        // Clear the alt screen
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                altGrid[r][c] = ' '; altFg[r][c] = DEFAULT_FG; altBg[r][c] = DEFAULT_BG;
                altBold[r][c] = false; altReverse[r][c] = false;
            }
        altCursorRow = 0;
        altCursorCol = 0;
        // Save main cursor so we can restore it
        savedRow = cursorRow;
        savedCol = cursorCol;
    }

    /** Switch back to the main screen buffer. */
    private void leaveAltScreen() {
        if (!altScreen) return;
        altScreen = false;
        cursorRow = Math.max(0, Math.min(rows - 1, savedRow));
        cursorCol = Math.max(0, Math.min(cols - 1, savedCol));
    }

    private static int[] parseArgs(String s) {
        if (s.isEmpty()) return new int[0];
        String[] parts = s.split(";");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
        }
        return out;
    }

    private void putChar(char ch) {
        if (altScreen) {
            putCharAlt(ch);
            return;
        }
        if (cursorCol >= cols) { cursorCol = 0; lineFeed(); }
        grid[cursorRow][cursorCol] = ch;
        fg[cursorRow][cursorCol] = curFg;
        bg[cursorRow][cursorCol] = curBg;
        bold[cursorRow][cursorCol] = curBold;
        reverse[cursorRow][cursorCol] = curReverse;
        cursorCol++;
        if (cursorCol >= cols) { cursorCol = 0; lineFeed(); }
    }

    /** Write a char to the alternate screen (no scrollback). */
    private void putCharAlt(char ch) {
        if (cursorCol >= cols) { cursorCol = 0; return; } // alt screen doesn't scroll
        altGrid[cursorRow][cursorCol] = ch;
        altFg[cursorRow][cursorCol] = curFg;
        altBg[cursorRow][cursorCol] = curBg;
        altBold[cursorRow][cursorCol] = curBold;
        altReverse[cursorRow][cursorCol] = curReverse;
        cursorCol++;
    }

    private void lineFeed() {
        cursorRow++;
        if (cursorRow >= rows) scrollUp();
    }

    private void scrollUp() {
        if (scrollChars.size() >= MAX_SCROLLBACK) {
            scrollChars.remove(0); scrollFg.remove(0); scrollBg.remove(0);
        }
        scrollChars.add(grid[0].clone());
        scrollFg.add(fg[0].clone());
        scrollBg.add(bg[0].clone());
        for (int r = 0; r < rows - 1; r++) {
            System.arraycopy(grid[r+1], 0, grid[r], 0, cols);
            System.arraycopy(fg[r+1], 0, fg[r], 0, cols);
            System.arraycopy(bg[r+1], 0, bg[r], 0, cols);
            System.arraycopy(bold[r+1], 0, bold[r], 0, cols);
            System.arraycopy(reverse[r+1], 0, reverse[r], 0, cols);
        }
        for (int c = 0; c < cols; c++) {
            grid[rows-1][c] = ' '; fg[rows-1][c] = DEFAULT_FG;
            bg[rows-1][c] = DEFAULT_BG; bold[rows-1][c] = false; reverse[rows-1][c] = false;
        }
        cursorRow = rows - 1;
    }

    private void eraseDisplay(int mode) {
        if (altScreen) { eraseDisplayAlt(mode); return; }
        if (mode == 0) {
            for (int r = cursorRow; r < rows; r++) {
                int s = (r == cursorRow) ? cursorCol : 0;
                for (int c = s; c < cols; c++) clearCell(r, c);
            }
        } else if (mode == 1) {
            for (int r = 0; r <= cursorRow; r++) {
                int e = (r == cursorRow) ? cursorCol : cols;
                for (int c = 0; c < e; c++) clearCell(r, c);
            }
        } else {
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) clearCell(r, c);
        }
    }

    private void eraseDisplayAlt(int mode) {
        if (mode == 0) {
            for (int r = cursorRow; r < rows; r++) {
                int s = (r == cursorRow) ? cursorCol : 0;
                for (int c = s; c < cols; c++) clearCellAlt(r, c);
            }
        } else if (mode == 1) {
            for (int r = 0; r <= cursorRow; r++) {
                int e = (r == cursorRow) ? cursorCol : cols;
                for (int c = 0; c < e; c++) clearCellAlt(r, c);
            }
        } else {
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) clearCellAlt(r, c);
        }
    }

    private void eraseLine(int mode) {
        if (altScreen) { eraseLineAlt(mode); return; }
        if (mode == 0) { for (int c = cursorCol; c < cols; c++) clearCell(cursorRow, c); }
        else if (mode == 1) { for (int c = 0; c <= cursorCol; c++) clearCell(cursorRow, c); }
        else { for (int c = 0; c < cols; c++) clearCell(cursorRow, c); }
    }

    private void eraseLineAlt(int mode) {
        if (mode == 0) { for (int c = cursorCol; c < cols; c++) clearCellAlt(cursorRow, c); }
        else if (mode == 1) { for (int c = 0; c <= cursorCol; c++) clearCellAlt(cursorRow, c); }
        else { for (int c = 0; c < cols; c++) clearCellAlt(cursorRow, c); }
    }

    private void clearCell(int r, int c) {
        grid[r][c] = ' '; fg[r][c] = curFg; bg[r][c] = curBg;
        bold[r][c] = false; reverse[r][c] = false;
    }

    private void clearCellAlt(int r, int c) {
        altGrid[r][c] = ' '; altFg[r][c] = curFg; altBg[r][c] = curBg;
        altBold[r][c] = false; altReverse[r][c] = false;
    }

    private void applySgr(int[] n) {
        if (n.length == 0) { curFg = DEFAULT_FG; curBg = DEFAULT_BG; curBold = false; curReverse = false; return; }
        for (int i = 0; i < n.length; i++) {
            switch (n[i]) {
                case 0: curFg = DEFAULT_FG; curBg = DEFAULT_BG; curBold = false; curReverse = false; break;
                case 1: curBold = true; break;
                case 7: curReverse = true; break;
                case 22: curBold = false; break;
                case 27: curReverse = false; break;
                case 39: curFg = DEFAULT_FG; break;
                case 49: curBg = DEFAULT_BG; break;
                default:
                    if (n[i] >= 30 && n[i] <= 37) curFg = PALETTE[n[i] - 30];
                    else if (n[i] >= 40 && n[i] <= 47) curBg = PALETTE[n[i] - 40];
                    else if (n[i] >= 90 && n[i] <= 97) curFg = PALETTE[n[i] - 82];
                    else if (n[i] >= 100 && n[i] <= 107) curBg = PALETTE[n[i] - 92];
                    break;
            }
        }
    }

    private void reset() {
        for (int r = 0; r < rows; r++)
            for (int c = 0; c < cols; c++) {
                grid[r][c] = ' '; fg[r][c] = DEFAULT_FG; bg[r][c] = DEFAULT_BG;
                bold[r][c] = false; reverse[r][c] = false;
                altGrid[r][c] = ' '; altFg[r][c] = DEFAULT_FG; altBg[r][c] = DEFAULT_BG;
                altBold[r][c] = false; altReverse[r][c] = false;
            }
        cursorRow = 0; cursorCol = 0;
        altCursorRow = 0; altCursorCol = 0;
        curFg = DEFAULT_FG; curBg = DEFAULT_BG; curBold = false; curReverse = false;
        altScreen = false;
    }

    /** Clear the current screen (Ctrl+L equivalent). */
    public void clear() {
        if (altScreen) {
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) clearCellAlt(r, c);
            altCursorRow = 0; altCursorCol = 0;
        } else {
            for (int r = 0; r < rows; r++)
                for (int c = 0; c < cols; c++) clearCell(r, c);
            cursorRow = 0; cursorCol = 0;
        }
        render();
    }

    /** Return the terminal content as plain text (for copy-to-clipboard). */
    public String toText() {
        char[][] ch = altScreen ? altGrid : grid;
        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < rows; r++) {
            int end = cols;
            // Trim trailing spaces
            while (end > 0 && ch[r][end - 1] == ' ') end--;
            for (int c = 0; c < end; c++) sb.append(ch[r][c]);
            sb.append('\n');
        }
        return sb.toString();
    }

    private void render() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.setFill(DEFAULT_BG);
        g.fillRect(0, 0, canvas.getWidth(), canvas.getHeight());

        char[][] ch = altScreen ? altGrid : grid;
        Color[][] fgc = altScreen ? altFg : fg;
        Color[][ ] bgc = altScreen ? altBg : bg;
        boolean[][] boldc = altScreen ? altBold : bold;
        boolean[][] revc = altScreen ? altReverse : reverse;
        int cr = altScreen ? altCursorRow : cursorRow;
        int cc = altScreen ? altCursorCol : cursorCol;

        for (int r = 0; r < rows; r++) {
            for (int c = 0; c < cols; c++) {
                Color f = fgc[r][c];
                Color b = bgc[r][c];
                if (revc[r][c]) { Color t = f; f = b; b = t; }
                // Draw background only if different from default
                if (b.getRed() != DEFAULT_BG.getRed() || b.getGreen() != DEFAULT_BG.getGreen()
                        || b.getBlue() != DEFAULT_BG.getBlue()) {
                    g.setFill(b);
                    g.fillRect(c * CELL_W, r * CELL_H, CELL_W, CELL_H);
                }
                if (ch[r][c] != ' ') {
                    g.setFont(boldc[r][c] ? FONT_BOLD : FONT);
                    g.setFill(f);
                    g.fillText(String.valueOf(ch[r][c]), c * CELL_W + 1, r * CELL_H + CELL_H - 4);
                }
            }
        }
        // Cursor block
        g.setFill(CURSOR_COLOR);
        g.setGlobalAlpha(0.6);
        g.fillRect(cc * CELL_W, cr * CELL_H, CELL_W, CELL_H);
        g.setGlobalAlpha(1.0);
    }
}
