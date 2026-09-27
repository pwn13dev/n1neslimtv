package n1neslim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Packs an unpacked filesystem directory into a flat block stream so the
 * builder can produce system.new.dat without needing root or an ext4 toolchain.
 *
 * Layout produced (a tiny, self-describing archive — NOT ext4):
 *   header: "N1NESLIM-TREE" + count
 *   per file: path length, path, mode(3 octets), data length, data
 * The recovery updater-script in this project mounts /system from the restored
 * image via loopback when a real ext4 is provided; otherwise this tree stream is
 * what gets written to the partition as opaque payload. Documented clearly for
 * beginners in README.
 */
public final class TreeToDat {

    private static final String MAGIC = "N1NESLIM-TREE\n";

    private TreeToDat() {}

    public static byte[] pack(File root) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(MAGIC.getBytes(StandardCharsets.UTF_8));
        List<File> files = new ArrayList<>();
        collect(root, files);
        files.sort(Comparator.comparing(f -> rel(root, f)));
        bos.write(Integer.toString(files.size()).getBytes(StandardCharsets.US_ASCII));
        bos.write('\n');
        for (File f : files) {
            String path = rel(root, f);
            byte[] p = path.getBytes(StandardCharsets.UTF_8);
            bos.write(Integer.toString(p.length).getBytes(StandardCharsets.US_ASCII));
            bos.write(' ');
            bos.write(p);
            bos.write('\n');
            bos.write(modeString(f).getBytes(StandardCharsets.US_ASCII));
            bos.write(' ');
            byte[] data = Util.readFully(f);
            bos.write(Integer.toString(data.length).getBytes(StandardCharsets.US_ASCII));
            bos.write('\n');
            bos.write(data);
            bos.write('\n');
        }
        return bos.toByteArray();
    }

    private static void collect(File dir, List<File> out) {
        File[] kids = dir.listFiles();
        if (kids == null) return;
        for (File k : kids) {
            if (k.isDirectory()) collect(k, out);
            else if (k.isFile()) out.add(k);
        }
    }

    private static String rel(File root, File f) {
        String r = root.getAbsolutePath();
        String a = f.getAbsolutePath();
        if (a.startsWith(r)) a = a.substring(r.length());
        while (a.startsWith("/")) a = a.substring(1);
        return a.replace(File.separatorChar, '/');
    }

    private static String modeString(File f) {
        // Best-effort POSIX-ish mode using Java's boolean view.
        int m = 0644;
        if (f.canExecute()) m = 0755;
        return String.format("%03o", m);
    }
}
