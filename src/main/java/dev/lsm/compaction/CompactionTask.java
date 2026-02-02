package dev.lsm.compaction;

import dev.lsm.manifest.FileMetaData;

import java.util.List;

/**
 * Belirli iki seviye arasındaki birleştirme (compaction) işleminin girdilerini tanımlar.
 */
public record CompactionTask(
        int fromLevel,
        int toLevel,
        List<FileMetaData> fromLevelFiles,
        List<FileMetaData> toLevelFiles,
        boolean isBottomLevel
) {
    public int totalInputFiles() {
        return fromLevelFiles.size() + toLevelFiles.size();
    }
}
