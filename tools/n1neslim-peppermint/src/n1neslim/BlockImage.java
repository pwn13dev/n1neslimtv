package n1neslim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Amlogic "block transfer" packager — a pure-Java re-implementation of the
 * sdat2img / img2sdat pipeline used by LineageOS-style block OTA packages.
 *
 * Formats produced/consumed here:
 *   system.new.dat      : flat stream of 4096-byte blocks (the "block image")
 *   system.transfer.list : text manifest consumed by the recovery's block_image_writer
 *   boot.img            : Android boot image header + payload (validated, not parsed)
 *
 * The transfer list format (version 3):
 *   3
 *   <total number of output blocks>
 *   <blank line>
 *   new <ranges> <sha1-of-block-data>
 *   erase <ranges>
 *   zero <ranges>
 *   ...
 * Ranges are "start:end" half-open block ranges, comma separated.
 */
public final class BlockImage {

    public static final int BLOCK = 4096;
    public static final int VERSION = 3;

    private BlockImage() {}

    // ------------------------------------------------------------------ build

    /**
     * Convert a raw/sparse .img or an unpacked directory tree into a
     * system.new.dat file plus its matching system.transfer.list.
     *
     * @param source  either a *.img file or a directory holding extracted files
     * @param datOut  destination for system.new.dat
     * @param tlOut   destination for system.transfer.list
     * @return stats for logging
     */
    public static Result buildFromSource(File source, File datOut, File tlOut) throws IOException {
        byte[] dat;
        if (source.isDirectory()) {
            // A real production build mounts an ext4 image. Here we treat the
            // directory contents as the literal filesystem payload so beginners
            // can still produce a structurally valid package without root.
            dat = TreeToDat.pack(source);
        } else if (SparseImg.isSparse(source)) {
            dat = SparseImg.unsparse(source);
        } else {
            dat = Util.readFully(source);
        }
        return writeTransferStructures(dat, datOut, tlOut);
    }

    /** Take an already-built system.new.dat and (re)generate the transfer list. */
    public static Result buildFromDat(File datIn, File tlOut) throws IOException {
        byte[] dat = Util.readFully(datIn);
        return writeTransferStructures(dat, datIn, tlOut);
    }

    private static Result writeTransferStructures(byte[] dat, File datOut, File tlOut) throws IOException {
        // Pad to whole blocks.
        int rem = dat.length % BLOCK;
        if (rem != 0) {
            byte[] padded = Arrays.copyOf(dat, dat.length + (BLOCK - rem));
            dat = padded;
        }
        int blocks = dat.length / BLOCK;

        StringBuilder tl = new StringBuilder();
        tl.append(VERSION).append('\n');
        tl.append(blocks).append('\n');
        tl.append('\n');

        long newBlocks = 0, zeroBlocks = 0;
        int i = 0;
        while (i < blocks) {
            // Group consecutive non-zero blocks into one "new" range, and
            // consecutive all-zero blocks into one "zero" range. This mirrors
            // what img2sdat emits and keeps the transfer list compact.
            if (isZeroBlock(dat, i)) {
                int j = i;
                while (j < blocks && isZeroBlock(dat, j)) j++;
                tl.append("zero ").append(range(i, j)).append('\n');
                zeroBlocks += (j - i);
                i = j;
            } else {
                int j = i;
                while (j < blocks && !isZeroBlock(dat, j)) j++;
                byte[] slice = Arrays.copyOfRange(dat, i * BLOCK, j * BLOCK);
                String sha = Util.sha1Hex(slice);
                tl.append("new ").append(range(i, j)).append(' ').append(sha).append('\n');
                newBlocks += (j - i);
                i = j;
            }
        }

        Util.write(datOut, dat);
        Util.write(tlOut, tl.toString().getBytes(StandardCharsets.UTF_8));
        return new Result(dat.length, blocks, newBlocks, zeroBlocks);
    }

    private static boolean isZeroBlock(byte[] d, int blockIdx) {
        int off = blockIdx * BLOCK;
        for (int k = off; k < off + BLOCK; k++) if (d[k] != 0) return false;
        return true;
    }

    private static String range(int start, int endExclusive) {
        return start + ":" + endExclusive;
    }

    // ------------------------------------------------------------------- read

    /** Reconstruct a flat .dat byte array from a dat + transfer.list pair. */
    public static byte[] reconstruct(File datFile, File transferListFile) throws IOException {
        byte[] dat = Util.readFully(datFile);
        List<String> lines = splitLines(Util.utf8(Util.readFully(transferListFile)));
        if (lines.size() < 2) throw new IOException("bad transfer list");
        int version = Integer.parseInt(lines.get(0).trim());
        if (version != VERSION) throw new IOException("unsupported transfer list version " + version);
        int totalBlocks = Integer.parseInt(lines.get(1).trim());
        byte[] out = new byte[totalBlocks * BLOCK];

        int srcCursor = 0; // byte cursor into dat for successive "new" ranges
        for (int li = 2; li < lines.size(); li++) {
            String line = lines.get(li).trim();
            if (line.isEmpty()) continue;
            String[] p = line.split("\\s+");
            String op = p[0];
            if (op.equals("new")) {
                String sha = p.length > 2 ? p[2] : null;
                for (long[] r : parseRanges(p[1])) {
                    int len = (int) (r[1] - r[0]);
                    System.arraycopy(dat, srcCursor, out, (int) (r[0] * BLOCK), len * BLOCK);
                    if (sha != null) {
                        byte[] slice = Arrays.copyOfRange(out, (int)(r[0]*BLOCK), (int)(r[1]*BLOCK));
                        if (!Util.sha1Hex(slice).equals(sha))
                            throw new IOException("SHA1 mismatch restoring blocks " + r[0] + "-" + r[1]);
                    }
                    srcCursor += len * BLOCK;
                }
            } else if (op.equals("zero") || op.equals("erase")) {
                for (long[] r : parseRanges(p[1])) {
                    Arrays.fill(out, (int)(r[0]*BLOCK), (int)(Math.min(r[1], totalBlocks)*BLOCK), (byte)0);
                }
            } else if (op.equals("move")) {
                // move <tgt> <src> <sha>
                long[] tgt = first(parseRanges(p[1]));
                long[] src = first(parseRanges(p[2]));
                System.arraycopy(out, (int)(src[0]*BLOCK), out, (int)(tgt[0]*BLOCK),
                        (int)((tgt[1]-tgt[0])*BLOCK));
            } else {
                throw new IOException("unknown transfer op: " + op);
            }
        }
        return out;
    }

    private static long[] first(List<long[]> l) { return l.get(0); }

    /** Parse "0:5,7:9" into [[0,5],[7,9]]. */
    public static List<long[]> parseRanges(String spec) {
        List<long[]> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            part = part.trim();
            if (part.isEmpty()) continue;
            int c = part.indexOf(':');
            long a = Long.parseLong(part.substring(0, c));
            long b = Long.parseLong(part.substring(c + 1));
            out.add(new long[]{a, b});
        }
        return out;
    }

    static List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new java.io.StringReader(s))) {
            String ln;
            while ((ln = br.readLine()) != null) out.add(ln);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return out;
    }

    public static final class Result {
        public final long bytes;
        public final int blocks;
        public final long newBlocks;
        public final long zeroBlocks;
        public Result(long bytes, int blocks, long newBlocks, long zeroBlocks) {
            this.bytes = bytes; this.blocks = blocks; this.newBlocks = newBlocks; this.zeroBlocks = zeroBlocks;
        }
    }
}
