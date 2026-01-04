package dev.lsm.model;

/**
 * LSM-Tree kayıt tipini temsil eden numaralandırma.
 * PUT: Yeni veri ekleme veya güncelleme.
 * DELETE: Silme işareti (tombstone).
 */
public enum RecordType {
    PUT((byte) 1),
    DELETE((byte) 2);

    private final byte code;

    RecordType(byte code) {
        this.code = code;
    }

    public byte getCode() {
        return code;
    }

    public static RecordType fromCode(byte code) {
        return switch (code) {
            case 1 -> PUT;
            case 2 -> DELETE;
            default -> throw new IllegalArgumentException("Bilinmeyen kayıt tipi kodu: " + code);
        };
    }
}
