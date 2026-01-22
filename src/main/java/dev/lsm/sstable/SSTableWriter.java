package dev.lsm.sstable;

import dev.lsm.filter.BloomFilter;
import dev.lsm.model.KeyValueRecord;
import dev.lsm.model.RecordType;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * SSTable ikili (binary) dosya formatı oluşturucusu.
 * Veri bloklarını, seyrek indeks bloğunu, Bloom filtresini ve altbilgiyi (Footer)
 * diske ardışık ve optimize edilmiş olarak yazar.
 */
public final class SSTableWriter {

    private final Path filePath;
    private final long sstableId;
    private final int targetBlockSize;
    private final double fpp;

    public SSTableWriter(Path filePath, long sstableId, int targetBlockSize, double fpp) {
        this.filePath = filePath;
        this.sstableId = sstableId;
        this.targetBlockSize = Math.max(1024, targetBlockSize);
        this.fpp = fpp;
    }

    /**
     * Sıralı kayıt akışını SSTable dosyasına yazar.
     */
    public SSTableSummary write(Iterator<KeyValueRecord> records, int estimatedRecordCount) throws IOException {
        List<BlockMeta> blockMetaList = new ArrayList<>();
        BloomFilter bloomFilter = new BloomFilter(Math.max(16, estimatedRecordCount), fpp);

        long minSeqNum = Long.MAX_VALUE;
        long maxSeqNum = 0;
        int totalRecordCount = 0;
        byte[] tableFirstKey = null;
        byte[] tableLastKey = null;

        try (FileChannel channel = FileChannel.open(
                filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING
        )) {
            ByteArrayOutputStream blockBaos = new ByteArrayOutputStream(targetBlockSize * 2);
            DataOutputStream blockDos = new DataOutputStream(blockBaos);

            byte[] currentBlockFirstKey = null;
            byte[] currentBlockLastKey = null;
            int currentBlockRecordCount = 0;
            long currentFileOffset = 0;

            while (records.hasNext()) {
                KeyValueRecord record = records.next();

                if (tableFirstKey == null) {
                    tableFirstKey = record.key();
                }
                tableLastKey = record.key();

                if (currentBlockFirstKey == null) {
                    currentBlockFirstKey = record.key();
                }
                currentBlockLastKey = record.key();

                bloomFilter.add(record.key());

                minSeqNum = Math.min(minSeqNum, record.sequenceNumber());
                maxSeqNum = Math.max(maxSeqNum, record.sequenceNumber());
                totalRecordCount++;
                currentBlockRecordCount++;

                // Blok içi kayıt serileştirme
                writeRecordToStream(blockDos, record);

                // Blok hedef boyuta ulaştıysa diske boşalt
                if (blockBaos.size() >= targetBlockSize) {
                    blockDos.flush();
                    byte[] blockBytes = blockBaos.toByteArray();
                    ByteBuffer buf = ByteBuffer.wrap(blockBytes);
                    while (buf.hasRemaining()) {
                        channel.write(buf);
                    }

                    blockMetaList.add(new BlockMeta(
                            currentBlockFirstKey,
                            currentBlockLastKey,
                            currentFileOffset,
                            blockBytes.length,
                            currentBlockRecordCount
                    ));

                    currentFileOffset += blockBytes.length;
                    blockBaos.reset();
                    currentBlockFirstKey = null;
                    currentBlockLastKey = null;
                    currentBlockRecordCount = 0;
                }
            }

            // Son kalan blok varsa diske yaz
            if (currentBlockRecordCount > 0) {
                blockDos.flush();
                byte[] blockBytes = blockBaos.toByteArray();
                ByteBuffer buf = ByteBuffer.wrap(blockBytes);
                while (buf.hasRemaining()) {
                    channel.write(buf);
                }

                blockMetaList.add(new BlockMeta(
                        currentBlockFirstKey,
                        currentBlockLastKey,
                        currentFileOffset,
                        blockBytes.length,
                        currentBlockRecordCount
                ));

                currentFileOffset += blockBytes.length;
            }

            // 1. İndeks Bloğunu Hazırla ve Yaz
            long indexOffset = currentFileOffset;
            ByteBuffer indexBuffer = serializeIndexBlock(blockMetaList);
            int indexLength = indexBuffer.remaining();
            while (indexBuffer.hasRemaining()) {
                channel.write(indexBuffer);
            }
            currentFileOffset += indexLength;

            // 2. Bloom Filtresi Bloğunu Hazırla ve Yaz
            long filterOffset = currentFileOffset;
            ByteBuffer filterBuffer = ByteBuffer.allocate(bloomFilter.byteSize());
            bloomFilter.writeTo(filterBuffer);
            filterBuffer.flip();
            int filterLength = filterBuffer.remaining();
            while (filterBuffer.hasRemaining()) {
                channel.write(filterBuffer);
            }
            currentFileOffset += filterLength;

            // 3. Footer Yaz
            ByteBuffer footerBuffer = ByteBuffer.allocate(Footer.FOOTER_SIZE);
            Footer footer = new Footer(
                    indexOffset,
                    indexLength,
                    filterOffset,
                    filterLength,
                    totalRecordCount == 0 ? 0 : minSeqNum,
                    maxSeqNum,
                    totalRecordCount,
                    Footer.MAGIC_NUMBER
            );
            footer.writeTo(footerBuffer);
            footerBuffer.flip();
            while (footerBuffer.hasRemaining()) {
                channel.write(footerBuffer);
            }

            channel.force(true);
        }

        return new SSTableSummary(
                sstableId,
                filePath,
                totalRecordCount,
                tableFirstKey,
                tableLastKey,
                minSeqNum == Long.MAX_VALUE ? 0 : minSeqNum,
                maxSeqNum
        );
    }

    private void writeRecordToStream(DataOutputStream dos, KeyValueRecord record) throws IOException {
        byte[] key = record.key();
        byte[] value = record.value() != null ? record.value() : new byte[0];

        dos.writeByte(record.type().getCode());
        dos.writeInt(key.length);
        dos.write(key);
        dos.writeInt(value.length);
        dos.write(value);
        dos.writeLong(record.sequenceNumber());
        dos.writeLong(record.timestamp());
    }

    private ByteBuffer serializeIndexBlock(List<BlockMeta> blockMetaList) {
        int totalSize = 4;
        for (BlockMeta meta : blockMetaList) {
            totalSize += meta.serializedSize();
        }

        ByteBuffer buf = ByteBuffer.allocate(totalSize);
        buf.putInt(blockMetaList.size());
        for (BlockMeta meta : blockMetaList) {
            meta.writeTo(buf);
        }
        buf.flip();
        return buf;
    }

    public record SSTableSummary(
            long id,
            Path path,
            int recordCount,
            byte[] firstKey,
            byte[] lastKey,
            long minSeqNum,
            long maxSeqNum
    ) {}
}
