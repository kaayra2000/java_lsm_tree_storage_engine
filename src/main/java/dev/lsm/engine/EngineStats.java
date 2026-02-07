package dev.lsm.engine;

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
) {}
