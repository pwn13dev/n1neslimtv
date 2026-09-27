package n1neslim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Small shared helpers: file IO, hex/digests, zip reading, tiny INI parser. */
public final class Util {

    private Util() {}

    public static byte[] readFully(File f) throws IOException {
        try (InputStream in = new BufferedInputStream(new FileInputStream(f))) {
            return readAll(in);
        }
    }

    /** Read exactly n bytes from a stream. Throws EOFException if fewer are available. */
    public static byte[] readN(InputStream in, int n) throws IOException {
        byte[] b = new byte[n];
        int got = 0;
        while (got < n) {
            int r = in.read(b, got, n - got);
            if (r < 0) throw new java.io.EOFException("expected " + n + " bytes, got " + got);
            got += r;
        }
        return b;
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream(Math.max(1024, in.available()));
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    public static void write(File f, byte[] data) throws IOException {
        File p = f.getAbsoluteFile().getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        try (OutputStream o = new BufferedOutputStream(new FileOutputStream(f))) {
            o.write(data);
        }
    }

    /** Stream from in to out until EOF. Closes neither (caller owns them). */
    public static void pipe(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    /** Copy a whole file to another path, creating parent dirs. */
    public static void copy(File src, File dst) throws IOException {
        File p = dst.getAbsoluteFile().getParentFile();
        if (p != null && !p.isDirectory()) p.mkdirs();
        try (InputStream in = new BufferedInputStream(new FileInputStream(src));
             OutputStream o = new BufferedOutputStream(new FileOutputStream(dst))) {
            pipe(in, o);
        }
    }

    public static String sha1Hex(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            return toHex(md.digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static String toHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    public static boolean isZip(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < 4) return false;
            r.seek(0);
            int a = r.read(), b = r.read(), c = r.read(), d = r.read();
            return a == 'P' && b == 'K' && (c == 3 || c == 5) && (d == 4 || d == 6);
        } catch (IOException e) {
            return false;
        }
    }

    /** Read every entry of a zip into memory (paths are normalized, no '..'). */
    public static Map<String, byte[]> readZip(File f) throws IOException {
        Map<String, byte[]> map = new LinkedHashMap<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(f)))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                if (name.contains("../")) continue; // path traversal guard
                ByteArrayOutputStream bos = new ByteArrayOutputStream((int) Math.min(e.getSize() <= 0 ? 1024 : e.getSize(), 1 << 26));
                int n;
                while ((n = zin.read(buf)) > 0) bos.write(buf, 0, n);
                map.put(name, bos.toByteArray());
                zin.closeEntry();
            }
        }
        return map;
    }

    /** Unzip a whole archive to a directory. Returns the list of extracted files. */
    public static List<File> unzip(File zip, File destDir) throws IOException {
        List<File> out = new ArrayList<>();
        try (ZipInputStream zin = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)))) {
            ZipEntry e;
            byte[] buf = new byte[16384];
            while ((e = zin.getNextEntry()) != null) {
                String name = e.getName().replace('\\', '/');
                if (name.contains("../") || name.startsWith("/")) continue;
                File target = new File(destDir, name);
                if (e.isDirectory()) {
                    target.mkdirs();
                } else {
                    target.getParentFile().mkdirs();
                    try (OutputStream o = new BufferedOutputStream(new FileOutputStream(target))) {
                        int n;
                        while ((n = zin.read(buf)) > 0) o.write(buf, 0, n);
                    }
                    out.add(target);
                }
                zin.closeEntry();
            }
        }
        return out;
    }

    /** Recursively delete a directory tree (never follows symlinks). */
    public static void deleteTree(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    public static String utf8(byte[] b) {
        return new String(b, StandardCharsets.UTF_8);
    }

    /** Human readable size. */
    public static String human(long bytes) {
        if (bytes < 1024) return bytes + " B";
        String[] u = {"KB", "MB", "GB", "TB"};
        double v = bytes / 1024.0;
        int i = 0;
        while (v >= 1024 && i < u.length - 1) { v /= 1024; i++; }
        return String.format(Locale.US, "%.2f %s", v, u[i]);
    }

    /**
     * Very small "key=value" reader used for device_config.ini.
     * Lines starting with # or ; are comments. Blank lines ignored.
     */
    public static Map<String, String> readIni(File f) throws IOException {
        Map<String, String> m = new LinkedHashMap<>();
        for (String raw : new String(readFully(f), StandardCharsets.UTF_8).split("\\R")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) continue;
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq).trim();
            String v = line.substring(eq + 1).trim();
            if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\""))) v = v.substring(1, v.length() - 1);
            m.put(k, v);
        }
        return m;
    }

    public static String get(Map<String, String> m, String key, String def) {
        String v = m.get(key);
        return (v == null || v.isEmpty()) ? def : v;
    }

    /** True when the named executable can be found on PATH. */
    public static boolean haveCmd(String cmd) {
        String path = System.getenv("PATH");
        if (path == null) return false;
        for (String dir : path.split(File.pathSeparator)) {
            File exe = new File(dir, cmd);
            if (exe.isFile() && exe.canExecute()) return true;
            File exeExe = new File(dir, cmd + ".exe"); // WSL/Windows interop
            if (exeExe.isFile()) return true;
        }
        return false;
    }
}
