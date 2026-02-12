package dev.lsm.cli;

import dev.lsm.engine.EngineStats;
import dev.lsm.engine.LsmConfig;
import dev.lsm.engine.LsmEngine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Sanal İş Parçacıkları (Virtual Threads) ile çok iş parçacıklı LSM Benchmark kıyaslama aracı.
 */
public final class LsmBenchmark {

    public static void main(String[] args) throws Exception {
        int totalOperations = 50_000;
        int virtualThreads = 16;
        Path benchDir = Paths.get("./data/benchmark_db");

        // Temiz bir kıyaslama için eski dizini temizle
        cleanDirectory(benchDir);

        System.out.println("=================================================");
        System.out.println("   LSM-Tree Pure Java 21 Benchmark Testi         ");
        System.out.println("=================================================");
        System.out.printf("Toplam İşlem: %d, Eşzamanlı Sanal İş Parçacığı: %d%n", totalOperations, virtualThreads);

        LsmConfig config = LsmConfig.builder()
                .dbPath(benchDir)
                .memTableThresholdBytes(2 * 1024 * 1024L) // 2 MB
                .targetBlockSize(4096)
                .bloomFpp(0.01)
                .blockCacheCapacity(2048)
                .l0CompactionThreshold(4)
                .build();

        try (LsmEngine engine = LsmEngine.open(config)) {
            // 1. Çok İş Parçacıklı Eşzamanlı Yazma (PUT)
            System.out.println("\n[1/3] Çok İş Parçacıklı Yazma (PUT) Testi Başlatılıyor...");
            long[] writeLatencies = runConcurrentWrites(engine, totalOperations, virtualThreads);
            printLatencyReport("Eşzamanlı PUT", writeLatencies);

            // MemTable flush & Compaction
            System.out.println("\nMemTable diske boşaltılıyor ve Compaction çalıştırılıyor...");
            long flushStart = System.currentTimeMillis();
            engine.flush();
            engine.compact();
            System.out.printf("Compaction tamamlandı (%d ms)%n", (System.currentTimeMillis() - flushStart));

            // 2. Çok İş Parçacıklı Rastgele Okuma (GET - Mevcut Anahtarlar)
            System.out.println("\n[2/3] Çok İş Parçacıklı Rastgele Okuma (GET - Mevcut Anahtarlar)...");
            long[] readLatencies = runConcurrentReads(engine, totalOperations, virtualThreads, true);
            printLatencyReport("Rastgele GET (Hit)", readLatencies);

            // 3. Çok İş Parçacıklı Bloom Filtresi Negatif Okuma (GET - Olmayan Anahtarlar)
            System.out.println("\n[3/3] Bloom Filtresi Negatif Okuma Testi (Olmayan Anahtarlar)...");
            long[] negativeLatencies = runConcurrentReads(engine, totalOperations, virtualThreads, false);
            printLatencyReport("Rastgele GET (Bloom Negative)", negativeLatencies);

            // Telemetri ve İstatistikler
            System.out.println("\n" + engine.getStats());
        }

        System.out.println("\nBenchmark başarıyla tamamlandı!");
    }

    private static long[] runConcurrentWrites(LsmEngine engine, int totalOps, int threads) throws InterruptedException {
        long[] latencies = new long[totalOps];
        int opsPerThread = totalOps / threads;
        CountDownLatch latch = new CountDownLatch(threads);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long globalStart = System.nanoTime();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    int startIdx = threadId * opsPerThread;
                    int endIdx = (threadId == threads - 1) ? totalOps : startIdx + opsPerThread;

                    for (int i = startIdx; i < endIdx; i++) {
                        String key = String.format("usr_%08d", i);
                        String val = "payload_content_data_value_record_" + i;
                        long opStart = System.nanoTime();
                        try {
                            engine.put(key, val);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        latencies[i] = System.nanoTime() - opStart;
                    }
                    latch.countDown();
                });
            }

            latch.await();
            long totalNanos = System.nanoTime() - globalStart;
            double seconds = totalNanos / 1_000_000_000.0;
            System.out.printf("Toplam Süre: %.3f sn | Throughput: %.0f ops/sn%n", seconds, totalOps / seconds);
        }

        return latencies;
    }

    private static long[] runConcurrentReads(LsmEngine engine, int totalOps, int threads, boolean existing) throws InterruptedException {
        long[] latencies = new long[totalOps];
        int opsPerThread = totalOps / threads;
        CountDownLatch latch = new CountDownLatch(threads);
        Random random = new Random(42);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            long globalStart = System.nanoTime();

            for (int t = 0; t < threads; t++) {
                final int threadId = t;
                executor.submit(() -> {
                    int startIdx = threadId * opsPerThread;
                    int endIdx = (threadId == threads - 1) ? totalOps : startIdx + opsPerThread;

                    for (int i = startIdx; i < endIdx; i++) {
                        String key = existing
                                ? String.format("usr_%08d", random.nextInt(totalOps))
                                : String.format("non_existing_key_%08d", i + 1_000_000);

                        long opStart = System.nanoTime();
                        try {
                            engine.get(key);
                        } catch (IOException e) {
                            e.printStackTrace();
                        }
                        latencies[i] = System.nanoTime() - opStart;
                    }
                    latch.countDown();
                });
            }

            latch.await();
            long totalNanos = System.nanoTime() - globalStart;
            double seconds = totalNanos / 1_000_000_000.0;
            System.out.printf("Toplam Süre: %.3f sn | Throughput: %.0f ops/sn%n", seconds, totalOps / seconds);
        }

        return latencies;
    }

    private static void printLatencyReport(String title, long[] latencies) {
        Arrays.sort(latencies);
        int len = latencies.length;
        double p50 = latencies[(int) (len * 0.50)] / 1_000.0; // mikro saniye
        double p95 = latencies[(int) (len * 0.95)] / 1_000.0;
        double p99 = latencies[(int) (len * 0.99)] / 1_000.0;
        double max = latencies[len - 1] / 1_000.0;

        System.out.printf("[%s Gecikme] p50: %.2f µs | p95: %.2f µs | p99: %.2f µs | max: %.2f µs%n",
                title, p50, p95, p99, max);
    }

    private static void cleanDirectory(Path path) {
        try {
            if (Files.exists(path)) {
                try (var stream = Files.walk(path)) {
                    stream.sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {}
                    });
                }
            }
        } catch (Exception ignored) {}
    }
}
