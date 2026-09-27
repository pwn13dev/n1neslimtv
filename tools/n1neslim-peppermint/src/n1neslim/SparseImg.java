package n1neslim;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Android "sparse image" (ext4 sparse format) reader/writer.
 * Magic: 0x3AFF26ED little-endian at offset 0.
 * Used to unsparse the stock p281 system.img before converting to .dat.
 */
public final class SparseImg {

    private static final int MAGIC = 0x3AFF26ED;
    private static final int FILE_HEADER_SIZE = 28;
    private static final int CHUNK_HEADER_SIZE = 12;

    private static final int CHUNK_TYPE_RAW = 0xCAC1;
    private static final int CHUNK_TYPE_FILL = 0xCAC2;
    private static final int CHUNK_TYPE_DONT_CARE = 0xCAC3;
    private static final int CHUNK_TYPE_CRC32 = 0xCAC4;

    private SparseImg() {}

    public static boolean isSparse(File f) {
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            if (r.length() < FILE_HEADER_SIZE) return false;
            byte[] h = new byte[4];
            r.seek(0);
            r.readFully(h);
            int m = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN).getInt();
            return m == MAGIC;
        } catch (IOException e) {
            return false;
        }
    }

    /** Expand a sparse image into a flat byte array (the raw block device content). */
    public static byte[] unsparse(File f) throws IOException {
        byte[] all = Util.readFully(f);
        ByteBuffer bb = ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN);
        int magic = bb.getInt(0);
        if (magic != MAGIC) throw new IOException("not a sparse image");
        int blockSize = bb.getInt(8);
        int totalBlocks = bb.getInt(12);
        long outLen = (long) totalBlocks * blockSize;
        // Guard against absurd declared sizes.
        if (outLen > 4L * 1024 * 1024 * 1024) throw new IOException("sparse image too large: " + outLen);
        byte[] out = new byte[(int) outLen];

        int pos = FILE_HEADER_SIZE;
        int written = 0; // blocks written so far
        while (pos + CHUNK_HEADER_SIZE <= all.length && written < totalBlocks) {
            int chunkType = bb.getShort(pos + 0) & 0xffff;
            int chunkBlocks = bb.getInt(pos + 4);
            int chunkTotal = bb.getInt(pos + 8); // includes header
            int dataLen = chunkTotal - CHUNK_HEADER_SIZE;
            int dataOff = pos + CHUNK_HEADER_SIZE;
            int blkBytes = chunkBlocks * blockSize;

            switch (chunkType) {
                case CHUNK_TYPE_RAW:
                    System.arraycopy(all, dataOff, out, written * blockSize, Math.min(dataLen, out.length - written * blockSize));
                    break;
                case CHUNK_TYPE_FILL: {
                    int fillStart = written * blockSize;
                    for (int i = 0; i < blkBytes && fillStart + i < out.length; i++) {
                        out[fillStart + i] = all[dataOff + (i % 4)];
                    }
                    break;
                }
                case CHUNK_TYPE_DONT_CARE:
                    // leave zeros (already zero-initialized)
                    break;
                case CHUNK_TYPE_CRC32:
                    // skip checksum chunk, contributes 0 blocks
                    pos += chunkTotal;
                    continue;
                default:
                    throw new IOException("unknown sparse chunk type " + chunkType);
            }
            written += chunkBlocks;
            pos += chunkTotal;
        }
        return out;
    }

    /** Wrap a flat byte array into a sparse image (RAW + trailing DONT_CARE for holes). */
    public static byte[] toSparse(byte[] flat, int blockSize) {
        int totalBlocks = (flat.length + blockSize - 1) / blockSize;
        ByteArrayOutputStream bos = new ByteArrayOutputStream(flat.length + 256);
        ByteBuffer hdr = ByteBuffer.allocate(FILE_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
        hdr.putInt(MAGIC);
        hdr.putShort((short) 3); hdr.putShort((short) 1);          // major/minor
        hdr.putShort((short) FILE_HEADER_SIZE);
        hdr.putShort((short) CHUNK_HEADER_SIZE);
        hdr.putShort((short) 0);                                    // file_header ext
        hdr.putInt(blockSize);
        hdr.putInt(totalBlocks);
        hdr.putInt(0);                                              // magic2
        try { bos.write(hdr.array()); } catch (IOException e) { throw new RuntimeException(e); }

        try {
            // One RAW chunk holding the whole payload.
            int rawBlocks = flat.length / blockSize;
            ByteBuffer ch = ByteBuffer.allocate(CHUNK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
            ch.putShort((short) CHUNK_TYPE_RAW);
            ch.putShort((short) 0);
            ch.putInt(rawBlocks);
            ch.putInt(CHUNK_HEADER_SIZE + flat.length);
            bos.write(ch.array());
            bos.write(flat, 0, flat.length);
            // Pad the raw chunk to block multiple if needed via a FILL of the remainder.
            int tail = flat.length % blockSize;
            if (tail != 0) {
                // handled by padding in BlockImage; nothing extra here.
            }
            // Trailing DONT_CARE to reach totalBlocks.
            int dontCareBlocks = totalBlocks - rawBlocks - (tail == 0 ? 0 : 1);
            if (dontCareBlocks > 0) {
                ByteBuffer dc = ByteBuffer.allocate(CHUNK_HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN);
                dc.putShort((short) CHUNK_TYPE_DONT_CARE);
                dc.putShort((short) 0);
                dc.putInt(dontCareBlocks);
                dc.putInt(CHUNK_HEADER_SIZE);
                bos.write(dc.array());
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return bos.toByteArray();
    }
}
