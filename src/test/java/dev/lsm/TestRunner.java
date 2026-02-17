package dev.lsm;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Harici bir test çatısına (JUnit vb.) ihtiyaç duymadan testleri çalıştıran
 * bağımsız saf Java test koşucusu.
 */
public final class TestRunner {

    private static final String RESET = "\u001B[0m";
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String CYAN = "\u001B[36m";
    private static final String BOLD = "\u001B[1m";

    public static void main(String[] args) {
        System.out.println(CYAN + BOLD + "=== LSM-Tree Storage Engine Test Paketi Çalıştırılıyor ===" + RESET);
        long startTime = System.currentTimeMillis();

        List<String> passed = new ArrayList<>();
        List<String> failed = new ArrayList<>();

        Method[] methods = LsmTestSuite.class.getDeclaredMethods();
        for (Method method : methods) {
            if (method.getName().startsWith("test")) {
                String testName = method.getName();
                try {
                    method.invoke(null);
                    passed.add(testName);
                } catch (Throwable t) {
                    failed.add(testName);
                    System.err.println(RED + "  ✗ TEST BAŞARISIZ: " + testName + RESET);
                    Throwable cause = t.getCause() != null ? t.getCause() : t;
                    cause.printStackTrace();
                }
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        System.out.println("\n---------------------------------------------------------");
        System.out.printf("Toplam Test: %d | Başarılı: " + GREEN + "%d" + RESET + " | Başarısız: " + RED + "%d" + RESET + " (%d ms)%n",
                (passed.size() + failed.size()), passed.size(), failed.size(), elapsed);
        System.out.println("---------------------------------------------------------");

        if (!failed.isEmpty()) {
            System.err.println(RED + "Bazı testler başarısız oldu!" + RESET);
            System.exit(1);
        } else {
            System.out.println(GREEN + BOLD + "Tüm testler başarıyla geçti! ✓" + RESET);
        }
    }
}
