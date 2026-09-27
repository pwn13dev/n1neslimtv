package n1neslim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * AOSP test-key RSA signer (SHA256withRSA / PKCS#1 v1.5) implemented with pure
 * BigInteger math so the builder stays a single dependency-free jar.
 *
 * This mirrors what recovery's RSA_verify() does for signed OTA packages:
 *   DigestInfo(SHA-256) is EMSA-PKCS1-v1_5 encoded, then RSASP1(m)^e mod n.
 */
public final class RsaSigner {

    private static final byte[] SHA256_AID = {
        0x30, 0x31, 0x30, 0x0d, 0x06, 0x09, 0x60, (byte)0x86, 0x48, 0x01, 0x65, 0x03, 0x04, 0x02, 0x01, 0x05, 0x00
    };

    private RsaSigner() {}

    /** Sign data with a raw RSA key (PKCS#1 v1.5, SHA-256). Returns signature bytes. */
    public static byte[] sign(byte[] data, RsaKey k) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(data);
            // DigestInfo ::= SEQUENCE { AlgorithmIdentifier, OCTET STRING }
            byte[] diBody = concat(SHA256_AID, derOctetString(hash));
            byte[] digestInfo = derSequence(diBody);
            byte[] em = emsaPkcs1(digestInfo, k.modulusBytes());
            java.math.BigInteger m = new java.math.BigInteger(1, em);
            java.math.BigInteger s = m.modPow(k.d, k.n);
            return toFixed(s, k.modulusBytes());
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Verify a signature against a public key. */
    public static boolean verify(byte[] data, byte[] sig, RsaKey pub) throws IOException {
        if (sig.length != pub.modulusBytes()) return false;
        java.math.BigInteger s = new java.math.BigInteger(1, sig);
        java.math.BigInteger v = s.modPow(pub.e, pub.n);
        byte[] em = toFixed(v, pub.modulusBytes());
        // strip leading 0x00 0x01 ... 0x00 padding
        int idx = findSep(em);
        if (idx < 0) return false;
        byte[] tail = Arrays.copyOfRange(em, idx + 1, em.length);
        MessageDigest md;
        try { md = MessageDigest.getInstance("SHA-256"); } catch (Exception e) { throw new IOException(e); }
        byte[] expectedDi = derSequence(concat(SHA256_AID, derOctetString(md.digest(data))));
        return Arrays.equals(tail, expectedDi);
    }

    private static int findSep(byte[] em) {
        if (em.length < 2 || em[0] != 0 || em[1] != 1) return -1;
        int i = 2;
        while (i < em.length && em[i] == (byte)0xff) i++;
        if (i >= em.length || em[i] != 0) return -1;
        return i;
    }

    private static byte[] emsaPkcs1(byte[] digestInfo, int emLen) {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        bos.write(0);
        bos.write(1);
        for (int i = 0; i < emLen - digestInfo.length - 3; i++) bos.write((byte)0xff);
        bos.write(0);
        bos.write(digestInfo, 0, digestInfo.length);
        return bos.toByteArray();
    }

    // ---- tiny DER helpers -------------------------------------------------
    private static byte[] derLen(int len) {
        if (len < 0x80) return new byte[]{(byte) len};
        if (len <= 0xff) return new byte[]{(byte)0x81, (byte) len};
        return new byte[]{(byte)0x82, (byte)(len >> 8), (byte) len};
    }

    private static byte[] derSeqOrStr(int tag, byte[] body) {
        byte[] l = derLen(body.length);
        byte[] out = new byte[1 + l.length + body.length];
        out[0] = (byte) tag;
        System.arraycopy(l, 0, out, 1, l.length);
        System.arraycopy(body, 0, out, 1 + l.length, body.length);
        return out;
    }

    private static byte[] derSequence(byte[] body) { return derSeqOrStr(0x30, body); }
    private static byte[] derOctetString(byte[] body) { return derSeqOrStr(0x04, body); }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] o = new byte[a.length + b.length];
        System.arraycopy(a, 0, o, 0, a.length);
        System.arraycopy(b, 0, o, a.length, b.length);
        return o;
    }

    private static byte[] toFixed(java.math.BigInteger x, int len) {
        byte[] b = x.toByteArray();
        if (b.length == len) return b;
        if (b.length == len + 1 && b[0] == 0) return Arrays.copyOfRange(b, 1, b.length);
        if (b.length < len) {
            byte[] o = new byte[len];
            System.arraycopy(b, 0, o, len - b.length, b.length);
            return o;
        }
        throw new IllegalArgumentException("value too large for modulus");
    }

    /** An RSA key pair (or just the public half when d==null). */
    public static final class RsaKey {
        public final java.math.BigInteger n, e, d;
        public RsaKey(java.math.BigInteger n, java.math.BigInteger e, java.math.BigInteger d) {
            this.n = n; this.e = e; this.d = d;
        }
        public int modulusBytes() { return (n.bitLength() + 7) / 8; }
        public boolean hasPrivate() { return d != null; }
    }
}
