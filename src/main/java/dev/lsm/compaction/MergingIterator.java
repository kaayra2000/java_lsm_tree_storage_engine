package dev.lsm.compaction;

import dev.lsm.model.ByteUtils;
import dev.lsm.model.KeyValueRecord;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

public final class MergingIterator implements Iterator<KeyValueRecord> {
    private record HeapEntry(KeyValueRecord record, Iterator<KeyValueRecord> sourceIterator) {}

    private final PriorityQueue<HeapEntry> minHeap;
    private final boolean dropTombstones;
    private KeyValueRecord nextRecord;

    public MergingIterator(List<Iterator<KeyValueRecord>> iterators, boolean dropTombstones) {
        this.dropTombstones = dropTombstones;
        Comparator<HeapEntry> heapComparator = (a, b) -> {
            int cmp = ByteUtils.compare(a.record.key(), b.record.key());
            if (cmp != 0) return cmp;
            return Long.compare(b.record.sequenceNumber(), a.record.sequenceNumber());
        };
        this.minHeap = new PriorityQueue<>(Math.max(1, iterators.size()), heapComparator);
        for (Iterator<KeyValueRecord> it : iterators) {
            if (it.hasNext()) minHeap.add(new HeapEntry(it.next(), it));
        }
        advance();
    }

    private void advance() {
        nextRecord = null;
        if (!minHeap.isEmpty()) {
            HeapEntry top = minHeap.poll();
            KeyValueRecord candidate = top.record;
            if (top.sourceIterator.hasNext()) {
                minHeap.add(new HeapEntry(top.sourceIterator.next(), top.sourceIterator));
            }
            if (dropTombstones && candidate.isTombstone()) {
                advance();
                return;
            }
            nextRecord = candidate;
        }
    }

    @Override
    public boolean hasNext() { return nextRecord != null; }

    @Override
    public KeyValueRecord next() {
        if (!hasNext()) throw new NoSuchElementException();
        KeyValueRecord current = nextRecord;
        advance();
        return current;
    }
}
