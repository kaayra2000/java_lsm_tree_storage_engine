package dev.lsm.memtable;

import dev.lsm.model.ByteUtils;
import dev.lsm.model.KeyValueRecord;

import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;

public final class MemTable implements Iterable<KeyValueRecord> {
    private final long walId;
    private final ConcurrentSkipListMap<byte[], KeyValueRecord> map;
    private long byteSize = 0;
    private long recordCount = 0;
    private boolean frozen = false;
    private long minSeqNum = Long.MAX_VALUE;
    private long maxSeqNum = 0;

    public MemTable(long walId) {
        this.walId = walId;
        this.map = new ConcurrentSkipListMap<>(ByteUtils.COMPARATOR);
    }

    public synchronized void put(KeyValueRecord record) {
        if (frozen) throw new IllegalStateException("MemTable dondurulmuştur!");
        byte[] key = record.key();
        KeyValueRecord existing = map.put(key, record);
        long delta = record.estimatedSizeInBytes();
        if (existing != null) delta -= existing.estimatedSizeInBytes();
        else recordCount++;
        byteSize += delta;

        long seq = record.sequenceNumber();
        if (seq < minSeqNum) minSeqNum = seq;
        if (seq > maxSeqNum) maxSeqNum = seq;
    }

    public KeyValueRecord get(byte[] key) { return map.get(key); }
    public boolean containsKey(byte[] key) { return map.containsKey(key); }

    public synchronized MemTableSnapshot freeze() {
        if (!frozen) {
            frozen = true;
            return new MemTableSnapshot(this);
        }
        throw new IllegalStateException("Zaten dondurulmuş!");
    }

    public boolean isFrozen() { return frozen; }
    public long getWalId() { return walId; }
    public synchronized long getByteSize() { return byteSize; }
    public synchronized long getRecordCount() { return recordCount; }
    public synchronized long getMinSeqNum() { return minSeqNum == Long.MAX_VALUE ? 0 : minSeqNum; }
    public synchronized long getMaxSeqNum() { return maxSeqNum; }
    public boolean isEmpty() { return map.isEmpty(); }

    @Override
    public Iterator<KeyValueRecord> iterator() { return map.values().iterator(); }

    public Iterator<KeyValueRecord> range(byte[] startKey, byte[] endKey) {
        NavigableMap<byte[], KeyValueRecord> subMap;
        if (startKey == null && endKey == null) subMap = map;
        else if (startKey == null) subMap = map.headMap(endKey, false);
        else if (endKey == null) subMap = map.tailMap(startKey, true);
        else subMap = map.subMap(startKey, true, endKey, false);
        return subMap.values().iterator();
    }

    public byte[] firstKey() { return map.isEmpty() ? null : map.firstKey(); }
    public byte[] lastKey() { return map.isEmpty() ? null : map.lastKey(); }
}
