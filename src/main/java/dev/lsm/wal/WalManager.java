package dev.lsm.wal;

import dev.lsm.model.KeyValueRecord;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * WAL dosyalarının yaşam döngüsünü (oluşturma, rotasyon, temizleme ve kurtarma) yönetir.
 */
public final class WalManager implements AutoCloseable {

    private final Path walDir;
    private final boolean syncOnWrite;
    private final AtomicLong currentWalId = new AtomicLong(0);
    private WalWriter currentWriter;

    public WalManager(Path walDir, boolean syncOnWrite) throws IOException {
        this.walDir = walDir;
        this.syncOnWrite = syncOnWrite;
        Files.createDirectories(walDir);

        long maxExistingId = findMaxWalId();
        this.currentWalId.set(maxExistingId);
    }

    /**
     * Yeni bir WAL dosyası başlatır.
     */
    public synchronized WalWriter rotate() throws IOException {
        if (currentWriter != null) {
            currentWriter.close();
        }
        long nextId = currentWalId.incrementAndGet();
        Path nextWalFile = getWalPath(nextId);
        this.currentWriter = new WalWriter(nextWalFile, syncOnWrite);
        return this.currentWriter;
    }

    public synchronized WalWriter getCurrentWriter() throws IOException {
        if (currentWriter == null) {
            rotate();
        }
        return currentWriter;
    }

    public synchronized void append(KeyValueRecord record) throws IOException {
        getCurrentWriter().append(record);
    }

    public synchronized void sync() throws IOException {
        if (currentWriter != null) {
            currentWriter.sync();
        }
    }

    /**
     * Belirtilen WAL id'sine kadar olan eski WAL dosyalarını siler.
     */
    public synchronized void purgeWalsBefore(long walId) throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir, "wal-*.log")) {
            for (Path entry : stream) {
                long id = extractWalId(entry.getFileName().toString());
                if (id > 0 && id < walId) {
                    Files.deleteIfExists(entry);
                }
            }
        }
    }

    /**
     * Dizindeki mevcut tüm WAL dosyalarını sırayla okuyup kurtarır.
     */
    public List<KeyValueRecord> recoverAll() throws IOException {
        List<Path> walFiles = listWalFiles();
        List<KeyValueRecord> allRecords = new ArrayList<>();
        for (Path walFile : walFiles) {
            List<KeyValueRecord> records = WalReader.readAll(walFile);
            allRecords.addAll(records);
        }
        return allRecords;
    }

    public List<Path> listWalFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir, "wal-*.log")) {
            for (Path entry : stream) {
                files.add(entry);
            }
        }
        files.sort((a, b) -> Long.compare(
                extractWalId(a.getFileName().toString()),
                extractWalId(b.getFileName().toString())
        ));
        return files;
    }

    public Path getWalPath(long id) {
        return walDir.resolve(String.format("wal-%08d.log", id));
    }

    public long getCurrentWalId() {
        return currentWalId.get();
    }

    private long findMaxWalId() throws IOException {
        long maxId = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(walDir, "wal-*.log")) {
            for (Path entry : stream) {
                long id = extractWalId(entry.getFileName().toString());
                if (id > maxId) {
                    maxId = id;
                }
            }
        }
        return maxId;
    }

    private long extractWalId(String filename) {
        try {
            if (filename.startsWith("wal-") && filename.endsWith(".log")) {
                String idPart = filename.substring(4, filename.length() - 4);
                return Long.parseLong(idPart);
            }
        } catch (NumberFormatException ignored) {}
        return -1;
    }

    @Override
    public synchronized void close() throws IOException {
        if (currentWriter != null) {
            currentWriter.close();
            currentWriter = null;
        }
    }
}
