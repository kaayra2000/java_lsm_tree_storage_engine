package dev.lsm.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Saf Java bayt dizisi işlem, karşılaştırma ve hashleme yardımcı sınıfı.
 */
public final class ByteUtils {

    private ByteUtils() {}

    public static final Comparator<byte[]> COMPARATOR = Arrays::compareUnsigned;

    public static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    public static byte[] toBytes(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    public static String toString(byte[] bytes) {
        return bytes == null ? "null" : new String(bytes, StandardCharsets.UTF_8);
    }

    public static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    // Basit hash döngüsü (daha sonra unrolled 64-bit ile optimize edilecek)
    public static long murmurHash64(byte[] data, int offset, int length, long seed) {
        long m = 0xc6a4a7935bd1e995L;
        long h = seed ^ (length * m);
        for (int i = offset; i < offset + length; i++) {
            h ^= (data[i] & 0xff);
            h *= m;
            h ^= h >>> 47;
        }
        return h;
    }

    public static long murmurHash64(byte[] data, long seed) {
        return murmurHash64(data, 0, data.length, seed);
    }
}
