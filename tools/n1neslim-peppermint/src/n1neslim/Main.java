package n1neslim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Command-line + interactive entry point for the n1neslim "peppermint" builder.
 *
 * Usage:
 *   java -jar n1neslim.jar build  --config device_config.ini --system <img|dir> [--boot boot.img] --out out.zip
 *   java -jar n1neslim.jar skeleton --out out.zip
 *   java -jar n1neslim.jar unsparse in.img out.img
 *   java -jar n1neslim.jar sdat2img system.new.dat transfer.list out.img
 *   java -jar n1neslim.jar img2sdat in.img outdir/
 *   java -jar n1neslim.jar sign --in unsigned.zip --out signed.zip [--pk8 testkey.pk8 --pem testkey.x509.pem]
 *   java -nar n1neslim.jar verify --zip pkg.zip
 *   java -jar n1neslim.jar menu        (interactive beginner mode)
 */
public final class Main {

    public static void main(String[] args) throws Exception {
        PrintStream log = new PrintStream(new FileOutputStream(FileDescriptor.out), true, "UTF-8");
        try {
            if (args.length == 0) { menu(log); return; }
            String cmd = args[0];
            Map<String, String> opt = parseOpts(Arrays.copyOfRange(args, 1, args.length));
            switch (cmd) {
                case "build":     doBuild(opt, log); break;
                case "skeleton":  Builder.buildSkeleton(new File(req(opt, "out")), log); break;
                case "unsparse":  doUnsparse(opt, log); break;
                case "sdat2img":  doSdat2Img(opt, log); break;
                case "img2sdat":  doImg2Sdat(opt, log); break;
                case "sign":      doSign(opt, log); break;
                case "verify":    doVerify(opt, log); break;
                case "menu":      menu(log); break;
                case "help":
                default:          usage(log);
            }
        } catch (Exception e) {
            log.println("ERROR: " + e.getMessage());
            System.exit(1);
        }
    }

    // ------------------------------------------------------------------ build
    private static void doBuild(Map<String, String> o, PrintStream log) throws IOException {
        File cfg = new File(req(o, "config"));
        File sys = new File(req(o, "system"));
        File boot = o.containsKey("boot") ? new File(o.get("boot")) : null;
        File out = new File(req(o, "out"));
        Builder.build(cfg, sys, boot, out, log);
        if (Boolean.parseBoolean(o.getOrDefault("sign", "true"))) {
            File pk8 = o.containsKey("pk8") ? new File(o.get("pk8")) : ApkSigner.findKey("testkey.pk8");
            File pem = o.containsKey("pem") ? new File(o.get("pem")) : ApkSigner.findKey("testkey.x509.pem");
            File tmp = new File(out.getAbsolutePath() + ".unsigned");
            Util.copy(out, tmp);
            boolean real = ApkSigner.sign(tmp, out, pk8, pem, log);
            tmp.delete();
            log.println(real ? "signed with external signapk." : "signed with built-in fallback.");
        }
        log.println("\nDONE -> " + out.getAbsolutePath());
    }

    private static void doUnsparse(Map<String, String> o, PrintStream log) throws IOException {
        File in = new File(req(o, "in"));
        File out = new File(req(o, "out"));
        byte[] flat = SparseImg.unsparse(in);
        Util.write(out, flat);
        log.println("unsparse: " + in.getName() + " -> " + out.getName() + " (" + Util.human(flat.length) + ")");
    }

    private static void doSdat2Img(Map<String, String> o, PrintStream log) throws IOException {
        File dat = new File(req(o, "dat"));
        File tl = new File(req(o, "transfer-list"));
        File out = new File(req(o, "out"));
        byte[] img = BlockImage.reconstruct(dat, tl);
        Util.write(out, img);
        log.println("sdat2img: wrote " + out.getName() + " (" + Util.human(img.length) + ")");
    }

    private static void doImg2Sdat(Map<String, String> o, PrintStream log) throws IOException {
        File in = new File(req(o, "in"));
        File dir = new File(req(o, "outdir"));
        if (!dir.exists()) dir.mkdirs();
        File dat = new File(dir, "system.new.dat");
        File tl = new File(dir, "system.transfer.list");
        BlockImage.Result r = BlockImage.buildFromSource(in, dat, tl);
        log.printf("img2sdat: %d blocks, %s dat%n", r.blocks, Util.human(r.bytes));
    }

    private static void doSign(Map<String, String> o, PrintStream log) throws IOException {
        File in = new File(req(o, "in"));
        File out = new File(req(o, "out"));
        File pk8 = o.containsKey("pk8") ? new File(o.get("pk8")) : ApkSigner.findKey("testkey.pk8");
        File pem = o.containsKey("pem") ? new File(o.get("pem")) : ApkSigner.findKey("testkey.x509.pem");
        boolean real = ApkSigner.sign(in, out, pk8, pem, log);
        log.println(real ? "external signer used." : "built-in signer used.");
    }

    private static void doVerify(Map<String, String> o, PrintStream log) throws IOException {
        File zip = new File(req(o, "zip"));
        Builder.verify(zip, null, log);
    }

