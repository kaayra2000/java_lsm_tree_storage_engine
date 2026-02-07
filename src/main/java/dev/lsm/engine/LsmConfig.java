package dev.lsm.engine;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * LSM-Tree Depolama Motoru yapılandırma ayarları.
 */
public record LsmConfig(
        Path dbPath,
        long memTableThresholdBytes,
        int targetBlockSize,
        double bloomFpp,
        int blockCacheCapacity,
        int l0CompactionThreshold,
        long l1BaseBytes,
        int levelSizeMultiplier,
        long sstableTargetSizeBytes,
        boolean syncWalOnWrite
) {

    public static Builder builder() {
        return new Builder();
    }

    public static LsmConfig defaultConfig(Path dbPath) {
        return builder().dbPath(dbPath).build();
    }

    public static final class Builder {
        private Path dbPath = Paths.get("./data/lsm_db");
        private long memTableThresholdBytes = 4 * 1024 * 1024L; // 4 MB
        private int targetBlockSize = 4096; // 4 KB
        private double bloomFpp = 0.01; // %1 False Positive Rate
        private int blockCacheCapacity = 1024; // 1024 blok
        private int l0CompactionThreshold = 4; // 4 SSTable L0'da birikince L1'e taşınır
        private long l1BaseBytes = 10 * 1024 * 1024L; // 10 MB
        private int levelSizeMultiplier = 10; // Her seviye 10 kat büyür
        private long sstableTargetSizeBytes = 2 * 1024 * 1024L; // 2 MB SSTable hedef boyutu
        private boolean syncWalOnWrite = false;

        public Builder dbPath(Path dbPath) {
            this.dbPath = dbPath;
            return this;
        }

        public Builder memTableThresholdBytes(long bytes) {
            this.memTableThresholdBytes = bytes;
            return this;
        }

        public Builder targetBlockSize(int bytes) {
            this.targetBlockSize = bytes;
            return this;
        }

        public Builder bloomFpp(double fpp) {
            this.bloomFpp = fpp;
            return this;
        }

        public Builder blockCacheCapacity(int capacity) {
            this.blockCacheCapacity = capacity;
            return this;
        }

        public Builder l0CompactionThreshold(int threshold) {
            this.l0CompactionThreshold = threshold;
            return this;
        }

        public Builder l1BaseBytes(long bytes) {
            this.l1BaseBytes = bytes;
            return this;
        }

        public Builder levelSizeMultiplier(int multiplier) {
            this.levelSizeMultiplier = multiplier;
            return this;
        }

        public Builder sstableTargetSizeBytes(long bytes) {
            this.sstableTargetSizeBytes = bytes;
            return this;
        }

        public Builder syncWalOnWrite(boolean sync) {
            this.syncWalOnWrite = sync;
            return this;
        }

        public LsmConfig build() {
            return new LsmConfig(
                    dbPath,
                    memTableThresholdBytes,
                    targetBlockSize,
                    bloomFpp,
                    blockCacheCapacity,
                    l0CompactionThreshold,
                    l1BaseBytes,
                    levelSizeMultiplier,
                    sstableTargetSizeBytes,
                    syncWalOnWrite
            );
        }
    }
}
