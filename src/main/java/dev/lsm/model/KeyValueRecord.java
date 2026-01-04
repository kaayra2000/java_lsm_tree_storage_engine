package dev.lsm.model;

import java.util.Arrays;

/**
 * LSM-Tree dahili kayıt modeli.
 * Sıra numarası (MVCC / sequence number), işlem tipi ve zaman damgası içerir.
 */
public record KeyValueRecord(
        byte[] key,
        byte[] value,
        RecordType type,
        long sequenceNumber,
        long timestamp
) {

    public KeyValueRecord {
        if (key == null) {
            throw new IllegalArgumentException("Anahtar null olamaz");
        }
        if (type == null) {
            throw new IllegalArgumentException("Kayıt tipi null olamaz");
        }
        if (value == null && type == RecordType.PUT) {
            throw new IllegalArgumentException("PUT işlemi için değer null olamaz");
        }
    }

    public static KeyValueRecord put(byte[] key, byte[] value, long seqNum) {
        return new KeyValueRecord(key, value, RecordType.PUT, seqNum, System.currentTimeMillis());
    }

    public static KeyValueRecord delete(byte[] key, long seqNum) {
        return new KeyValueRecord(key, new byte[0], RecordType.DELETE, seqNum, System.currentTimeMillis());
    }

    public boolean isTombstone() {
        return type == RecordType.DELETE;
    }

    /**
     * Kaydın bellekte kapladığı yaklaşık bayt büyüklüğü.
     * MemTable boyut takibi ve flush eşiği hesaplamasında kullanılır.
     */
    public int estimatedSizeInBytes() {
        // Obje başlığı (~32 bayt) + referanslar (~32 bayt) + key + value + primitive alanlar
        int keyLen = key != null ? key.length : 0;
        int valLen = value != null ? value.length : 0;
        return 64 + keyLen + valLen;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof KeyValueRecord that)) return false;
        return sequenceNumber == that.sequenceNumber &&
                timestamp == that.timestamp &&
                type == that.type &&
                Arrays.equals(key, that.key) &&
                Arrays.equals(value, that.value);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(key);
        result = 31 * result + Arrays.hashCode(value);
        result = 31 * result + type.hashCode();
        result = 31 * result + Long.hashCode(sequenceNumber);
        return result;
    }

    @Override
    public String toString() {
        return "KeyValueRecord{" +
                "key=" + ByteUtils.toString(key) +
                ", type=" + type +
                ", seq=" + sequenceNumber +
                ", valLen=" + (value != null ? value.length : 0) +
                '}';
    }
}
