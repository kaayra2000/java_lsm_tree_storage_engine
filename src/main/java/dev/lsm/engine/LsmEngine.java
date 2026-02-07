package dev.lsm.engine;

import dev.lsm.compaction.CompactionStrategy;
import dev.lsm.compaction.Compactor;
import dev.lsm.compaction.MergingIterator;
import dev.lsm.manifest.FileMetaData;
import dev.lsm.manifest.ManifestManager;
import dev.lsm.manifest.Version;
import dev.lsm.manifest.VersionEdit;
import dev.lsm.memtable.MemTable;
import dev.lsm.memtable.MemTableSnapshot;
import dev.lsm.model.ByteUtils;
import dev.lsm.model.Entry;
import dev.lsm.model.KeyValueRecord;
import dev.lsm.sstable.BlockCache;
import dev.lsm.sstable.SSTableReader;
import dev.lsm.sstable.SSTableWriter;
import dev.lsm.wal.WalManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.logging.Logger;

public final class LsmEngine implements AutoCloseable {
    private static final Logger LOGGER = Logger.getLogger(LsmEngine.class.getName());
    private final LsmConfig config;
    private final BlockCache blockCache;
    private final ManifestManager manifestManager;
    private final WalManager walManager;
    private final Compactor compactor;
    private final ReentrantReadWriteLock rwLock = new ReentrantReadWriteLock();
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicLong nextSequenceNumber = new AtomicLong(0);

    private volatile MemTable activeMemTable;
    private volatile MemTableSnapshot immutableMemTable;

    private final AtomicLong totalPuts = new AtomicLong(0);
    private final AtomicLong totalGets = new AtomicLong(0);
    private final AtomicLong totalDeletes = new AtomicLong(0);
    private final AtomicLong memTableHits = new AtomicLong(0);
    private final AtomicLong immutableHits = new AtomicLong(0);
    private final AtomicLong sstableHits = new AtomicLong(0);
    private final AtomicLong bloomFilterBypasses = new AtomicLong(0);

    public static LsmEngine open(LsmConfig config) throws IOException {
        Files.createDirectories(config.dbPath());
        Path walDir = config.dbPath().resolve("wal");
        Files.createDirectories(walDir);

        BlockCache blockCache = new BlockCache(config.blockCacheCapacity());
        ManifestManager manifestManager = new ManifestManager(config.dbPath());
        WalManager walManager = new WalManager(walDir, config.syncWalOnWrite());
        CompactionStrategy strategy = new CompactionStrategy(config.l0CompactionThreshold(), config.l1BaseBytes(), config.levelSizeMultiplier());
        Compactor compactor = new Compactor(config.dbPath(), manifestManager, strategy, blockCache, config.targetBlockSize(), config.bloomFpp(), config.sstableTargetSizeBytes());

        LsmEngine engine = new LsmEngine(config, blockCache, manifestManager, walManager, compactor);
        engine.recover();
        return engine;
    }

    private LsmEngine(LsmConfig config, BlockCache blockCache, ManifestManager manifestManager, WalManager walManager, Compactor compactor) {
        this.config = config;
        this.blockCache = blockCache;
        this.manifestManager = manifestManager;
        this.walManager = walManager;
        this.compactor = compactor;
    }

    private void recover() throws IOException {
        long currentWalId = walManager.getCurrentWalId();
        if (currentWalId == 0) {
            walManager.rotate();
            currentWalId = walManager.getCurrentWalId();
        }
        this.activeMemTable = new MemTable(currentWalId);
        List<KeyValueRecord> recoveredRecords = walManager.recoverAll();
        long maxSeq = manifestManager.getLastSequenceNumber();
        for (KeyValueRecord record : recoveredRecords) {
            activeMemTable.put(record);
            if (record.sequenceNumber() > maxSeq) maxSeq = record.sequenceNumber();
        }
        nextSequenceNumber.set(maxSeq);
        manifestManager.setLastSequenceNumber(maxSeq);
    }

