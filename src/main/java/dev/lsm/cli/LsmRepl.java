package dev.lsm.cli;

import dev.lsm.engine.EngineStats;
import dev.lsm.engine.LsmConfig;
import dev.lsm.engine.LsmEngine;
import dev.lsm.model.ByteUtils;
import dev.lsm.model.Entry;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Iterator;
import java.util.Optional;

/**
 * LSM-Tree Depolama Motoru İnteraktif Komut Satırı Arayüzü (REPL).
 */
public final class LsmRepl {

    private static final String RESET = "\u001B[0m";
    private static final String CYAN = "\u001B[36m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String BOLD = "\u001B[1m";

    public static void main(String[] args) {
        Path dbPath = Paths.get("./data/repl_db");
        if (args.length > 0) {
            dbPath = Paths.get(args[0]);
        }

        printBanner();

        LsmConfig config = LsmConfig.builder()
                .dbPath(dbPath)
                .memTableThresholdBytes(2 * 1024 * 1024L) // 2 MB
                .targetBlockSize(4096)
                .bloomFpp(0.01)
                .build();

        try (LsmEngine engine = LsmEngine.open(config);
             BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {

            System.out.println(GREEN + "Veritabanı dizini bağlandı: " + dbPath.toAbsolutePath() + RESET);
            System.out.println("Komut listesi için " + YELLOW + "help" + RESET + " yazabilirsiniz.\n");

            while (true) {
                System.out.print(CYAN + BOLD + "lsm-kv> " + RESET);
                String line = reader.readLine();
                if (line == null) break;

                line = line.trim();
                if (line.isEmpty()) continue;

                String[] parts = line.split("\\s+");
                String command = parts[0].toLowerCase();

                if (command.equals("exit") || command.equals("quit")) {
                    System.out.println("LSM Motoru güvenle kapatılıyor...");
                    break;
                }

                try {
                    switch (command) {
                        case "put" -> handlePut(engine, parts);
                        case "get" -> handleGet(engine, parts);
                        case "del", "delete" -> handleDelete(engine, parts);
                        case "scan" -> handleScan(engine, parts);
                        case "flush" -> {
                            long start = System.nanoTime();
                            engine.flush();
                            long elapsed = (System.nanoTime() - start) / 1_000_000;
                            System.out.println(GREEN + "✓ MemTable diske (SSTable L0) boşaltıldı (" + elapsed + " ms)" + RESET);
                        }
                        case "compact" -> {
                            long start = System.nanoTime();
                            engine.compact();
                            long elapsed = (System.nanoTime() - start) / 1_000_000;
                            System.out.println(GREEN + "✓ Seviyeli birleştirme (Leveled Compaction) tamamlandı (" + elapsed + " ms)" + RESET);
                        }
                        case "stats" -> {
                            EngineStats stats = engine.getStats();
                            System.out.println(CYAN + stats.toString() + RESET);
                        }
                        case "bench" -> {
                            int count = parts.length > 1 ? Integer.parseInt(parts[1]) : 10_000;
                            runMiniBench(engine, count);
                        }
                        case "help" -> printHelp();
                        default -> System.out.println(RED + "Bilinmeyen komut: '" + command + "'. 'help' yazarak komutları görebilirsiniz." + RESET);
                    }
                } catch (Exception e) {
                    System.out.println(RED + "Hata: " + e.getMessage() + RESET);
                }
            }
        } catch (Exception e) {
            System.err.println(RED + "Motor başlatılamadı: " + e.getMessage() + RESET);
            e.printStackTrace();
        }
    }

    private static void handlePut(LsmEngine engine, String[] parts) throws Exception {
        if (parts.length < 3) {
            System.out.println(YELLOW + "Kullanım: put <anahtar> <değer>" + RESET);
            return;
        }
        String key = parts[1];
        String val = parts[2];
        engine.put(key, val);
        System.out.println(GREEN + "OK: " + key + " kaydedildi." + RESET);
    }

    private static void handleGet(LsmEngine engine, String[] parts) throws Exception {
        if (parts.length < 2) {
            System.out.println(YELLOW + "Kullanım: get <anahtar>" + RESET);
            return;
        }
        String key = parts[1];
        Optional<String> val = engine.get(key);
        if (val.isPresent()) {
            System.out.println(GREEN + val.get() + RESET);
        } else {
            System.out.println(YELLOW + "(nil - anahtar bulunamadı)" + RESET);
        }
    }

    private static void handleDelete(LsmEngine engine, String[] parts) throws Exception {
        if (parts.length < 2) {
            System.out.println(YELLOW + "Kullanım: del <anahtar>" + RESET);
            return;
        }
        String key = parts[1];
        engine.delete(key);
        System.out.println(GREEN + "OK: " + key + " silindi (tombstone yazıldı)." + RESET);
    }

    private static void handleScan(LsmEngine engine, String[] parts) throws Exception {
        byte[] startKey = parts.length > 1 && !parts[1].equals("-") ? ByteUtils.toBytes(parts[1]) : null;
        byte[] endKey = parts.length > 2 && !parts[2].equals("-") ? ByteUtils.toBytes(parts[2]) : null;

        Iterator<Entry> it = engine.scan(startKey, endKey);
        int count = 0;
        System.out.println(CYAN + "--- Tarama Sonuçları ---" + RESET);
        while (it.hasNext() && count < 50) {
            Entry e = it.next();
            System.out.println("  " + e.keyAsString() + " -> " + e.valueAsString());
            count++;
        }
        if (it.hasNext()) {
            System.out.println(YELLOW + "  ... ve daha fazlası (50 kayıt ile sınırlandırıldı)" + RESET);
        }
        System.out.println(CYAN + "Toplam listelenen: " + count + " kayıt" + RESET);
    }

    private static void runMiniBench(LsmEngine engine, int count) throws Exception {
        System.out.println(CYAN + "Hızlı benchmark başlatılıyor (" + count + " işlem)..." + RESET);

        long startWrite = System.nanoTime();
        for (int i = 0; i < count; i++) {
            engine.put("key_" + i, "val_" + i);
        }
        long writeTimeMs = (System.nanoTime() - startWrite) / 1_000_000;
        double writeOps = (count / (writeTimeMs / 1000.0));
        System.out.printf(GREEN + "✓ %d PUT tamamlandı: %d ms (%.0f ops/sn)%n" + RESET, count, writeTimeMs, writeOps);

        long startRead = System.nanoTime();
        int found = 0;
        for (int i = 0; i < count; i++) {
            if (engine.get("key_" + i).isPresent()) {
                found++;
            }
        }
        long readTimeMs = (System.nanoTime() - startRead) / 1_000_000;
        double readOps = (count / (readTimeMs / 1000.0));
        System.out.printf(GREEN + "✓ %d GET tamamlandı (Bulunan: %d): %d ms (%.0f ops/sn)%n" + RESET, count, found, readTimeMs, readOps);
    }

    private static void printBanner() {
        System.out.println(CYAN + BOLD + """
                 _     ____  __  __     _____                    
                | |   / ___||  \\/  |   |_   _| __ ___  ___       
                | |   \\___ \\| |\\/| |_____| || '__/ _ \\/ _ \\      
                | |___ ___) | |  | |_____| || | |  __/  __/      
                |_____|____/|_|  |_|     |_||_|  \\___|\\___|      
                   High-Performance Pure Java 21 Storage Engine  
                """ + RESET);
    }

    private static void printHelp() {
        System.out.println(BOLD + "Kullanılabilir Komutlar:" + RESET);
        System.out.println("  " + GREEN + "put <anahtar> <değer>" + RESET + "       : Yeni kayıt ekler veya günceller");
        System.out.println("  " + GREEN + "get <anahtar>" + RESET + "               : Anahtarın değerini getirir");
        System.out.println("  " + GREEN + "del <anahtar>" + RESET + "               : Anahtarı siler (tombstone)");
        System.out.println("  " + GREEN + "scan [başlangıç] [bitiş]" + RESET + "    : Sıralı aralık taraması yapar");
        System.out.println("  " + GREEN + "flush" + RESET + "                      : MemTable'ı diske (SSTable) yazar");
        System.out.println("  " + GREEN + "compact" + RESET + "                    : Seviyeli birleştirmeyi tetikler");
        System.out.println("  " + GREEN + "stats" + RESET + "                      : Motor istatistiklerini listeler");
        System.out.println("  " + GREEN + "bench [adet]" + RESET + "                : Hızlı performans ölçümü yapar");
        System.out.println("  " + GREEN + "exit / quit" + RESET + "                 : Veritabanını güvenle kapatıp çıkar");
    }
}
