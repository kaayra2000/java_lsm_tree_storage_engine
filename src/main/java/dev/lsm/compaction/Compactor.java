package dev.lsm.compaction;

import dev.lsm.manifest.FileMetaData;
import dev.lsm.manifest.ManifestManager;
import dev.lsm.manifest.Version;
import dev.lsm.manifest.VersionEdit;
import dev.lsm.model.KeyValueRecord;
import dev.lsm.sstable.BlockCache;
import dev.lsm.sstable.SSTableReader;
import dev.lsm.sstable.SSTableWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Arka planda Sanal İş Parçacığı (Virtual Thread) üzerinde çalışan
 * Seviyeli Birleştirici (Leveled Compactor).
 * Çakışan ve eskimiş anahtarları birleştirerek okuma gecikmesini ($O(\log N)$)
 * ve disk alanını optimize eder.
 */
public final class Compactor implements AutoCloseable {

    private static final Logger LOGGER = Logger.getLogger(Compactor.class.getName());

    private final Path dbDir;
    private final ManifestManager manifestManager;
    private final CompactionStrategy strategy;
    private final BlockCache blockCache;
    private final int targetBlockSize;
    private final double fpp;
    private final long targetFileSize;

    private final AtomicBoolean running = new AtomicBoolean(true);
    private final ReentrantLock triggerLock = new ReentrantLock();
    private final Condition triggerCondition = triggerLock.newCondition();
    private final Thread workerThread;

    public Compactor(Path dbDir, ManifestManager manifestManager,
                     CompactionStrategy strategy, BlockCache blockCache,
                     int targetBlockSize, double fpp, long targetFileSize) {
        this.dbDir = dbDir;
        this.manifestManager = manifestManager;
        this.strategy = strategy;
        this.blockCache = blockCache;
        this.targetBlockSize = targetBlockSize;
        this.fpp = fpp;
        this.targetFileSize = targetFileSize;

        this.workerThread = Thread.ofVirtual().name("lsm-compactor-worker").start(this::runLoop);
    }

    /**
     * Arka plan iş parçacığına birleştirme denetimi sinyali gönderir.
     */
    public void trigger() {
        triggerLock.lock();
        try {
            triggerCondition.signal();
        } finally {
            triggerLock.unlock();
        }
    }

