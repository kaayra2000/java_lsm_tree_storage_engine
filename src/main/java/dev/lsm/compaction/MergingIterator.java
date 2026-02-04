package dev.lsm.compaction;

import dev.lsm.model.ByteUtils;
import dev.lsm.model.KeyValueRecord;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;

/**
 * K-Way Merge (K-Yollu Birleştirme) Yineleyicisi.
 * Birden fazla sıralı SSTable veya MemTable yineleyicisini Min-Heap (Öncelik Kuyruğu)
 * kullanarak tek bir sıralı akışta birleştirir.
 * Aynı anahtara sahip kayıtlarda en yüksek sıra numaralı (en güncel) kaydı tutar,
 * eski sürümleri eler.
 */
public final class MergingIterator implements Iterator<KeyValueRecord> {

    private record HeapEntry(KeyValueRecord record, Iterator<KeyValueRecord> sourceIterator) {}

    private final PriorityQueue<HeapEntry> minHeap;
    private final boolean dropTombstones;
    private KeyValueRecord nextRecord;

    public MergingIterator(List<Iterator<KeyValueRecord>> iterators, boolean dropTombstones) {
        this.dropTombstones = dropTombstones;

        // Min-heap: Önce anahtar sözlükbilimsel sıralanır, anahtarlar eşitse YÜKSEK sıra no önce gelir
        Comparator<HeapEntry> heapComparator = (a, b) -> {
            int cmp = ByteUtils.compare(a.record.key(), b.record.key());
            if (cmp != 0) {
                return cmp;
            }
            // Aynı anahtar: Daha yüksek sequence number daha günceldir
            return Long.compare(b.record.sequenceNumber(), a.record.sequenceNumber());
        };

        this.minHeap = new PriorityQueue<>(Math.max(1, iterators.size()), heapComparator);

        for (Iterator<KeyValueRecord> it : iterators) {
            if (it.hasNext()) {
                minHeap.add(new HeapEntry(it.next(), it));
            }
        }

        advance();
    }

    private void advance() {
        nextRecord = null;

        while (!minHeap.isEmpty() && nextRecord == null) {
            HeapEntry top = minHeap.poll();
            KeyValueRecord candidate = top.record;

            // Sıradaki elemanı heap'e tekrar besle
            if (top.sourceIterator.hasNext()) {
                minHeap.add(new HeapEntry(top.sourceIterator.next(), top.sourceIterator));
            }

            // Aynı anahtara sahip daha eski (daha düşük seqNum) kopyaları tüket ve at
            while (!minHeap.isEmpty()) {
                HeapEntry peek = minHeap.peek();
                if (ByteUtils.compare(candidate.key(), peek.record.key()) == 0) {
                    HeapEntry duplicate = minHeap.poll();
                    if (duplicate.sourceIterator.hasNext()) {
                        minHeap.add(new HeapEntry(duplicate.sourceIterator.next(), duplicate.sourceIterator));
                    }
                } else {
                    break;
                }
            }

            // Eğer en alt seviyeye birleştiriyorsak ve kayıt bir mezar taşı (tombstone) ise tamamen kaldır
            if (dropTombstones && candidate.isTombstone()) {
                continue; // Bir sonraki anahtara geç
            }

            nextRecord = candidate;
        }
    }

    @Override
    public boolean hasNext() {
        return nextRecord != null;
    }

    @Override
    public KeyValueRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        KeyValueRecord current = nextRecord;
        advance();
        return current;
    }
}
