package dev.lsm.sstable;

import java.nio.ByteBuffer;

/**
 * SSTable dosyasının en sonundaki 48 baytlık sabit boyutlu altbilgi (Footer).
 * İndeks ve Bloom Filtresi bloklarının dosya içi konumlarını ve motor sihirli numarasını barındırır.
 */
public record Footer(
        long indexOffset,
        int indexLength,
        long filterOffset,
        int filterLength,
        long minSeqNum,
        long maxSeqNum,
        int recordCount,
        int magicNumber
) {

    public static final int FOOTER_SIZE = 48;
    public static final int MAGIC_NUMBER = 0x4C534D31; // "LSM1"

    public void writeTo(ByteBuffer buffer) {
        buffer.putLong(indexOffset);
        buffer.putInt(indexLength);
        buffer.putLong(filterOffset);
        buffer.putInt(filterLength);
        buffer.putLong(minSeqNum);
        buffer.putLong(maxSeqNum);
        buffer.putInt(recordCount);
        buffer.putInt(magicNumber);
    }

    public static Footer readFrom(ByteBuffer buffer) {
        long indexOffset = buffer.getLong();
        int indexLength = buffer.getInt();
        long filterOffset = buffer.getLong();
        int filterLength = buffer.getInt();
        long minSeqNum = buffer.getLong();
        long maxSeqNum = buffer.getLong();
        int recordCount = buffer.getInt();
        int magicNumber = buffer.getInt();

        if (magicNumber != MAGIC_NUMBER) {
            throw new IllegalStateException(String.format(
                    "Geçersiz SSTable dosyası! Beklenen sihirli numara: 0x%08X, okunan: 0x%08X",
                    MAGIC_NUMBER, magicNumber
            ));
        }

        return new Footer(
                indexOffset,
                indexLength,
                filterOffset,
                filterLength,
                minSeqNum,
                maxSeqNum,
                recordCount,
                magicNumber
        );
    }
}
