package dev.lsm.compaction;

import dev.lsm.manifest.FileMetaData;
import dev.lsm.manifest.Version;
import dev.lsm.model.ByteUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Seviyeli Birleştirme Stratejisi (Leveled Compaction Strategy).
 * Her seviyenin boyut sınırlarını ve L0 dosya eşiğini denetleyerek
 * yürütülecek en uygun birleştirme görevini (CompactionTask) belirler.
 */
public final class CompactionStrategy {

    private final int l0FileThreshold;
    private final long l1BaseBytes;
    private final int levelSizeMultiplier;

    public CompactionStrategy(int l0FileThreshold, long l1BaseBytes, int levelSizeMultiplier) {
        this.l0FileThreshold = Math.max(2, l0FileThreshold);
        this.l1BaseBytes = Math.max(1024 * 1024, l1BaseBytes);
        this.levelSizeMultiplier = Math.max(2, levelSizeMultiplier);
    }

    /**
     * Mevcut sürüm üzerinde birleştirme ihtiyacını inceler ve ilk öncelikli görevi döner.
     */
    public CompactionTask pickCompactionTask(Version version) {
        // 1. Level 0 Kontrolü (Dosya adedi eşiği)
        List<FileMetaData> l0Files = version.getFilesAtLevel(0);
        if (l0Files.size() >= l0FileThreshold) {
            byte[] minKey = null;
            byte[] maxKey = null;
            for (FileMetaData f : l0Files) {
                if (minKey == null || ByteUtils.compare(f.firstKey(), minKey) < 0) minKey = f.firstKey();
                if (maxKey == null || ByteUtils.compare(f.lastKey(), maxKey) > 0) maxKey = f.lastKey();
            }

            List<FileMetaData> l1Files = version.getFilesAtLevel(1);
            List<FileMetaData> overlappingL1 = new ArrayList<>();
            for (FileMetaData f1 : l1Files) {
                if (f1.overlapsWith(minKey, maxKey)) {
                    overlappingL1.add(f1);
                }
            }

            boolean isBottom = isBottomLevel(version, 1);
            return new CompactionTask(0, 1, new ArrayList<>(l0Files), overlappingL1, isBottom);
        }

        // 2. Level 1 ve Üstü Kontrolü (Bayt boyutu eşiği)
        long currentLevelLimit = l1BaseBytes;
        for (int lvl = 1; lvl < Version.MAX_LEVELS - 1; lvl++) {
            long totalBytes = version.getTotalBytesAtLevel(lvl);
            if (totalBytes > currentLevelLimit) {
                List<FileMetaData> fromFiles = version.getFilesAtLevel(lvl);
                if (!fromFiles.isEmpty()) {
                    // İlk dosyayı seç
                    FileMetaData candidate = fromFiles.getFirst();
                    List<FileMetaData> toLevelFiles = version.getFilesAtLevel(lvl + 1);
                    List<FileMetaData> overlappingTo = new ArrayList<>();
                    for (FileMetaData toF : toLevelFiles) {
                        if (toF.overlapsWith(candidate.firstKey(), candidate.lastKey())) {
                            overlappingTo.add(toF);
                        }
                    }

                    boolean isBottom = isBottomLevel(version, lvl + 1);
                    return new CompactionTask(lvl, lvl + 1, List.of(candidate), overlappingTo, isBottom);
                }
            }
            currentLevelLimit *= levelSizeMultiplier;
        }

        return null;
    }

    private boolean isBottomLevel(Version version, int targetLevel) {
        for (int lvl = targetLevel + 1; lvl < Version.MAX_LEVELS; lvl++) {
            if (version.getFileCountAtLevel(lvl) > 0) {
                return false;
            }
        }
        return true;
    }
}
