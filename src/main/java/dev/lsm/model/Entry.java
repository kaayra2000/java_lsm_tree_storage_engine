package dev.lsm.model;

import java.util.Arrays;

/**
 * Kullanıcı seviyesinde sunulan anahtar-değer çifti kaydı.
 */
public record Entry(byte[] key, byte[] value) {

    public Entry {
        if (key == null) {
            throw new IllegalArgumentException("Key null olamaz");
        }
    }

    public static Entry of(String key, String value) {
        return new Entry(ByteUtils.toBytes(key), ByteUtils.toBytes(value));
    }

    public static Entry of(byte[] key, byte[] value) {
        return new Entry(key, value);
    }

    public String keyAsString() {
        return ByteUtils.toString(key);
    }

    public String valueAsString() {
        return ByteUtils.toString(value);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Entry entry)) return false;
        return Arrays.equals(key, entry.key) && Arrays.equals(value, entry.value);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        return result;
    }

    @Override
    public String toString() {
        return keyAsString() + " => " + valueAsString();
    }
}
