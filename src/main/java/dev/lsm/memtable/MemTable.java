package dev.lsm.memtable;

import dev.lsm.model.ByteUtils;
import dev.lsm.model.KeyValueRecord;
import dev.lsm.model.RecordType;

import java.util.Iterator;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * LSM-Tree bellek içi yazma tablosu (MemTable).
 * Eşzamanlı atlama listesi (ConcurrentSkipListMap) üzerinde $O(\log N)$ arama,
 * ekleme ve sıralı tarama performansı sunar.
 */
public final class MemTable implements Iterable<KeyValueRecord> {

    private final long walId;
    private final ConcurrentSkipListMap<byte[], KeyValueRecord> map;
    private final AtomicLong byteSize = new AtomicLong(0);
    private final AtomicLong recordCount = new AtomicLong(0);
    private final AtomicBoolean frozen = new AtomicBoolean(false);
    private final AtomicLong minSeqNum = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxSeqNum = new AtomicLong(0);

    public MemTable(long walId) {
        this.walId = walId;
        this.map = new ConcurrentSkipListMap<>(ByteUtils.COMPARATOR);
    }

    /**
     * MemTable'a kayıt ekler veya günceller.
     * @throws IllegalStateException MemTable dondurulmuş (frozen) ise
     */
    public void put(KeyValueRecord record) {
        if (frozen.get()) {
            throw new IllegalStateException("MemTable dondurulmuştur, yeni yazma kabul edilemez!");
        }

        byte[] key = record.key();
        KeyValueRecord existing = map.put(key, record);

        long sizeDelta = record.estimatedSizeInBytes();
        if (existing != null) {
            sizeDelta -= existing.estimatedSizeInBytes();
        } else {
            recordCount.incrementAndGet();
        }
        byteSize.addAndGet(sizeDelta);

        long seq = record.sequenceNumber();
        minSeqNum.updateAndGet(current -> Math.min(current, seq));
        maxSeqNum.updateAndGet(current -> Math.max(current, seq));
    }

    /**
     * Verilen anahtara karşılık gelen kaydı döner.
     */
    public KeyValueRecord get(byte[] key) {
        return map.get(key);
    }

    public boolean containsKey(byte[] key) {
        return map.containsKey(key);
    }

    /**
     * MemTable'ı dondurur ve değiştirilemez bir anlık görüntü (snapshot) üretir.
     */
    public MemTableSnapshot freeze() {
        if (frozen.compareAndSet(false, true)) {
            return new MemTableSnapshot(this);
        }
        throw new IllegalStateException("MemTable zaten dondurulmuş!");
    }

    public boolean isFrozen() {
        return frozen.get();
    }

    public long getWalId() {
        return walId;
    }

    public long getByteSize() {
        return byteSize.get();
    }

    public long getRecordCount() {
        return recordCount.get();
    }

    public long getMinSeqNum() {
        long min = minSeqNum.get();
        return min == Long.MAX_VALUE ? 0 : min;
    }

    public long getMaxSeqNum() {
        return maxSeqNum.get();
    }

    public boolean isEmpty() {
        return map.isEmpty();
    }

    @Override
    public Iterator<KeyValueRecord> iterator() {
        return map.values().iterator();
    }

    /**
     * [startKey, endKey) aralığındaki kayıtların yineleyicisini döner.
     */
    public Iterator<KeyValueRecord> range(byte[] startKey, byte[] endKey) {
        NavigableMap<byte[], KeyValueRecord> subMap;
        if (startKey == null && endKey == null) {
            subMap = map;
        } else if (startKey == null) {
            subMap = map.headMap(endKey, false);
        } else if (endKey == null) {
            subMap = map.tailMap(startKey, true);
        } else {
            subMap = map.subMap(startKey, true, endKey, false);
        }
        return subMap.values().iterator();
    }

    public byte[] firstKey() {
        return map.isEmpty() ? null : map.firstKey();
    }

    public byte[] lastKey() {
        return map.isEmpty() ? null : map.lastKey();
    }
}
