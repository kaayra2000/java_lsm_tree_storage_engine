package dev.lsm.manifest;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;
import java.util.zip.CRC32;

/**
 * LSM-Tree MANIFEST dosyasını yöneten sınıf.
 * Seviye değişimlerini, SSTable yaşam döngüsünü ve sürüm geçişlerini diske kaydeder.
 */
public final class ManifestManager implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(ManifestManager.class.getName());
    private static final String MANIFEST_FILE = "MANIFEST";

    private final Path dbDir;
    private final Path manifestPath;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final CRC32 crc32 = new CRC32();

    private Version currentVersion;
    private final AtomicLong nextSstableId = new AtomicLong(1);
    private final AtomicLong lastSequenceNumber = new AtomicLong(0);
    private FileChannel manifestChannel;

    public ManifestManager(Path dbDir) throws IOException {
        this.dbDir = dbDir;
        this.manifestPath = dbDir.resolve(MANIFEST_FILE);
        this.currentVersion = new Version();

        recoverOrCreate();
    }

    private void recoverOrCreate() throws IOException {
        if (Files.exists(manifestPath) && Files.size(manifestPath) > 0) {
            replayManifest();
        } else {
            this.manifestChannel = FileChannel.open(
                    manifestPath,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND
            );
        }
    }

    private void replayManifest() throws IOException {
        try (FileChannel channel = FileChannel.open(manifestPath, StandardOpenOption.READ)) {
            ByteBuffer headerBuf = ByteBuffer.allocate(8); // CRC(4) + Length(4)

            while (channel.position() < channel.size()) {
                headerBuf.clear();
                int read = channel.read(headerBuf);
                if (read < 8) break;
                headerBuf.flip();

                int expectedCrc = headerBuf.getInt();
                int length = headerBuf.getInt();

                if (length <= 0 || length > 32 * 1024 * 1024) {
                    LOGGER.warning("Geçersiz MANIFEST kayıt uzunluğu: " + length);
                    break;
                }

                ByteBuffer payload = ByteBuffer.allocate(length);
                int payloadRead = channel.read(payload);
                if (payloadRead < length) {
                    LOGGER.warning("Eksik MANIFEST kaydı okundu, kurtarma durduruldu.");
                    break;
                }
                payload.flip();

                crc32.reset();
                crc32.update(payload.array(), 0, length);
                if ((int) crc32.getValue() != expectedCrc) {
                    LOGGER.severe("MANIFEST CRC32 hatası tespit edildi!");
                    break;
                }

                VersionEdit edit = VersionEdit.fromBytes(payload);
                this.currentVersion = this.currentVersion.apply(edit);

                if (edit.getNextSstableId() != null && edit.getNextSstableId() > nextSstableId.get()) {
                    nextSstableId.set(edit.getNextSstableId());
                }
                if (edit.getLastSeqNum() != null && edit.getLastSeqNum() > lastSequenceNumber.get()) {
                    lastSequenceNumber.set(edit.getLastSeqNum());
                }
            }
        }

        // Açık yazma kanalı bağla
        this.manifestChannel = FileChannel.open(
                manifestPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND
        );
    }

    /**
     * Sürüm değişikliğini MANIFEST kütüğüne kalıcı olarak yazar ve yeni sürümü devreye alır.
     */
    public void logAndApply(VersionEdit edit) throws IOException {
        lock.writeLock().lock();
        try {
            if (edit.getNextSstableId() != null) {
                nextSstableId.set(Math.max(nextSstableId.get(), edit.getNextSstableId()));
            }
            if (edit.getLastSeqNum() != null) {
                lastSequenceNumber.set(Math.max(lastSequenceNumber.get(), edit.getLastSeqNum()));
            }

            byte[] bytes = edit.toBytes();

            crc32.reset();
            crc32.update(bytes);
            int checksum = (int) crc32.getValue();

            ByteBuffer buf = ByteBuffer.allocate(8 + bytes.length);
            buf.putInt(checksum);
            buf.putInt(bytes.length);
            buf.put(bytes);
            buf.flip();

            while (buf.hasRemaining()) {
                manifestChannel.write(buf);
            }
            manifestChannel.force(false);

            // Yeni sürümü uygula
            this.currentVersion = this.currentVersion.apply(edit);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Version getCurrentVersion() {
        lock.readLock().lock();
        try {
            return currentVersion;
        } finally {
            lock.readLock().unlock();
        }
    }

    public long getNextSstableId() {
        return nextSstableId.getAndIncrement();
    }

    public long getLastSequenceNumber() {
        return lastSequenceNumber.get();
    }

    public void setLastSequenceNumber(long seq) {
        lastSequenceNumber.updateAndGet(current -> Math.max(current, seq));
    }

    @Override
    public void close() throws IOException {
        lock.writeLock().lock();
        try {
            if (manifestChannel != null && manifestChannel.isOpen()) {
                manifestChannel.force(true);
                manifestChannel.close();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }
}
