package org.researchzosho.librarian;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * A QR code (ISO 18004) for a short text, drawn as SVG: byte mode, error correction M, versions 1 to 10 (up to 213 bytes), the mask
 * chosen by the standard's penalty score. Enough for the otpauth address an authenticator app scans, and for nothing larger.
 */
final class QrCode {
    private QrCode() { }

    /** Codewords in all, and error-correction codewords per block, for versions 1–10 at level M; the block count per version. */
    private static final int[] TOTAL = {0, 26, 44, 70, 100, 134, 172, 196, 242, 292, 346};
    private static final int[] EC_PER_BLOCK = {0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26};
    private static final int[] BLOCKS = {0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5};

    static final int MAX_VERSION = 10;

    /** The code as an SVG element, {@code scale} pixels a module, with the standard four-module quiet zone. */
    static String svg(String text, int scale) {
        boolean[][] m = modules(text, -1);
        int n = m.length, s = n + 8;
        StringBuilder d = new StringBuilder();
        for (int y = 0; y < n; y++) for (int x = 0; x < n; x++) if (m[y][x]) d.append('M').append(x + 4).append(' ').append(y + 4).append("h1v1h-1z");
        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + s + " " + s + "\" width=\"" + s * scale + "\" height=\"" + s * scale + "\" shape-rendering=\"crispEdges\" role=\"img\" aria-label=\"QR code\">"
                + "<rect width=\"" + s + "\" height=\"" + s + "\" fill=\"#fff\"/><path d=\"" + d + "\" fill=\"#000\"/></svg>";
    }

    /** The modules, dark = true; {@code mask} -1 picks the best, 0–7 forces one (tests compare against a reference encoder that way). */
    static boolean[][] modules(String text, int mask) {
        byte[] data = text.getBytes(StandardCharsets.UTF_8);
        int version = -1;
        for (int v = 1; v <= MAX_VERSION; v++) if (dataCapacity(v) * 8 >= 4 + countBits(v) + data.length * 8) { version = v; break; }
        if (version < 0) throw new IllegalArgumentException("The text is too long for a QR code of version " + MAX_VERSION + " (" + data.length + " bytes).");
        byte[] codewords = codewords(data, version);
        int size = version * 4 + 17;
        boolean[][] modules = new boolean[size][size], function = new boolean[size][size];
        drawFunctionPatterns(modules, function, version);
        drawCodewords(modules, function, codewords);
        int best = mask;
        if (mask < 0) {
            long low = Long.MAX_VALUE;
            for (int k = 0; k < 8; k++) {
                applyMask(modules, function, k); drawFormatBits(modules, function, k);
                long p = penalty(modules);
                if (p < low) { low = p; best = k; }
                applyMask(modules, function, k);   // undo: the mask is its own inverse
            }
        }
        applyMask(modules, function, best);
        drawFormatBits(modules, function, best);
        return modules;
    }

    private static int countBits(int version) { return version <= 9 ? 8 : 16; }
    private static int dataCapacity(int version) { return TOTAL[version] - EC_PER_BLOCK[version] * BLOCKS[version]; }

    // ---- the codewords ----

