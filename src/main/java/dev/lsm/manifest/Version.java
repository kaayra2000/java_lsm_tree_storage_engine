package dev.lsm.manifest;

import dev.lsm.model.ByteUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Belirli bir andaki tüm seviyelerin (L0..L_max) SSTable dosya listesini tutan
 * değiştirilemez (immutable) sürüm anlık görüntüsü.
 * MVCC okuma güvenliği için referans sayımı (reference counting) uygular.
 */
public final class Version {

    public static final int MAX_LEVELS = 7;

    private final List<List<FileMetaData>> levels;
    private final AtomicInteger refCount = new AtomicInteger(1);

    public Version() {
        this.levels = new ArrayList<>(MAX_LEVELS);
        for (int i = 0; i < MAX_LEVELS; i++) {
            this.levels.add(new ArrayList<>());
        }
    }

    public Version(List<List<FileMetaData>> existingLevels) {
        this.levels = new ArrayList<>(MAX_LEVELS);
        for (int i = 0; i < MAX_LEVELS; i++) {
            List<FileMetaData> list = new ArrayList<>(existingLevels.get(i));
            if (i > 0) {
                // L1 ve sonrası firstKey'e göre sıralı tutulur
                list.sort((a, b) -> ByteUtils.compare(a.firstKey(), b.firstKey()));
            }
            this.levels.add(list);
        }
    }

    public void retain() {
        refCount.incrementAndGet();
    }

    public int release() {
        return refCount.decrementAndGet();
    }

    public int getRefCount() {
        return refCount.get();
    }

    public List<FileMetaData> getFilesAtLevel(int level) {
        if (level < 0 || level >= MAX_LEVELS) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(levels.get(level));
    }

    public int getFileCountAtLevel(int level) {
        if (level < 0 || level >= MAX_LEVELS) return 0;
        return levels.get(level).size();
    }

    public long getTotalBytesAtLevel(int level) {
        if (level < 0 || level >= MAX_LEVELS) return 0;
        long total = 0;
        for (FileMetaData f : levels.get(level)) {
            total += f.fileSize();
        }
        return total;
    }

    public List<FileMetaData> getAllFiles() {
        List<FileMetaData> all = new ArrayList<>();
        for (List<FileMetaData> list : levels) {
            all.addAll(list);
        }
        return all;
    }

    /**
     * Bu sürümün üzerine delta (VersionEdit) uygulayarak yeni bir Version nesnesi üretir.
     */
    public Version apply(VersionEdit edit) {
        List<List<FileMetaData>> newLevels = new ArrayList<>(MAX_LEVELS);
        for (int i = 0; i < MAX_LEVELS; i++) {
            newLevels.add(new ArrayList<>(this.levels.get(i)));
        }

        // Silinen dosyaları kaldır
        for (VersionEdit.DeletedFile del : edit.getDeletedFiles()) {
            if (del.level() >= 0 && del.level() < MAX_LEVELS) {
                newLevels.get(del.level()).removeIf(f -> f.sstableId() == del.sstableId());
            }
        }

        // Eklenen dosyaları ekle
        for (FileMetaData add : edit.getAddedFiles()) {
            if (add.level() >= 0 && add.level() < MAX_LEVELS) {
                newLevels.get(add.level()).add(add);
            }
        }

        return new Version(newLevels);
    }
}
