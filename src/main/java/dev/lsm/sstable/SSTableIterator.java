package dev.lsm.sstable;

import dev.lsm.model.KeyValueRecord;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;

/**
 * Bir SSTable içerisindeki tüm kayıtları blok blok diski yormadan okuyan yineleyici.
 */
public final class SSTableIterator implements Iterator<KeyValueRecord> {

    private final SSTableReader reader;
    private final int totalBlocks;
    private int currentBlockIdx = 0;
    private List<KeyValueRecord> currentBlockRecords = Collections.emptyList();
    private int currentRecordIdxInBlock = 0;

    public SSTableIterator(SSTableReader reader) {
        this.reader = reader;
        this.totalBlocks = reader.getBlockCount();
        advanceToNextAvailable();
    }

    private void advanceToNextAvailable() {
        while (currentRecordIdxInBlock >= currentBlockRecords.size()) {
            if (currentBlockIdx >= totalBlocks) {
                return; // Tükendi
            }
            try {
                currentBlockRecords = reader.readBlockByIndex(currentBlockIdx++);
                currentRecordIdxInBlock = 0;
            } catch (IOException e) {
                throw new UncheckedIOException("SSTable bloğu okunurken hata: " + reader.getFilePath(), e);
            }
        }
    }

    @Override
    public boolean hasNext() {
        return currentRecordIdxInBlock < currentBlockRecords.size();
    }

    @Override
    public KeyValueRecord next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        KeyValueRecord record = currentBlockRecords.get(currentRecordIdxInBlock++);
        if (currentRecordIdxInBlock >= currentBlockRecords.size()) {
            advanceToNextAvailable();
        }
        return record;
    }
}
