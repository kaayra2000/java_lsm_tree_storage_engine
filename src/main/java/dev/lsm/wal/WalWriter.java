package dev.lsm.wal;

import dev.lsm.model.KeyValueRecord;
import dev.lsm.model.RecordType;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Write-Ahead Log (WAL) yazıcısı.
 * Her yazma (PUT/DELETE) işlemini diskte ardışık binary formatta kütüğe kaydeder.
 * CRC32 sağlama toplamı (checksum) ile disk hatalarını ve çökme anındaki yarım yazmaları denetler.
 */
public final class WalWriter implements AutoCloseable {

    private final Path filePath;
    private final FileChannel fileChannel;
    private final CRC32 crc32 = new CRC32();
    private final boolean syncOnWrite;

    public WalWriter(Path filePath, boolean syncOnWrite) throws IOException {
        this.filePath = filePath;
        this.syncOnWrite = syncOnWrite;
        this.fileChannel = FileChannel.open(
                filePath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    /**
     * Kaydı WAL dosyasına atomik çerçeve (frame) olarak yazar.
     * Çerçeve Formatı:
     * [CRC32: 4B] [SeqNum: 8B] [Timestamp: 8B] [RecordType: 1B] [KeyLen: 4B] [KeyBytes] [ValLen: 4B] [ValBytes]
     */
    public synchronized void append(KeyValueRecord record) throws IOException {
        byte[] key = record.key();
        byte[] value = record.value() != null ? record.value() : new byte[0];

        int payloadSize = 8 + 8 + 1 + 4 + key.length + 4 + value.length;
        ByteBuffer payloadBuffer = ByteBuffer.allocate(payloadSize);

        payloadBuffer.putLong(record.sequenceNumber());
        payloadBuffer.putLong(record.timestamp());
        payloadBuffer.put(record.type().getCode());
        payloadBuffer.putInt(key.length);
        payloadBuffer.put(key);
        payloadBuffer.putInt(value.length);
        payloadBuffer.put(value);

        payloadBuffer.flip();

        // CRC32 hesaplama
        crc32.reset();
        crc32.update(payloadBuffer.array(), 0, payloadSize);
        int checksum = (int) crc32.getValue();

        // 4B Checksum + Payload
        ByteBuffer frameBuffer = ByteBuffer.allocate(4 + payloadSize);
        frameBuffer.putInt(checksum);
        frameBuffer.put(payloadBuffer);
        frameBuffer.flip();

        while (frameBuffer.hasRemaining()) {
            fileChannel.write(frameBuffer);
        }

        if (syncOnWrite) {
            fileChannel.force(false);
        }
    }

    public synchronized void sync() throws IOException {
        if (fileChannel.isOpen()) {
            fileChannel.force(false);
        }
    }

    public Path getFilePath() {
        return filePath;
    }

    @Override
    public synchronized void close() throws IOException {
        if (fileChannel.isOpen()) {
            fileChannel.force(true);
            fileChannel.close();
        }
    }
}