    // -------------------------------------------------------------- interactive
    private static void menu(PrintStream log) throws IOException {
        ConsoleIO io = new ConsoleIO(log);
        log.println("==================================================");
        log.println("  n1neslim \"peppermint\" builder — p281 / S905W");
        log.println("  Android TV 9 block-OTA packager (beginner mode)");
        log.println("==================================================");
        while (true) {
            log.println("\nChoose an action:");
            log.println("  1) Build a full OTA zip from a system image + boot.img");
            log.println("  2) Build a STRUCTURE-ONLY skeleton zip (no images yet)");
            log.println("  3) Unsparse a stock .img (sparse -> raw)");
            log.println("  4) Convert system.img -> system.new.dat (img2sdat)");
            log.println("  5) Sign an existing zip with test keys");
            log.println("  6) Verify a package");
            log.println("  7) Show current device_config.ini");
            log.println("  0) Exit");
            String c = io.ask("> ").trim();
            try {
                switch (c) {
                    case "1": {
                        File cfg = io.file("Path to device_config.ini", "device_config.ini");
                        File sys = io.file("Path to system.img (or unpacked system dir)", "system.img");
                        File boot = io.file("Path to boot.img (blank = none)", "");
                        File out = io.file("Output zip name", "n1neslim_v1_ota.zip");
                        Builder.build(cfg, sys, boot.isFile() ? boot : null, out, log);
                        File pk8 = ApkSigner.findKey("testkey.pk8");
                        File pem = ApkSigner.findKey("testkey.x509.pem");
                        File tmp = new File(out.getAbsolutePath() + ".unsigned");
                        Util.copy(out, tmp);
                        ApkSigner.sign(tmp, out, pk8, pem, log);
                        tmp.delete();
                        break;
                    }
                    case "2": {
                        File out = io.file("Output zip name", "n1neslim_v1_ota.zip");
                        Builder.buildSkeleton(out, log);
                        break;
                    }
                    case "3": {
                        File in = io.file("Input sparse img", "system.img");
                        File out = io.file("Output raw img", "system_raw.img");
                        byte[] flat = SparseImg.unsparse(in);
                        Util.write(out, flat);
                        log.println("wrote " + out + " (" + Util.human(flat.length) + ")");
                        break;
                    }
                    case "4": {
                        File in = io.file("Input system.img", "system.img");
                        File dir = io.file("Output directory", "sdat_out");
                        if (!dir.exists()) dir.mkdirs();
                        File dat = new File(dir, "system.new.dat");
                        File tl = new File(dir, "system.transfer.list");
                        BlockImage.Result r = BlockImage.buildFromSource(in, dat, tl);
                        log.printf("wrote system.new.dat (%d blocks) + system.transfer.list%n", r.blocks);
                        break;
                    }
                    case "5": {
                        File in = io.file("Unsigned zip", "n1neslim_v1_ota.zip");
                        File out = io.file("Signed zip output", "n1neslim_v1_ota_signed.zip");
                        File pk8 = io.file("testkey.pk8 path (blank = auto)", "");
                        File pem = io.file("testkey.x509.pem path (blank = auto)", "");
                        ApkSigner.sign(in, out,
                                pk8.isFile() ? pk8 : ApkSigner.findKey("testkey.pk8"),
                                pem.isFile() ? pem : ApkSigner.findKey("testkey.x509.pem"), log);
                        break;
                    }
                    case "6": {
                        File zip = io.file("Zip to verify", "n1neslim_v1_ota.zip");
                        Builder.verify(zip, null, log);
                        break;
                    }
                    case "7": {
                        File cfg = io.file("Config path", "device_config.ini");
                        if (cfg.isFile()) {
                            for (Map.Entry<String, String> e : Util.readIni(cfg).entrySet())
                                log.println("  " + e.getKey() + " = " + e.getValue());
                        } else log.println("  (not found: " + cfg + ")");
                        break;
                    }
                    case "0": log.println("bye."); return;
                    default: log.println("unknown option.");
                }
            } catch (Exception e) {
                log.println("ERROR: " + e.getMessage());
            }
        }
    }

    /** Simple stdin prompt helper so the menu works even without java.io.Console. */
    private static final class ConsoleIO {
        private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        private final PrintStream log;
        ConsoleIO(PrintStream log) { this.log = log; }
        String ask(String prompt) throws IOException {
            log.print(prompt);
            log.flush();
            String l = in.readLine();
            return l == null ? "" : l;
        }
        File file(String label, String def) throws IOException {
            String v = ask(label + " [" + def + "]: ").trim();
            if (v.isEmpty()) v = def;
            return new File(v);
        }
    }

    // ------------------------------------------------------------------- util
    private static Map<String, String> parseOpts(String[] a) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < a.length; i++) {
            String t = a[i];
            if (t.startsWith("--")) {
                String k = t.substring(2);
                if (i + 1 < a.length && !a[i + 1].startsWith("--")) m.put(k, a[++i]);
                else m.put(k, "true");
            }
        }
        return m;
    }

    private static String req(Map<String, String> o, String k) {
        String v = o.get(k);
        if (v == null) throw new IllegalArgumentException("missing required option --" + k);
        return v;
    }

    private static void usage(PrintStream log) {
        log.println("n1neslim peppermint builder v" + Builder.VERSION);
        log.println("commands: build | skeleton | unsparse | sdat2img | img2sdat | sign | verify | menu");
        log.println("run with no args for the interactive beginner menu.");
    }
}
