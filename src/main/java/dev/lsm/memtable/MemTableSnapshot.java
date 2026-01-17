package dev.lsm.memtable;

import dev.lsm.model.KeyValueRecord;

import java.util.Iterator;

/**
 * Diske (SSTable) boşaltılmaya (flush) hazır, değiştirilemez MemTable anlık görüntüsü.
 */
public final class MemTableSnapshot implements Iterable<KeyValueRecord> {

    private final MemTable sourceMemTable;
    private final long walId;
    private final long byteSize;
    private final long recordCount;
    private final long minSeqNum;
    private final long maxSeqNum;
    private final byte[] firstKey;
    private final byte[] lastKey;

    public MemTableSnapshot(MemTable sourceMemTable) {
        this.sourceMemTable = sourceMemTable;
        this.walId = sourceMemTable.getWalId();
        this.byteSize = sourceMemTable.getByteSize();
        this.recordCount = sourceMemTable.getRecordCount();
        this.minSeqNum = sourceMemTable.getMinSeqNum();
        this.maxSeqNum = sourceMemTable.getMaxSeqNum();
        this.firstKey = sourceMemTable.firstKey();
        this.lastKey = sourceMemTable.lastKey();
    }

    public KeyValueRecord get(byte[] key) {
        return sourceMemTable.get(key);
    }

    public long getWalId() {
        return walId;
    }

    public long getByteSize() {
        return byteSize;
    }

    public long getRecordCount() {
        return recordCount;
    }

    public long getMinSeqNum() {
        return minSeqNum;
    }

    public long getMaxSeqNum() {
        return maxSeqNum;
    }

    public byte[] getFirstKey() {
        return firstKey;
    }

    public byte[] getLastKey() {
        return lastKey;
    }

    @Override
    public Iterator<KeyValueRecord> iterator() {
        return sourceMemTable.iterator();
    }

    public Iterator<KeyValueRecord> range(byte[] startKey, byte[] endKey) {
        return sourceMemTable.range(startKey, endKey);
    }
}
