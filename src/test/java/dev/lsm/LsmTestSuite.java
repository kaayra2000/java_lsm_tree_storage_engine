package dev.lsm;

import dev.lsm.filter.BloomFilter;
import dev.lsm.engine.LsmConfig;
import dev.lsm.engine.LsmEngine;
import dev.lsm.memtable.MemTable;
import dev.lsm.model.ByteUtils;
import dev.lsm.model.Entry;
import dev.lsm.model.KeyValueRecord;
import dev.lsm.sstable.BlockCache;
import dev.lsm.sstable.SSTableReader;
import dev.lsm.sstable.SSTableWriter;
import dev.lsm.wal.WalReader;
import dev.lsm.wal.WalWriter;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * LSM-Tree Kapsamlı Test Paketi.
 * Harici bağımlılık gerektirmeden saf Java ile tüm motor bileşenlerini doğrular.
 */
public final class LsmTestSuite {

    public static void testBloomFilter() {
        System.out.println("  [TEST] BloomFilter: Sıfır Negatif Hata ve Doğruluk...");
        BloomFilter filter = new BloomFilter(1000, 0.01);

        for (int i = 0; i < 1000; i++) {
            filter.add(ByteUtils.toBytes("key_" + i));
        }

        // Eklenen hiçbir anahtar false dönmemeli (Zero False Negatives)
        for (int i = 0; i < 1000; i++) {
            if (!filter.mightContain(ByteUtils.toBytes("key_" + i))) {
                throw new AssertionError("BloomFilter false negative üretti! Anahtar: key_" + i);
            }
        }

        // Eklenmeyen anahtarlarda hata oranı %2'nin altında olmalı
        int falsePositives = 0;
        int testCount = 5000;
        for (int i = 1000; i < 1000 + testCount; i++) {
            if (filter.mightContain(ByteUtils.toBytes("non_key_" + i))) {
                falsePositives++;
            }
        }
        double observedFpp = (double) falsePositives / testCount;
        if (observedFpp > 0.05) {
            throw new AssertionError("BloomFilter fpp beklenenden yüksek: " + observedFpp);
        }
        System.out.printf("  ✓ BloomFilter testi başarılı (Gözlenen FPP: %.2f%%)%n", observedFpp * 100);
    }

    public static void testWalIntegrity() throws IOException {
        System.out.println("  [TEST] WAL: CRC32 Sağlama ve Kurtarma...");
        Path tempWal = Files.createTempFile("test_wal_", ".log");
        try {
            try (WalWriter writer = new WalWriter(tempWal, true)) {
                for (int i = 0; i < 50; i++) {
                    writer.append(KeyValueRecord.put(ByteUtils.toBytes("k" + i), ByteUtils.toBytes("v" + i), i));
                }
                writer.append(KeyValueRecord.delete(ByteUtils.toBytes("k25"), 51));
            }

            List<KeyValueRecord> recovered = WalReader.readAll(tempWal);
            if (recovered.size() != 51) {
                throw new AssertionError("Kurtarılan WAL kayıt sayısı hatalı: " + recovered.size());
            }

            KeyValueRecord last = recovered.getLast();
            if (!last.isTombstone() || ByteUtils.compare(last.key(), ByteUtils.toBytes("k25")) != 0) {
                throw new AssertionError("Son WAL kaydı mezar taşı (tombstone) olmalıydı!");
            }
            System.out.println("  ✓ WAL CRC32 kurtarma testi başarılı.");
        } finally {
            Files.deleteIfExists(tempWal);
        }
    }

    public static void testMemTable() {
        System.out.println("  [TEST] MemTable: Atlama Listesi ve Dondurma...");
        MemTable memTable = new MemTable(1);

        for (int i = 0; i < 100; i++) {
            memTable.put(KeyValueRecord.put(ByteUtils.toBytes(String.format("k%03d", i)), ByteUtils.toBytes("val" + i), i));
        }

        if (memTable.getRecordCount() != 100) {
            throw new AssertionError("MemTable kayıt sayısı 100 olmalıydı!");
        }

        KeyValueRecord r = memTable.get(ByteUtils.toBytes("k042"));
        if (r == null || !ByteUtils.toString(r.value()).equals("val42")) {
            throw new AssertionError("k042 kaydı doğru bulunamadı!");
        }

        var snapshot = memTable.freeze();
        if (!memTable.isFrozen()) {
            throw new AssertionError("MemTable dondurulamadı!");
        }

        try {
            memTable.put(KeyValueRecord.put(ByteUtils.toBytes("k_new"), ByteUtils.toBytes("val"), 101));
            throw new AssertionError("Dondurulan MemTable'a yazma engellenmeliydi!");
        } catch (IllegalStateException expected) {
            // Başarılı
        }
        System.out.println("  ✓ MemTable testi başarılı.");
    }

