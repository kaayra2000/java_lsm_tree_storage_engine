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

/**
 * WAL dosyasından kayıtları sırayla okuyan ve CRC32 doğrulamasını yapan kurtarma sınıfı.
 */
public final class WalReader {

    private static final Logger LOGGER = Logger.getLogger(WalReader.class.getName());

    private WalReader() {}

    /**
     * Belirtilen WAL dosyasındaki tüm geçerli kayıtları kurtarır.
     * Dosya sonundaki bozuk veya tamamlanmamış çerçevelerde okumayı güvenle sonlandırır.
     */
    public static List<KeyValueRecord> readAll(Path filePath) throws IOException {
        List<KeyValueRecord> records = new ArrayList<>();
        if (!Files.exists(filePath) || Files.size(filePath) == 0) {
            return records;
        }

        CRC32 crc32 = new CRC32();

        try (FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ)) {
            ByteBuffer headerBuf = ByteBuffer.allocate(4); // CRC32 için

            while (channel.position() < channel.size()) {
                long frameStartPos = channel.position();

                headerBuf.clear();
                int bytesRead = readFully(channel, headerBuf);
                if (bytesRead < 4) {
                    // Dosya sonu eksik bayt
                    break;
                }
                headerBuf.flip();
                int expectedChecksum = headerBuf.getInt();

                // Sabit boyutlu meta alanları: SeqNum(8) + Timestamp(8) + Type(1) + KeyLen(4) = 21 bayt
                ByteBuffer metaBuf = ByteBuffer.allocate(21);
                bytesRead = readFully(channel, metaBuf);
                if (bytesRead < 21) {
                    LOGGER.warning("Eksik WAL meta çerçevesi bulundu, kurtarma " + frameStartPos + " konumunda durduruldu.");
                    break;
                }
                metaBuf.flip();

                long seqNum = metaBuf.getLong();
                long timestamp = metaBuf.getLong();
                byte typeCode = metaBuf.get();
                int keyLen = metaBuf.getInt();

                if (keyLen < 0 || keyLen > 64 * 1024 * 1024) { // 64 MB güvenlik sınırı
                    LOGGER.warning("Geçersiz anahtar uzunluğu: " + keyLen + ". Kurtarma sonlandırıldı.");
                    break;
                }

                ByteBuffer keyBuf = ByteBuffer.allocate(keyLen);
                bytesRead = readFully(channel, keyBuf);
                if (bytesRead < keyLen) {
                    LOGGER.warning("Eksik anahtar verisi. Kurtarma sonlandırıldı.");
                    break;
                }
                byte[] key = keyBuf.array();

                ByteBuffer valLenBuf = ByteBuffer.allocate(4);
                bytesRead = readFully(channel, valLenBuf);
                if (bytesRead < 4) {
                    LOGGER.warning("Eksik değer uzunluğu verisi. Kurtarma sonlandırıldı.");
                    break;
                }
                valLenBuf.flip();
                int valLen = valLenBuf.getInt();

                if (valLen < 0 || valLen > 256 * 1024 * 1024) { // 256 MB güvenlik sınırı
                    LOGGER.warning("Geçersiz değer uzunluğu: " + valLen + ". Kurtarma sonlandırıldı.");
                    break;
                }

                ByteBuffer valBuf = ByteBuffer.allocate(valLen);
                bytesRead = readFully(channel, valBuf);
                if (bytesRead < valLen) {
                    LOGGER.warning("Eksik değer verisi. Kurtarma sonlandırıldı.");
                    break;
                }
                byte[] value = valBuf.array();

                // Sağlama toplamı (CRC32) denetimi
                crc32.reset();
                metaBuf.flip();
                crc32.update(metaBuf.array(), 0, 21);
                crc32.update(key, 0, keyLen);
                valLenBuf.flip();
                crc32.update(valLenBuf.array(), 0, 4);
                crc32.update(value, 0, valLen);

                int actualChecksum = (int) crc32.getValue();
                if (actualChecksum != expectedChecksum) {
                    LOGGER.severe("WAL CRC32 doğrulama hatası! Beklenen: " + expectedChecksum + ", Gerçek: " + actualChecksum +
                            " konum: " + frameStartPos);
                    break;
                }

                RecordType type = RecordType.fromCode(typeCode);
                records.add(new KeyValueRecord(key, value, type, seqNum, timestamp));
            }
        }

        return records;
    }

    private static int readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        int totalRead = 0;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read == -1) {
                break;
            }
            totalRead += read;
        }
        return totalRead;
    }
}
