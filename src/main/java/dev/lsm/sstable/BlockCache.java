package dev.lsm.sstable;

import dev.lsm.model.KeyValueRecord;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * SSTable blokları için Least Recently Used (LRU) bellek önbelleği.
 * Tekrarlayan disk erişimlerini ve blok açma maliyetlerini ortadan kaldırır.
 */
public final class BlockCache {

    public record CacheKey(long sstableId, long blockOffset) {}

    private final int capacity;
    private final LinkedHashMap<CacheKey, List<KeyValueRecord>> lruMap;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private final AtomicLong hits = new AtomicLong(0);
    private final AtomicLong misses = new AtomicLong(0);
    private final AtomicLong evictions = new AtomicLong(0);

    public BlockCache(int capacity) {
        this.capacity = Math.max(16, capacity);
        this.lruMap = new LinkedHashMap<>(capacity, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<CacheKey, List<KeyValueRecord>> eldest) {
                boolean shouldRemove = size() > BlockCache.this.capacity;
                if (shouldRemove) {
                    evictions.incrementAndGet();
                }
                return shouldRemove;
            }
        };
    }

    public List<KeyValueRecord> get(long sstableId, long blockOffset) {
        CacheKey key = new CacheKey(sstableId, blockOffset);
        lock.writeLock().lock(); // LRU access order güncellemesi için writeLock
        try {
            List<KeyValueRecord> records = lruMap.get(key);
            if (records != null) {
                hits.incrementAndGet();
                return records;
            } else {
                misses.incrementAndGet();
                return null;
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void put(long sstableId, long blockOffset, List<KeyValueRecord> records) {
        CacheKey key = new CacheKey(sstableId, blockOffset);
        lock.writeLock().lock();
        try {
            lruMap.put(key, records);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void invalidate(long sstableId) {
        lock.writeLock().lock();
        try {
            lruMap.keySet().removeIf(key -> key.sstableId() == sstableId);
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void clear() {
        lock.writeLock().lock();
        try {
            lruMap.clear();
        } finally {
            lock.writeLock().unlock();
        }
    }

    public long getHits() {
        return hits.get();
    }

    public long getMisses() {
        return misses.get();
    }

    public long getEvictions() {
        return evictions.get();
    }

    public double getHitRate() {
        long h = hits.get();
        long total = h + misses.get();
        return total == 0 ? 0.0 : (double) h / total;
    }

    public int size() {
        lock.readLock().lock();
        try {
            return lruMap.size();
        } finally {
            lock.readLock().unlock();
        }
    }
}
