package dev.lsm.wal;

import dev.lsm.model.KeyValueRecord;
import dev.lsm.model.RecordType;

import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.zip.CRC32;

public final class WalReader {
    private static final Logger LOGGER = Logger.getLogger(WalReader.class.getName());
    private WalReader() {}

    public static List<KeyValueRecord> readAll(Path filePath) throws IOException {
        List<KeyValueRecord> records = new ArrayList<>();
        if (!Files.exists(filePath) || Files.size(filePath) == 0) return records;

        CRC32 crc32 = new CRC32();
        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            ByteBuffer headerBuf = ByteBuffer.allocate(4);
            while (channel.position() < channel.size()) {
                headerBuf.clear();
                readFully(channel, headerBuf);
                headerBuf.flip();
                int expectedChecksum = headerBuf.getInt();

                ByteBuffer metaBuf = ByteBuffer.allocate(21);
                readFully(channel, metaBuf);
                metaBuf.flip();

                long seqNum = metaBuf.getLong();
                long timestamp = metaBuf.getLong();
                byte typeCode = metaBuf.get();
                int keyLen = metaBuf.getInt();

                ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
                readFully(channel, keyBuf);
                byte[] key = keyBuf.array();

                ByteBuffer valLenBuf = ByteBuffer.allocate(4);
                readFully(channel, valLenBuf);
                valLenBuf.flip();
                int valLen = valLenBuf.getInt();

                ByteBuffer valBuf = ByteBuffer.allocate(valLen);
                readFully(channel, valBuf);
                byte[] value = valBuf.array();

                crc32.reset();
                metaBuf.flip();
                crc32.update(metaBuf.array(), 0, 21);
                crc32.update(key, 0, keyLen);
                valLenBuf.flip();
                crc32.update(valLenBuf.array(), 0, 4);
                crc32.update(value, 0, valLen);

                int actualChecksum = (int) crc32.getValue();
                if (actualChecksum != expectedChecksum) {
                    throw new IOException("WAL CRC32 doğrulama hatası!");
                }

                RecordType type = RecordType.fromCode(typeCode);
                records.add(new KeyValueRecord(key, value, type, seqNum, timestamp));
            }
        }
        return records;
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read == -1) {
                throw new EOFException("Beklenmedik dosya sonu");
            }
        }
    }
}
