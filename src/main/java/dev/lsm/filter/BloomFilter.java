package dev.lsm.filter;

import dev.lsm.model.ByteUtils;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Sıfır harici bağımlılıklı Bloom Filtresi gerçeklemesi.
 */
public final class BloomFilter {

    private static final double LN2_SQUARED = Math.log(2) * Math.log(2);
    private static final double LN2 = Math.log(2);

    private final int numHashFunctions;
    private final int bitSize;
    private final long[] words;

    public BloomFilter(int expectedInsertions, double fpp) {
        if (expectedInsertions <= 0) expectedInsertions = 1;
        if (fpp <= 0.0 || fpp >= 1.0) fpp = 0.01;

        long calculatedBits = (long) (-expectedInsertions * Math.log(fpp) / LN2_SQUARED);
        this.bitSize = (int) Math.max(64, calculatedBits);

        int calculatedK = (int) Math.round(((double) this.bitSize / expectedInsertions) * LN2);
        this.numHashFunctions = Math.max(1, Math.min(30, calculatedK));

        int wordCount = (this.bitSize + 63) >>> 6;
        this.words = new long[wordCount];
    }

    public BloomFilter(int numHashFunctions, int bitSize, long[] words) {
        this.numHashFunctions = numHashFunctions;
        this.bitSize = bitSize;
        this.words = words;
    }

    public void add(byte[] key) {
        if (key == null) return;
        long hash1 = ByteUtils.murmurHash64(key, 0x9747b28cL);
        long hash2 = ByteUtils.murmurHash64(key, hash1);
        for (int i = 0; i < numHashFunctions; i++) {
            long combinedHash = hash1 + ((long) i * hash2);
            int bitIndex = (int) ((combinedHash & 0x7fffffffffffffffL) % bitSize);
            setBit(bitIndex);
        }
    }

    public boolean mightContain(byte[] key) {
        if (key == null) return false;
        long hash1 = ByteUtils.murmurHash64(key, 0x9747b28cL);
        long hash2 = ByteUtils.murmurHash64(key, hash1);
        for (int i = 0; i < numHashFunctions; i++) {
            long combinedHash = hash1 + ((long) i * hash2);
            int bitIndex = (int) ((combinedHash & 0x7fffffffffffffffL) % bitSize);
            if (!getBit(bitIndex)) return false;
        }
        return true;
    }

    private void setBit(int bitIndex) {
        words[bitIndex >>> 6] |= (1L << (bitIndex & 63));
    }

    private boolean getBit(int bitIndex) {
        return (words[bitIndex >>> 6] & (1L << (bitIndex & 63))) != 0;
    }

    public int getNumHashFunctions() { return numHashFunctions; }
    public int getBitSize() { return bitSize; }
    public int getWordCount() { return words.length; }
    public long[] getWords() { return words; }
    public int byteSize() { return 12 + (words.length * 8); }

    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(numHashFunctions);
        buffer.putInt(bitSize);
        buffer.putInt(words.length);
        for (long word : words) buffer.putLong(word);
    }

    public static BloomFilter readFrom(ByteBuffer buffer) {
        int k = buffer.getInt();
        int m = buffer.getInt();
        int wordCount = buffer.getInt();
        long[] words = new long[wordCount];
        for (int i = 0; i < wordCount; i++) words[i] = buffer.getLong();
        return new BloomFilter(k, m, words);
    }
}