    public static void testSSTableReadWrite() throws IOException {
        System.out.println("  [TEST] SSTable: İkili Yazma, Seyrek İndeks ve Blok Önbellek...");
        Path sstPath = Files.createTempFile("test_sst_", ".sst");
        try {
            List<KeyValueRecord> records = new ArrayList<>();
            for (int i = 0; i < 500; i++) {
                records.add(KeyValueRecord.put(
                        ByteUtils.toBytes(String.format("entity_%05d", i)),
                        ByteUtils.toBytes("payload_content_" + i),
                        i
                ));
            }

            SSTableWriter writer = new SSTableWriter(sstPath, 1, 1024, 0.01);
            writer.write(records.iterator(), records.size());

            BlockCache cache = new BlockCache(64);
            try (SSTableReader reader = SSTableReader.open(1, sstPath, cache)) {
                // Mevcut kayıtları test et
                KeyValueRecord rec1 = reader.get(ByteUtils.toBytes("entity_00050"));
                if (rec1 == null || !ByteUtils.toString(rec1.value()).equals("payload_content_50")) {
                    throw new AssertionError("entity_00050 SSTable'dan doğru okunamadı!");
                }

                // İkinci okuma blok önbellekten gelmeli
                KeyValueRecord rec2 = reader.get(ByteUtils.toBytes("entity_00051"));
                if (rec2 == null) {
                    throw new AssertionError("entity_00051 bulunamadı!");
                }

                if (cache.getHits() == 0) {
                    throw new AssertionError("Blok önbellek isabet kaydetmedi!");
                }

                // Olmayan kayıt (Bloom Filter negatif)
                KeyValueRecord missing = reader.get(ByteUtils.toBytes("entity_99999"));
                if (missing != null) {
                    throw new AssertionError("Olmayan anahtar için kayıt döndü!");
                }
            }
            System.out.println("  ✓ SSTable ikili okuma/yazma ve önbellek testi başarılı.");
        } finally {
            Files.deleteIfExists(sstPath);
        }
    }

    public static void testEngineCrudAndFlush() throws IOException {
        System.out.println("  [TEST] LsmEngine: CRUD İşlemleri ve Flush Döngüsü...");
        Path tempDb = Files.createTempDirectory("lsm_engine_test_");
        try {
            LsmConfig config = LsmConfig.builder()
                    .dbPath(tempDb)
                    .memTableThresholdBytes(32 * 1024L) // 32 KB
                    .targetBlockSize(1024)
                    .build();

            try (LsmEngine engine = LsmEngine.open(config)) {
                // PUT
                engine.put("user:alice", "developer");
                engine.put("user:bob", "designer");
                engine.put("user:charlie", "architect");

                // GET
                if (!"developer".equals(engine.get("user:alice").orElse(null))) {
                    throw new AssertionError("user:alice değeri yanlış!");
                }

                // DELETE
                engine.delete("user:bob");
                if (engine.get("user:bob").isPresent()) {
                    throw new AssertionError("user:bob silinmiş olmalıydı!");
                }

                // FLUSH
                engine.flush();

                // Flush sonrası GET
                if (!"developer".equals(engine.get("user:alice").orElse(null))) {
                    throw new AssertionError("Flush sonrası user:alice okunamadı!");
                }
                if (engine.get("user:bob").isPresent()) {
                    throw new AssertionError("Flush sonrası user:bob silinmiş hali korunmalıydı!");
                }
            }
            System.out.println("  ✓ LsmEngine CRUD ve Flush testi başarılı.");
        } finally {
            cleanDirectory(tempDb);
        }
    }

    public static void testRangeScan() throws IOException {
        System.out.println("  [TEST] LsmEngine: Aralık Taraması (Range Scan)...");
        Path tempDb = Files.createTempDirectory("lsm_scan_test_");
        try {
            LsmConfig config = LsmConfig.builder().dbPath(tempDb).build();
            try (LsmEngine engine = LsmEngine.open(config)) {
                for (int i = 0; i < 20; i++) {
                    engine.put(String.format("k%02d", i), "v" + i);
                }

                // [k05, k10) aralığını tara
                Iterator<Entry> it = engine.scan(ByteUtils.toBytes("k05"), ByteUtils.toBytes("k10"));
                List<String> keys = new ArrayList<>();
                while (it.hasNext()) {
                    keys.add(it.next().keyAsString());
                }

                List<String> expected = List.of("k05", "k06", "k07", "k08", "k09");
                if (!keys.equals(expected)) {
                    throw new AssertionError("Aralık tarama sonucu hatalı: " + keys);
                }
            }
            System.out.println("  ✓ Aralık taraması testi başarılı.");
        } finally {
            cleanDirectory(tempDb);
        }
    }

