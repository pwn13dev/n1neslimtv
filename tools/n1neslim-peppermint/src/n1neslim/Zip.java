package n1neslim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * Minimal, dependency-free ZIP writer (store + deflate) with a deterministic
 * ordering and an "uncompressable" mode used by Android's recovery / OTA
 * signer so that META-INF/com/google/android/* entries are stored raw.
 *
 * This is intentionally small and readable: it exists so the whole builder
 * can run as a single .jar with no external libraries.
 */
public final class Zip implements Closeable {

    public static final int METHOD_STORE = 0;
    public static final int METHOD_DEFLATE = 8;

    private static final byte[] EMPTY = new byte[0];

    /** Extra bytes added to a stored entry name to mark it "uncompressable". */
    public static String align767(String name) {
        // 30 bytes local header + name + 4 extra => 34 + len == 0 mod 4
        int pad = (4 - ((name.length() + 34) % 4)) % 4;
        StringBuilder sb = new StringBuilder(name);
        for (int i = 0; i < pad; i++) sb.append('\u2020'); // dagger filler
        return sb.toString();
    }

    private static final class Entry {
        String displayName;   // name written into the archive
        String matchName;     // logical name (no padding) for lookups
        byte[] data;
        int method;
        boolean uncompressable;
        long crc;
    }

    private final OutputStream out;
    private final List<Entry> entries = new ArrayList<>();
    private final Set<String> seen = new HashSet<>();
    private long offset = 0;
    private boolean finished = false;

    public Zip(OutputStream out) {
        this.out = out;
    }

    public static Zip create(File f) throws IOException {
        File parent = f.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        return new Zip(new BufferedOutputStream(new FileOutputStream(f)));
    }

    public boolean contains(String logicalName) {
        return seen.contains(logicalName);
    }

    /** Add an entry, writing the logical (unpadded) name into the archive. */
    public void addBytes(String logicalName, byte[] data, int method, boolean uncompressable) throws IOException {
        if (finished) throw new IOException("zip already finalized");
        if (seen.contains(logicalName)) throw new IOException("duplicate zip entry: " + logicalName);
        Entry e = new Entry();
        e.matchName = logicalName;
        // We always write the clean logical name so standard unzip tools and
        // recovery's minzip can open the package. The 'uncompressable' flag is
        // recorded here for ordering/verification only.
        e.displayName = logicalName;
        e.data = data == null ? EMPTY : data;
        e.method = method;
        e.uncompressable = uncompressable;
        CRC32 c = new CRC32();
        c.update(e.data);
        e.crc = c.getValue();
        entries.add(e);
        seen.add(logicalName);
    }

    public void addBytes(String name, byte[] data, int method) throws IOException {
        addBytes(name, data, method, false);
    }

    public void addText(String name, String text, int method, boolean uncompressable) throws IOException {
        addBytes(name, text.getBytes(StandardCharsets.UTF_8), method, uncompressable);
    }

    public void addText(String name, String text) throws IOException {
        addText(name, text, METHOD_DEFLATE, false);
    }

    public void addFile(String name, File f, int method, boolean uncompressable) throws IOException {
        addBytes(name, Util.readFully(f), method, uncompressable);
    }

    public void addFile(String name, File f) throws IOException {
        addFile(name, f, METHOD_DEFLATE, false);
    }

    /** Write everything. After finish() the stream is flushed but not closed (close() does that). */
    public void finish() throws IOException {
        if (finished) return;
        // Deterministic order: directories first, then alphabetical files.
        entries.sort((a, b) -> {
            boolean da = a.matchName.endsWith("/"), db = b.matchName.endsWith("/");
            if (da != db) return da ? -1 : 1;
            return a.matchName.compareTo(b.matchName);
        });

        List<byte[]> central = new ArrayList<>();
        for (Entry e : entries) {
            byte[] nameBytes = e.displayName.getBytes(StandardCharsets.UTF_8);
            byte[] extra = e.uncompressable ? new byte[]{0x77, 0x00} : EMPTY; // tag 0x0077, len 0
            byte[] comp = e.data;
            if (e.method == METHOD_DEFLATE && e.data.length > 0) {
                comp = deflate(e.data);
                if (comp.length >= e.data.length) { // don't inflate by deflating
                    comp = e.data;
                    e.method = METHOD_STORE;
                }
            } else if (e.method == METHOD_DEFLATE) {
                e.method = METHOD_STORE;
            }

            ByteArrayOutputStream lh = new ByteArrayOutputStream();
            le(lh, 0x04034b50);              // local header magic
            leShort(lh, 20);                 // version needed
            leShort(lh, 0x0800);             // flags: UTF-8 names
            leShort(lh, e.method);
            leShort(lh, 0);                  // dos time
            leShort(lh, 0x21);               // dos date (1980-01-01) -> stable output
            le(lh, (int) e.crc);
            le(lh, comp.length);             // compressed size
            le(lh, e.data.length);           // uncompressed size
            leShort(lh, nameBytes.length);   // file name length
            leShort(lh, extra.length);       // extra field length
            lh.write(nameBytes);
            lh.write(extra);
            lh.write(comp);
            byte[] local = lh.toByteArray();
            out.write(local);
            long localOffset = offset;
            offset += local.length;

            ByteArrayOutputStream ch = new ByteArrayOutputStream();
            le(ch, 0x02014b50);              // central directory header
            leShort(ch, 20);                 // version made by
            leShort(ch, 20);                 // version needed
            leShort(ch, 0x0800);             // flags
            leShort(ch, e.method);
            leShort(ch, 0);                  // time
            leShort(ch, 0x21);               // date
            le(ch, (int) e.crc);
            le(ch, comp.length);
            le(ch, e.data.length);
            leShort(ch, nameBytes.length);
            leShort(ch, extra.length);       // extra field present in central too
            leShort(ch, 0);                  // comment
            leShort(ch, 0);                  // disk number
            leShort(ch, 0);                  // internal attrs
            le(ch, 0);                       // external attrs
            le(ch, (int) localOffset);
            ch.write(nameBytes);
            ch.write(extra);
            central.add(ch.toByteArray());
        }

        long cdStart = offset;
        int cdSize = 0;
        for (byte[] c : central) {
            out.write(c);
            cdSize += c.length;
            offset += c.length;
        }

        ByteArrayOutputStream eo = new ByteArrayOutputStream();
        le(eo, 0x06054b50);
        leShort(eo, 0);
        leShort(eo, 0);
        leShort(eo, entries.size());
        leShort(eo, entries.size());
        le(eo, cdSize);
        le(eo, (int) cdStart);
        leShort(eo, 0);
        byte[] end = eo.toByteArray();
        out.write(end);
        offset += end.length;
        out.flush();
        finished = true;
    }

    private static byte[] deflate(byte[] in) {
        Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true); // raw (no zlib wrapper)
        d.setInput(in);
        d.finish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(64, in.length / 2));
        byte[] buf = new byte[8192];
        while (!d.finished()) {
            int n = d.deflate(buf);
            bos.write(buf, 0, n);
        }
        d.end();
        return bos.toByteArray();
    }

    private static void le(ByteArrayOutputStream s, int v) {
        s.write(v & 0xff);
        s.write((v >> 8) & 0xff);
        s.write((v >> 16) & 0xff);
        s.write((v >> 24) & 0xff);
    }

    private static void leShort(ByteArrayOutputStream s, int v) {
        s.write(v & 0xff);
        s.write((v >> 8) & 0xff);
    }

    @Override
    public void close() throws IOException {
        finish();
        out.close();
    }
}