    private void runLoop() {
        while (running.get()) {
            triggerLock.lock();
            try {
                triggerCondition.awaitNanos(500_000_000L); // 500ms bekleme
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } finally {
                triggerLock.unlock();
            }

            if (!running.get()) break;

            try {
                while (performOneCompaction()) {
                    if (!running.get()) break;
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Birleştirme döngüsünde hata oluştu", e);
            }
        }
    }

    /**
     * Tek bir birleştirme görevini senkron olarak icra eder.
     * @return Birleştirme yapıldıysa true, gerek görülmediyse false
     */
    public synchronized boolean performOneCompaction() throws IOException {
        Version currentVersion = manifestManager.getCurrentVersion();
        CompactionTask task = strategy.pickCompactionTask(currentVersion);
        if (task == null) {
            return false;
        }

        LOGGER.info(String.format("Compaction başlatıldı: L%d -> L%d (Girdi dosya adedi: %d)",
                task.fromLevel(), task.toLevel(), task.totalInputFiles()));

        List<SSTableReader> readersToClose = new ArrayList<>();
        List<Iterator<KeyValueRecord>> iterators = new ArrayList<>();

        try {
            // Kaynak seviye okuyucuları
            for (FileMetaData meta : task.fromLevelFiles()) {
                Path p = dbDir.resolve(String.format("table-%08d.sst", meta.sstableId()));
                if (Files.exists(p)) {
                    SSTableReader r = SSTableReader.open(meta.sstableId(), p, blockCache);
                    readersToClose.add(r);
                    iterators.add(r.iterator());
                }
            }

            // Hedef seviye okuyucuları
            for (FileMetaData meta : task.toLevelFiles()) {
                Path p = dbDir.resolve(String.format("table-%08d.sst", meta.sstableId()));
                if (Files.exists(p)) {
                    SSTableReader r = SSTableReader.open(meta.sstableId(), p, blockCache);
                    readersToClose.add(r);
                    iterators.add(r.iterator());
                }
            }

            MergingIterator mergingIterator = new MergingIterator(iterators, task.isBottomLevel());

            List<FileMetaData> newlyCreatedFiles = new ArrayList<>();
            VersionEdit edit = new VersionEdit();

            // Giriş dosyalarını silinecek olarak işaretle
            for (FileMetaData f : task.fromLevelFiles()) {
                edit.deleteFile(task.fromLevel(), f.sstableId());
            }
            for (FileMetaData f : task.toLevelFiles()) {
                edit.deleteFile(task.toLevel(), f.sstableId());
            }

            // Çıktı SSTable'larını hedef boyuta göre bölerek yaz
            while (mergingIterator.hasNext()) {
                long newSstId = manifestManager.getNextSstableId();
                Path newSstPath = dbDir.resolve(String.format("table-%08d.sst", newSstId));

                SSTableWriter writer = new SSTableWriter(newSstPath, newSstId, targetBlockSize, fpp);

                // Yaklaşık kayıt tahmini
                int estRecords = 4096;
                SSTableWriter.SSTableSummary summary = writer.write(new ChunkIterator(mergingIterator, targetFileSize), estRecords);

                if (summary.recordCount() > 0) {
                    long size = Files.size(newSstPath);
                    FileMetaData meta = new FileMetaData(
                            newSstId,
                            task.toLevel(),
                            size,
                            summary.firstKey(),
                            summary.lastKey(),
                            summary.minSeqNum(),
                            summary.maxSeqNum()
                    );
                    newlyCreatedFiles.add(meta);
                    edit.addFile(meta);
                } else {
                    Files.deleteIfExists(newSstPath);
                }
            }

            // Manifest'e uygula
            manifestManager.logAndApply(edit);

        } finally {
            // Okuyucuları kapat
            for (SSTableReader r : readersToClose) {
                try {
                    r.close();
                } catch (Exception ignored) {}
            }

            // Eski SSTable dosyalarını diskten sil ve önbelleği temizle
            for (FileMetaData f : task.fromLevelFiles()) {
                blockCache.invalidate(f.sstableId());
                Path p = dbDir.resolve(String.format("table-%08d.sst", f.sstableId()));
                Files.deleteIfExists(p);
            }
            for (FileMetaData f : task.toLevelFiles()) {
                blockCache.invalidate(f.sstableId());
                Path p = dbDir.resolve(String.format("table-%08d.sst", f.sstableId()));
                Files.deleteIfExists(p);
            }
        }

        LOGGER.info(String.format("Compaction tamamlandı: L%d -> L%d", task.fromLevel(), task.toLevel()));
        return true;
    }

    /**
     * Tek bir SSTable'ın hedef boyuta ulaşana kadar kayıt tüketmesini sağlayan sarmalayıcı yineleyici.
     */
    private static class ChunkIterator implements Iterator<KeyValueRecord> {
        private final MergingIterator source;
        private final long maxBytes;
        private long bytesConsumed = 0;

        public ChunkIterator(MergingIterator source, long maxBytes) {
            this.source = source;
            this.maxBytes = maxBytes;
        }

        @Override
        public boolean hasNext() {
            return source.hasNext() && bytesConsumed < maxBytes;
        }

        @Override
        public KeyValueRecord next() {
            KeyValueRecord rec = source.next();
            bytesConsumed += rec.estimatedSizeInBytes();
            return rec;
        }
    }

    @Override
    public void close() {
        running.set(false);
        trigger();
        try {
            workerThread.join(2000);
        } catch (InterruptedException ignored) {}
    }
}