    private static byte[] codewords(byte[] data, int version) {
        int capacity = dataCapacity(version);
        // mode 0100, the count, the bytes, up to four zero bits, then the pad bytes
        BitBuffer bb = new BitBuffer();
        bb.append(4, 4);
        bb.append(data.length, countBits(version));
        for (byte b : data) bb.append(b & 0xff, 8);
        bb.append(0, Math.min(4, capacity * 8 - bb.length));
        bb.append(0, (8 - bb.length % 8) % 8);
        for (int pad = 0xEC; bb.length < capacity * 8; pad ^= 0xEC ^ 0x11) bb.append(pad, 8);
        byte[] dataWords = bb.bytes();
        // the blocks: the short ones first, then the long ones, each with its error-correction words
        int blocks = BLOCKS[version], ec = EC_PER_BLOCK[version], total = TOTAL[version];
        int shortBlocks = blocks - total % blocks, shortLen = total / blocks;
        byte[][] blockData = new byte[blocks][], blockEc = new byte[blocks][];
        byte[] gen = generator(ec);
        for (int i = 0, k = 0; i < blocks; i++) {
            int len = shortLen - ec + (i < shortBlocks ? 0 : 1);
            blockData[i] = Arrays.copyOfRange(dataWords, k, k + len);
            k += len;
            blockEc[i] = remainder(blockData[i], gen);
        }
        byte[] out = new byte[total];
        int o = 0;
        for (int i = 0; i < shortLen - ec + 1; i++) for (int b = 0; b < blocks; b++) if (i < blockData[b].length) out[o++] = blockData[b][i];
        for (int i = 0; i < ec; i++) for (int b = 0; b < blocks; b++) out[o++] = blockEc[b][i];
        return out;
    }

    private static final class BitBuffer {
        private final java.util.BitSet bits = new java.util.BitSet();
        int length;
        void append(int value, int n) { for (int i = n - 1; i >= 0; i--, length++) bits.set(length, ((value >>> i) & 1) != 0); }
        byte[] bytes() { byte[] b = new byte[length / 8]; for (int i = 0; i < length; i++) if (bits.get(i)) b[i >>> 3] |= (byte) (0x80 >>> (i & 7)); return b; }
    }

    /** The Reed–Solomon generator polynomial of this degree over GF(2^8) with 0x11D. */
    private static byte[] generator(int degree) {
        byte[] g = new byte[degree];
        g[degree - 1] = 1;
        int root = 1;
        for (int i = 0; i < degree; i++) {
            for (int j = 0; j < degree; j++) {
                g[j] = (byte) mul(g[j] & 0xff, root);
                if (j + 1 < degree) g[j] ^= g[j + 1];
            }
            root = mul(root, 2);
        }
        return g;
    }

