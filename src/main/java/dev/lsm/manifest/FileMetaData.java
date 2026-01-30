package dev.lsm.manifest;

import dev.lsm.model.ByteUtils;
import dev.lsm.sstable.SSTableReader;

import java.util.Arrays;

/**
 * Bir SSTable dosyasının seviye, boyut ve anahtar sınırlarını temsil eden meta verisi.
 */
public record FileMetaData(
        long sstableId,
        int level,
        long fileSize,
        byte[] firstKey,
        byte[] lastKey,
        long minSeqNum,
        long maxSeqNum
) {

    public boolean overlapsWith(byte[] targetFirst, byte[] targetLast) {
        if (targetFirst == null || targetLast == null) return true;
        return ByteUtils.compare(this.firstKey, targetLast) <= 0 &&
                ByteUtils.compare(this.lastKey, targetFirst) >= 0;
    }

    public boolean containsKey(byte[] key) {
        return ByteUtils.compare(key, firstKey) >= 0 && ByteUtils.compare(key, lastKey) <= 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof FileMetaData that)) return false;
        return sstableId == that.sstableId;
    }

    @Override
    public int hashCode() {
        return Long.hashCode(sstableId);
    }
}
