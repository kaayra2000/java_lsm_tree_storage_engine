package dev.lsm.filter;

import dev.lsm.model.ByteUtils;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Sıfır harici bağımlılıklı, yüksek başarımlı Bloom Filtresi gerçeklemesi.
 * Disk G/Ç (I/O) maliyetlerini düşürmek için SSTable seviyesinde $O(1)$
 * negatif arama garantisi sunar. Kirsch-Mitzenmacher çift hashleme tekniğini
 * (Double Hashing) kullanır.
 */
public final class BloomFilter {

    private static final double LN2_SQUARED = Math.log(2) * Math.log(2);
    private static final double LN2 = Math.log(2);

    private final int numHashFunctions;
    private final int bitSize;
    private final long[] words;

    /**
     * Beklenen eleman sayısı ve hedeflenen hata payına (false positive rate) göre Bloom filtresi oluşturur.
     *
     * @param expectedInsertions Filtreye eklenecek tahmini anahtar sayısı (n)
     * @param fpp                Hedeflenen yanlış pozitif oranı (p), örn: 0.01 (%1)
     */
    public BloomFilter(int expectedInsertions, double fpp) {
        if (expectedInsertions <= 0) {
            expectedInsertions = 1;
        }
        if (fpp <= 0.0 || fpp >= 1.0) {
            fpp = 0.01;
        }

        // m = - (n * ln(p)) / (ln(2)^2)
        long calculatedBits = (long) (-expectedInsertions * Math.log(fpp) / LN2_SQUARED);
        this.bitSize = (int) Math.max(64, calculatedBits);

        // k = (m / n) * ln(2)
        int calculatedK = (int) Math.round(((double) this.bitSize / expectedInsertions) * LN2);
        this.numHashFunctions = Math.max(1, Math.min(30, calculatedK));

        int wordCount = (this.bitSize + 63) >>> 6;
        this.words = new long[wordCount];
    }

    /**
     * Var olan bit dizisinden Bloom filtresi örneği oluşturur (deserialization için).
     */
    public BloomFilter(int numHashFunctions, int bitSize, long[] words) {
        this.numHashFunctions = numHashFunctions;
        this.bitSize = bitSize;
        this.words = words;
    }

    /**
     * Anahtarı Bloom filtresine ekler.
     */
    public void add(byte[] key) {
        if (key == null) return;

        long hash1 = ByteUtils.murmurHash64(key, 0x9747b28cL);
        long hash2 = ByteUtils.murmurHash64(key, hash1);

        for (int i = 0; i < numHashFunctions; i++) {
            long combinedHash = hash1 + ((long) i * hash2);
            // Pozitif bit indeksine mod al
            int bitIndex = (int) ((combinedHash & 0x7fffffffffffffffL) % bitSize);
            setBit(bitIndex);
        }
    }

    /**
     * Anahtarın filtrede olup olmadığını test eder.
     * @return false ise anahtar kesinlikle tabloda YOKTUR.
     *         true ise anahtar büyük olasılıkla vardır (küçük fpp ihtimaliyle yanlış pozitif).
     */
    public boolean mightContain(byte[] key) {
        if (key == null) return false;

        long hash1 = ByteUtils.murmurHash64(key, 0x9747b28cL);
        long hash2 = ByteUtils.murmurHash64(key, hash1);

        for (int i = 0; i < numHashFunctions; i++) {
            long combinedHash = hash1 + ((long) i * hash2);
            int bitIndex = (int) ((combinedHash & 0x7fffffffffffffffL) % bitSize);
            if (!getBit(bitIndex)) {
                return false;
            }
        }
        return true;
    }

    private void setBit(int bitIndex) {
        int wordIndex = bitIndex >>> 6;
        words[wordIndex] |= (1L << (bitIndex & 63));
    }

    private boolean getBit(int bitIndex) {
        int wordIndex = bitIndex >>> 6;
        return (words[wordIndex] & (1L << (bitIndex & 63))) != 0;
    }

    public int getNumHashFunctions() {
        return numHashFunctions;
    }

    public int getBitSize() {
        return bitSize;
    }

    public int getWordCount() {
        return words.length;
    }

    public long[] getWords() {
        return words;
    }

    public int byteSize() {
        // 4 bytes numHashFunctions + 4 bytes bitSize + 4 bytes wordCount + words.length * 8
        return 12 + (words.length * 8);
    }

    /**
     * Bloom filtresini ikili (binary) formata serileştirir.
     */
    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(numHashFunctions);
        buffer.putInt(bitSize);
        buffer.putInt(words.length);
        for (long word : words) {
            buffer.putLong(word);
        }
    }

    /**
     * İkili veriden Bloom filtresini yeniden yükler.
     */
    public static BloomFilter readFrom(ByteBuffer buffer) {
        int k = buffer.getInt();
        int m = buffer.getInt();
        int wordCount = buffer.getInt();
        long[] words = new long[wordCount];
        for (int i = 0; i < wordCount; i++) {
            words[i] = buffer.getLong();
        }
        return new BloomFilter(k, m, words);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BloomFilter that)) return false;
        return numHashFunctions == that.numHashFunctions &&
                bitSize == that.bitSize &&
                Arrays.equals(words, that.words);
    }

    @Override
    public int hashCode() {
        int result = numHashFunctions;
        result = 31 * result + bitSize;
        result = 31 * result + Arrays.hashCode(words);
        return result;
    }
}
