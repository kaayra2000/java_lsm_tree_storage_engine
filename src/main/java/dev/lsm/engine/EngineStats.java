package dev.lsm.engine;

/**
 * LSM-Tree motoru çalışma anı istatistikleri ve performans metrikleri.
 */
public record EngineStats(
        long totalPuts,
        long totalGets,
        long totalDeletes,
        long memTableHits,
        long immutableMemTableHits,
        long sstableHits,
        long bloomFilterNegativeBypasses,
        long cacheHits,
        long cacheMisses,
        double cacheHitRate,
        long activeMemTableBytes,
        long activeMemTableRecords,
        int totalSSTables,
        long totalSSTableBytes
) {
    @Override
    public String toString() {
        return String.format("""
                === LSM Engine İstatistikleri ===
                Yazma (PUT): %d | Okuma (GET): %d | Silme (DEL): %d
                MemTable İsabeti: %d | Dondurulmuş MemTable: %d | SSTable İsabeti: %d
                Bloom Filter Pas Geçme (I/O Tasarrufu): %d
                Blok Önbellek: İsabet: %d | Iskalama: %d | Oran: %.2f%%
                Aktif MemTable: %d bayt (~%.2f KB), %d kayıt
                SSTable Toplam: %d dosya, %d bayt (~%.2f MB)
                =================================""",
                totalPuts, totalGets, totalDeletes,
                memTableHits, immutableMemTableHits, sstableHits,
                bloomFilterNegativeBypasses,
                cacheHits, cacheMisses, cacheHitRate * 100.0,
                activeMemTableBytes, activeMemTableBytes / 1024.0, activeMemTableRecords,
                totalSSTables, totalSSTableBytes, totalSSTableBytes / (1024.0 * 1024.0)
        );
    }
}