    public void put(byte[] key, byte[] value) throws IOException {
        ensureOpen();
        totalPuts.incrementAndGet();
        long seq = nextSequenceNumber.incrementAndGet();
        KeyValueRecord record = KeyValueRecord.put(key, value, seq);
        rwLock.writeLock().lock();
        try {
            walManager.append(record);
            activeMemTable.put(record);
            if (activeMemTable.getByteSize() >= config.memTableThresholdBytes()) {
                triggerMemTableFlush();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void put(String key, String value) throws IOException {
        put(ByteUtils.toBytes(key), ByteUtils.toBytes(value));
    }

    public Optional<byte[]> get(byte[] key) throws IOException {
        ensureOpen();
        totalGets.incrementAndGet();
        rwLock.readLock().lock();
        try {
            MemTable currentMem = activeMemTable;
            KeyValueRecord record = currentMem.get(key);
            if (record != null) {
                memTableHits.incrementAndGet();
                return record.isTombstone() ? Optional.empty() : Optional.of(record.value());
            }

            MemTableSnapshot imm = immutableMemTable;
            if (imm != null) {
                record = imm.get(key);
                if (record != null) {
                    immutableHits.incrementAndGet();
                    return record.isTombstone() ? Optional.empty() : Optional.of(record.value());
                }
            }

            Version version = manifestManager.getCurrentVersion();
            for (FileMetaData fileMeta : version.getFilesAtLevel(0)) {
                record = searchInSSTable(fileMeta, key);
                if (record != null) {
                    sstableHits.incrementAndGet();
                    return record.isTombstone() ? Optional.empty() : Optional.of(record.value());
                }
            }
            return Optional.empty();
        } finally {
            rwLock.readLock().unlock();
        }
    }

    public Optional<String> get(String key) throws IOException {
        return get(ByteUtils.toBytes(key)).map(ByteUtils::toString);
    }

    public void delete(byte[] key) throws IOException {
        ensureOpen();
        totalDeletes.incrementAndGet();
        long seq = nextSequenceNumber.incrementAndGet();
        KeyValueRecord record = KeyValueRecord.delete(key, seq);
        rwLock.writeLock().lock();
        try {
            walManager.append(record);
            activeMemTable.put(record);
            if (activeMemTable.getByteSize() >= config.memTableThresholdBytes()) {
                triggerMemTableFlush();
            }
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    public void delete(String key) throws IOException { delete(ByteUtils.toBytes(key)); }

    public synchronized void flush() throws IOException {
        ensureOpen();
        rwLock.writeLock().lock();
        try {
            if (activeMemTable.isEmpty()) return;
            triggerMemTableFlush();
        } finally {
            rwLock.writeLock().unlock();
        }
    }

    private void triggerMemTableFlush() throws IOException {
        if (activeMemTable.isEmpty()) return;
        immutableMemTable = activeMemTable.freeze();
        walManager.rotate();
        this.activeMemTable = new MemTable(walManager.getCurrentWalId());

        long newSstId = manifestManager.getNextSstableId();
        Path sstPath = config.dbPath().resolve(String.format("table-%08d.sst", newSstId));
        SSTableWriter writer = new SSTableWriter(sstPath, newSstId, config.targetBlockSize(), config.bloomFpp());
        SSTableWriter.SSTableSummary summary = writer.write(immutableMemTable.iterator(), (int) immutableMemTable.getRecordCount());
        if (summary.recordCount() > 0) {
            long fileSize = Files.size(sstPath);
            FileMetaData meta = new FileMetaData(newSstId, 0, fileSize, summary.firstKey(), summary.lastKey(), summary.minSeqNum(), summary.maxSeqNum());
            VersionEdit edit = new VersionEdit();
            edit.addFile(meta);
            manifestManager.logAndApply(edit);
        }
        walManager.purgeWalsBefore(immutableMemTable.getWalId());
        immutableMemTable = null;
        compactor.trigger();
    }

    public synchronized void compact() throws IOException {
        ensureOpen();
        while (compactor.performOneCompaction()) {}
    }

    private KeyValueRecord searchInSSTable(FileMetaData meta, byte[] key) throws IOException {
        Path path = config.dbPath().resolve(String.format("table-%08d.sst", meta.sstableId()));
        if (!Files.exists(path)) return null;
        try (SSTableReader reader = SSTableReader.open(meta.sstableId(), path, blockCache)) {
            return reader.get(key);
        }
    }

    public boolean contains(byte[] key) throws IOException { return get(key).isPresent(); }
    public boolean contains(String key) throws IOException { return contains(ByteUtils.toBytes(key)); }

    public Iterator<Entry> scan(byte[] startKey, byte[] endKey) throws IOException {
        ensureOpen();
        List<Iterator<KeyValueRecord>> iterators = new ArrayList<>();
        iterators.add(activeMemTable.range(startKey, endKey));
        if (immutableMemTable != null) iterators.add(immutableMemTable.range(startKey, endKey));
        MergingIterator merged = new MergingIterator(iterators, false);
        return new Iterator<>() {
            @Override public boolean hasNext() { return merged.hasNext(); }
            @Override public Entry next() {
                KeyValueRecord r = merged.next();
                return new Entry(r.key(), r.value());
            }
        };
    }

    public EngineStats getStats() {
        return new EngineStats(totalPuts.get(), totalGets.get(), totalDeletes.get(), memTableHits.get(), immutableHits.get(), sstableHits.get(), bloomFilterBypasses.get(), blockCache.getHits(), blockCache.getMisses(), blockCache.getHitRate(), activeMemTable.getByteSize(), activeMemTable.getRecordCount(), 0, 0);
    }

    private void ensureOpen() {
        if (closed.get()) throw new IllegalStateException("LSM Depolama Motoru kapalıdır!");
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed.compareAndSet(false, true)) {
            if (activeMemTable != null && !activeMemTable.isEmpty()) triggerMemTableFlush();
            compactor.close();
            walManager.close();
            manifestManager.close();
            blockCache.clear();
        }
    }
}