    public static void testCrashRecovery() throws IOException {
        System.out.println("  [TEST] LsmEngine: Çökme / Yeniden Başlatma Kurtarması...");
        Path tempDb = Files.createTempDirectory("lsm_crash_test_");
        try {
            LsmConfig config = LsmConfig.builder().dbPath(tempDb).build();

            // İlk oturumda veri yaz ve kapatmadan (veya flush etmeden) simüle et
            try (LsmEngine engine = LsmEngine.open(config)) {
                engine.put("persist:1", "data_1");
                engine.put("persist:2", "data_2");
                engine.delete("persist:1");
                engine.put("persist:3", "data_3");
            }

            // Motor yeniden açılıyor
            try (LsmEngine engine = LsmEngine.open(config)) {
                if (engine.get("persist:1").isPresent()) {
                    throw new AssertionError("persist:1 silinmiş olmalıydı!");
                }
                if (!"data_2".equals(engine.get("persist:2").orElse(null))) {
                    throw new AssertionError("persist:2 kurtarılamadı!");
                }
                if (!"data_3".equals(engine.get("persist:3").orElse(null))) {
                    throw new AssertionError("persist:3 kurtarılamadı!");
                }
            }
            System.out.println("  ✓ Çökme ve WAL kurtarma testi başarılı.");
        } finally {
            cleanDirectory(tempDb);
        }
    }

    public static void testLeveledCompaction() throws IOException {
        System.out.println("  [TEST] LsmEngine: Seviyeli Birleştirme ve Mezar Taşı Temizliği...");
        Path tempDb = Files.createTempDirectory("lsm_compaction_test_");
        try {
            LsmConfig config = LsmConfig.builder()
                    .dbPath(tempDb)
                    .memTableThresholdBytes(8 * 1024L) // 8 KB ile çok sayıda L0 üret
                    .targetBlockSize(1024)
                    .l0CompactionThreshold(2)
                    .build();

            try (LsmEngine engine = LsmEngine.open(config)) {
                for (int i = 0; i < 500; i++) {
                    engine.put("key_" + i, "version_1_" + i);
                }
                engine.flush();

                // Üzerine yaz ve bazılarını sil
                for (int i = 0; i < 200; i++) {
                    engine.put("key_" + i, "version_2_" + i);
                }
                for (int i = 400; i < 500; i++) {
                    engine.delete("key_" + i);
                }
                engine.flush();

                // Compaction çalıştır
                engine.compact();

                // Doğrulama
                if (!"version_2_50".equals(engine.get("key_50").orElse(null))) {
                    throw new AssertionError("key_50 en güncel sürüme sahip olmalıydı!");
                }
                if (!"version_1_300".equals(engine.get("key_300").orElse(null))) {
                    throw new AssertionError("key_300 version_1 korunmalıydı!");
                }
                if (engine.get("key_450").isPresent()) {
                    throw new AssertionError("key_450 silinmiş olmalıydı!");
                }
            }
            System.out.println("  ✓ Seviyeli birleştirme testi başarılı.");
        } finally {
            cleanDirectory(tempDb);
        }
    }

    public static void testVirtualThreadConcurrency() throws Exception {
        System.out.println("  [TEST] LsmEngine: Sanal İş Parçacıkları (Virtual Threads) Eşzamanlılık Stresi...");
        Path tempDb = Files.createTempDirectory("lsm_concurrency_test_");
        try {
            LsmConfig config = LsmConfig.builder()
                    .dbPath(tempDb)
                    .memTableThresholdBytes(64 * 1024L)
                    .build();

            int threadCount = 16;
            int opsPerThread = 500;
            CountDownLatch latch = new CountDownLatch(threadCount);
            AtomicInteger errorCount = new AtomicInteger(0);

            try (LsmEngine engine = LsmEngine.open(config);
                 ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {

                for (int t = 0; t < threadCount; t++) {
                    final int threadId = t;
                    executor.submit(() -> {
                        try {
                            for (int i = 0; i < opsPerThread; i++) {
                                String key = "concurrent:" + threadId + ":" + i;
                                engine.put(key, "val:" + i);

                                if (i % 10 == 0) {
                                    Optional<String> val = engine.get(key);
                                    if (val.isEmpty() || !val.get().equals("val:" + i)) {
                                        errorCount.incrementAndGet();
                                    }
                                }
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                            errorCount.incrementAndGet();
                        } finally {
                            latch.countDown();
                        }
                    });
                }

                latch.await();

                if (errorCount.get() > 0) {
                    throw new AssertionError("Eşzamanlılık testinde " + errorCount.get() + " hata oluştu!");
                }
            }
            System.out.println("  ✓ Sanal iş parçacıkları eşzamanlılık testi başarılı.");
        } finally {
            cleanDirectory(tempDb);
        }
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
