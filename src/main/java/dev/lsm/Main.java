package dev.lsm;

import dev.lsm.cli.LsmBenchmark;
import dev.lsm.cli.LsmRepl;
import dev.lsm.engine.LsmConfig;
import dev.lsm.engine.LsmEngine;
import dev.lsm.model.ByteUtils;
import dev.lsm.model.Entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Optional;

/**
 * LSM-Tree Depolama Motoru ana giriş noktası.
 */
public final class Main {

    public static void main(String[] args) {
        if (args.length > 0 && "bench".equalsIgnoreCase(args[0])) {
            try {
                LsmBenchmark.main(args);
            } catch (Exception e) {
                e.printStackTrace();
            }
            return;
        }

        if (args.length > 0 && "demo".equalsIgnoreCase(args[0])) {
            runDemonstration();
            return;
        }

        // Varsayılan olarak REPL arayüzünü başlat
        LsmRepl.main(args);
    }

    private static void runDemonstration() {
        System.out.println("=== Java LSM-Tree Mimari Demonstrasyonu ===");
        Path demoDir = Paths.get("./data/demo_db");

        cleanDirectory(demoDir);

        LsmConfig config = LsmConfig.builder()
                .dbPath(demoDir)
                .memTableThresholdBytes(64 * 1024L) // 64 KB (Hızlı flush için)
                .targetBlockSize(2048)
                .bloomFpp(0.01)
                .build();

        try {
            System.out.println("\n1. LSM Motoru Açılıyor...");
            try (LsmEngine engine = LsmEngine.open(config)) {
                System.out.println("2. MemTable'a 1000 adet kayıt yazılıyor...");
                for (int i = 0; i < 1000; i++) {
                    engine.put("user:" + String.format("%04d", i), "data_payload_" + i);
                }

                System.out.println("3. Noktasal Sorgular (GET):");
                System.out.println("  user:0042 => " + engine.get("user:0042").orElse("YOK"));
                System.out.println("  user:0500 => " + engine.get("user:0500").orElse("YOK"));
                System.out.println("  user:9999 => " + engine.get("user:9999").orElse("YOK (Bloom Filter yakaladı)"));

                System.out.println("\n4. MemTable diske boşaltılıyor (Flush -> SSTable L0)...");
                engine.flush();

                System.out.println("5. Birkaç kayıt siliniyor (Tombstone ekleniyor)...");
                engine.delete("user:0042");
                System.out.println("  user:0042 silindi mi? => " + (engine.get("user:0042").isEmpty() ? "EVET (nil)" : "HAYIR"));

                System.out.println("\n6. Aralık Taraması (Range Scan: [user:0100, user:0105)):");
                Iterator<Entry> it = engine.scan(ByteUtils.toBytes("user:0100"), ByteUtils.toBytes("user:0105"));
                while (it.hasNext()) {
                    Entry e = it.next();
                    System.out.println("  " + e.keyAsString() + " = " + e.valueAsString());
                }

                System.out.println("\n7. Seviyeli Birleştirme (Compaction) Çalıştırılıyor...");
                engine.compact();

                System.out.println("\n8. İstatistikler:");
                System.out.println(engine.getStats());
            }

            System.out.println("\n9. Çökme / Yeniden Başlatma Kurtarma Testi (WAL Recovery)...");
            try (LsmEngine engine = LsmEngine.open(config)) {
                System.out.println("  user:0500 yeniden açıldıktan sonra okunuyor: " + engine.get("user:0500").orElse("YOK"));
                System.out.println("  user:0042 silinmiş hali korunuyor mu? " + (engine.get("user:0042").isEmpty() ? "EVET" : "HAYIR"));
            }

            System.out.println("\n✓ LSM-Tree motor demonstrasyonu başarıyla tamamlandı!");
        } catch (Exception e) {
            e.printStackTrace();
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