    private static byte[] remainder(byte[] data, byte[] gen) {
        byte[] r = new byte[gen.length];
        for (byte b : data) {
            int factor = (b ^ r[0]) & 0xff;
            System.arraycopy(r, 1, r, 0, r.length - 1);
            r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= (byte) mul(gen[i] & 0xff, factor);
        }
        return r;
    }

    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) {
            z = (z << 1) ^ ((z >>> 7) * 0x11D);
            z ^= ((y >>> i) & 1) * x;
        }
        return z & 0xff;
    }

    // ---- the drawing ----

    private static void drawFunctionPatterns(boolean[][] m, boolean[][] f, int version) {
        int size = m.length;
        for (int i = 0; i < size; i++) { set(m, f, 6, i, i % 2 == 0); set(m, f, i, 6, i % 2 == 0); }
        finder(m, f, 3, 3); finder(m, f, size - 4, 3); finder(m, f, 3, size - 4);
        int[] align = alignment(version);
        for (int i = 0; i < align.length; i++) for (int j = 0; j < align.length; j++) {
            if ((i == 0 && j == 0) || (i == 0 && j == align.length - 1) || (i == align.length - 1 && j == 0)) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++) set(m, f, align[i] + dx, align[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormatBits(m, f, 0);
        if (version >= 7) {
            int rem = version;
            for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = version << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean bit = ((bits >>> i) & 1) != 0;
                int a = size - 11 + i % 3, b = i / 3;
                set(m, f, a, b, bit); set(m, f, b, a, bit);
            }
        }
    }

    private static void finder(boolean[][] m, boolean[][] f, int x, int y) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int xx = x + dx, yy = y + dy;
            if (xx < 0 || yy < 0 || xx >= m.length || yy >= m.length) continue;
            int dist = Math.max(Math.abs(dx), Math.abs(dy));
            set(m, f, xx, yy, dist != 2 && dist != 4);
        }
    }

    static int[] alignment(int version) {
        if (version == 1) return new int[0];
        int n = version / 7 + 2, size = version * 4 + 17;
        int step = (version == 32) ? 26 : (version * 4 + n * 2 + 1) / (n * 2 - 2) * 2;
        int[] out = new int[n];
        out[0] = 6;
        for (int i = n - 1, pos = size - 7; i >= 1; i--, pos -= step) out[i] = pos;
        return out;
    }

    private static void drawFormatBits(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        int data = mask;   // level M is 00, so the five bits are the mask alone
        int rem = data;
        for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(m, f, 8, i, bit(bits, i));
        set(m, f, 8, 7, bit(bits, 6)); set(m, f, 8, 8, bit(bits, 7)); set(m, f, 7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(m, f, 14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(m, f, size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(m, f, 8, size - 15 + i, bit(bits, i));
        set(m, f, 8, size - 8, true);
    }

    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private static void set(boolean[][] m, boolean[][] f, int x, int y, boolean dark) { m[y][x] = dark; f[y][x] = true; }

    private static void drawCodewords(boolean[][] m, boolean[][] f, byte[] data) {
        int size = m.length, i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) {
                for (int j = 0; j < 2; j++) {
                    int x = right - j;
                    boolean upward = ((right + 1) & 2) == 0;
                    int y = upward ? size - 1 - vert : vert;
                    if (!f[y][x] && i < data.length * 8) { m[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) != 0; i++; }
                }
            }
        }
    }

    private static void applyMask(boolean[][] m, boolean[][] f, int mask) {
        int size = m.length;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            boolean invert = switch (mask) {
                case 0 -> (x + y) % 2 == 0;
                case 1 -> y % 2 == 0;
                case 2 -> x % 3 == 0;
                case 3 -> (x + y) % 3 == 0;
                case 4 -> (x / 3 + y / 2) % 2 == 0;
                case 5 -> x * y % 2 + x * y % 3 == 0;
                case 6 -> (x * y % 2 + x * y % 3) % 2 == 0;
                default -> ((x + y) % 2 + x * y % 3) % 2 == 0;
            };
            if (invert && !f[y][x]) m[y][x] = !m[y][x];
        }
    }

    /** The standard's four penalties: runs of five or more, two-by-two blocks, finder-like runs, and the balance of dark and light. */
    static long penalty(boolean[][] m) {
        int size = m.length;
        long score = 0;
        for (int pass = 0; pass < 2; pass++) {
            for (int a = 0; a < size; a++) {
                boolean[] line = new boolean[size];
                for (int b = 0; b < size; b++) line[b] = pass == 0 ? m[a][b] : m[b][a];
                int run = 1;
                for (int b = 1; b <= size; b++) {
                    if (b < size && line[b] == line[b - 1]) run++;
                    else { if (run >= 5) score += 3 + (run - 5); run = 1; }
                }
                score += 40L * finderLike(line);
            }
        }
        for (int y = 0; y + 1 < size; y++) for (int x = 0; x + 1 < size; x++) {
            boolean c = m[y][x];
            if (c == m[y][x + 1] && c == m[y + 1][x] && c == m[y + 1][x + 1]) score += 3;
        }
        long dark = 0;
        for (boolean[] row : m) for (boolean b : row) if (b) dark++;
        long total = (long) size * size;
        long k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        score += 10 * k;
        return score;
    }

    /** How many times the line holds dark-light-dark-dark-dark-light-dark with four light modules before or after it. */
    private static int finderLike(boolean[] line) {
        boolean[] core = {true, false, true, true, true, false, true};
        int n = 0;
        for (int i = 0; i + 7 <= line.length; i++) {
            boolean match = true;
            for (int j = 0; j < 7; j++) if (line[i + j] != core[j]) { match = false; break; }
            if (!match) continue;
            if (light(line, i - 4, i)) n++;
            if (light(line, i + 7, i + 11)) n++;
        }
        return n;
    }

    private static boolean light(boolean[] line, int from, int to) {
        if (from < 0 || to > line.length) return false;
        for (int i = from; i < to; i++) if (line[i]) return false;
        return true;
    }
}
