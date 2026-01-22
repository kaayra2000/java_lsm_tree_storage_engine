package dev.lsm.sstable;

import dev.lsm.model.ByteUtils;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * SSTable içerisindeki tek bir veri bloğunun (Data Block) meta verisi.
 * Seyrek indeks (Sparse Index) oluşturmak için kullanılır.
 */
public record BlockMeta(
        byte[] firstKey,
        byte[] lastKey,
        long offset,
        int length,
        int recordCount
) {

    public BlockMeta {
        if (firstKey == null) {
            throw new IllegalArgumentException("firstKey null olamaz");
        }
    }

    public void writeTo(ByteBuffer buffer) {
        buffer.putInt(firstKey.length);
        buffer.put(firstKey);
        byte[] lk = lastKey != null ? lastKey : new byte[0];
        buffer.putInt(lk.length);
        buffer.put(lk);
        buffer.putLong(offset);
        buffer.putInt(length);
        buffer.putInt(recordCount);
    }

    public static BlockMeta readFrom(ByteBuffer buffer) {
        int firstKeyLen = buffer.getInt();
        byte[] firstKey = new byte[firstKeyLen];
        buffer.get(firstKey);

        int lastKeyLen = buffer.getInt();
        byte[] lastKey = new byte[lastKeyLen];
        buffer.get(lastKey);

        long offset = buffer.getLong();
        int length = buffer.getInt();
        int recordCount = buffer.getInt();

        return new BlockMeta(firstKey, lastKey, offset, length, recordCount);
    }

    public int serializedSize() {
        return 4 + firstKey.length + 4 + (lastKey != null ? lastKey.length : 0) + 8 + 4 + 4;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockMeta that)) return false;
        return offset == that.offset &&
                length == that.length &&
                recordCount == that.recordCount &&
                Arrays.equals(firstKey, that.firstKey) &&
                Arrays.equals(lastKey, that.lastKey);
    }

    @Override
    public int hashCode() {
        int result = Arrays.hashCode(firstKey);
        result = 31 * result + Arrays.hashCode(lastKey);
        result = 31 * result + Long.hashCode(offset);
        result = 31 * result + length;
        result = 31 * result + recordCount;
        return result;
    }
}
