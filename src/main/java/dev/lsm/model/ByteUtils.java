package dev.lsm.model;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Saf Java bayt dizisi işlem, karşılaştırma ve hashleme yardımcı sınıfı.
 */
public final class ByteUtils {

    private ByteUtils() {}

    /**
     * Bayt dizileri için işaretsiz (unsigned lexicographical) karşılaştırıcı.
     * LSM-Tree SSTable bloklarında ve SkipList'te anahtarların sıralanmasını garanti eder.
     */
    public static final Comparator<byte[]> COMPARATOR = Arrays::compareUnsigned;

    /**
     * İki bayt dizisini sözlükbilimsel (lexicographical) olarak karşılaştırır.
     */
    public static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    /**
     * String anahtarı UTF-8 baytlarına dönüştürür.
     */
    public static byte[] toBytes(String str) {
        return str.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Bayt dizisini UTF-8 String'e dönüştürür.
     */
    public static String toString(byte[] bytes) {
        return bytes == null ? "null" : new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Bayt dizisini okunabilir Onaltılık (Hex) biçime çevirir.
     */
    public static String toHex(byte[] bytes) {
        if (bytes == null) return "null";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Bloom Filter ve blok dağıtımı için MurmurHash3 64-bit algoritması.
     * Yüksek homojenlik ve düşük çarpışma oranı sağlar.
     */
    public static long murmurHash64(byte[] data, int offset, int length, long seed) {
        long m = 0xc6a4a7935bd1e995L;
        int r = 47;

        long h = seed ^ (length * m);

        int end = offset + (length - (length & 7));
        for (int i = offset; i < end; i += 8) {
            long k = ((long) data[i] & 0xff) |
                    (((long) data[i + 1] & 0xff) << 8) |
                    (((long) data[i + 2] & 0xff) << 16) |
                    (((long) data[i + 3] & 0xff) << 24) |
                    (((long) data[i + 4] & 0xff) << 32) |
                    (((long) data[i + 5] & 0xff) << 40) |
                    (((long) data[i + 6] & 0xff) << 48) |
                    (((long) data[i + 7] & 0xff) << 56);

            k *= m;
            k ^= k >>> r;
            k *= m;

            h ^= k;
            h *= m;
        }

        int remainder = length & 7;
        if (remainder > 0) {
            int tailOffset = end;
            long k = 0;
            switch (remainder) {
                case 7: k |= ((long) data[tailOffset + 6] & 0xff) << 48;
                case 6: k |= ((long) data[tailOffset + 5] & 0xff) << 40;
                case 5: k |= ((long) data[tailOffset + 4] & 0xff) << 32;
                case 4: k |= ((long) data[tailOffset + 3] & 0xff) << 24;
                case 3: k |= ((long) data[tailOffset + 2] & 0xff) << 16;
                case 2: k |= ((long) data[tailOffset + 1] & 0xff) << 8;
                case 1: k |= ((long) data[tailOffset] & 0xff);
                    k *= m;
                    k ^= k >>> r;
                    k *= m;
                    h ^= k;
            }
        }

        h ^= h >>> r;
        h *= m;
        h ^= h >>> r;

        return h;
    }

    public static long murmurHash64(byte[] data, long seed) {
        return murmurHash64(data, 0, data.length, seed);
    }
}
