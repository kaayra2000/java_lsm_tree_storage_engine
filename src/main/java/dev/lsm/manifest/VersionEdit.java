package dev.lsm.manifest;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Bir veritabanı sürüm geçişini (SSTable ekleme/silme, sıra no güncelleme) tanımlayan delta paketi.
 */
public final class VersionEdit {

    public record DeletedFile(int level, long sstableId) {}

    private final List<FileMetaData> addedFiles = new ArrayList<>();
    private final List<DeletedFile> deletedFiles = new ArrayList<>();
    private Long nextSstableId = null;
    private Long lastSeqNum = null;

    public VersionEdit() {}

    public void addFile(FileMetaData meta) {
        addedFiles.add(meta);
    }

    public void deleteFile(int level, long sstableId) {
        deletedFiles.add(new DeletedFile(level, sstableId));
    }

    public void setNextSstableId(long nextSstableId) {
        this.nextSstableId = nextSstableId;
    }

    public void setLastSeqNum(long lastSeqNum) {
        this.lastSeqNum = lastSeqNum;
    }

    public List<FileMetaData> getAddedFiles() {
        return addedFiles;
    }

    public List<DeletedFile> getDeletedFiles() {
        return deletedFiles;
    }

    public Long getNextSstableId() {
        return nextSstableId;
    }

    public Long getLastSeqNum() {
        return lastSeqNum;
    }

    public byte[] toBytes() {
        int estimatedSize = 32 + (addedFiles.size() * 128) + (deletedFiles.size() * 16);
        ByteBuffer buf = ByteBuffer.allocate(estimatedSize);

        buf.putLong(nextSstableId != null ? nextSstableId : -1L);
        buf.putLong(lastSeqNum != null ? lastSeqNum : -1L);

        // Added files
        buf.putInt(addedFiles.size());
        for (FileMetaData f : addedFiles) {
            buf.putLong(f.sstableId());
            buf.putInt(f.level());
            buf.putLong(f.fileSize());
            buf.putLong(f.minSeqNum());
            buf.putLong(f.maxSeqNum());
            buf.putInt(f.firstKey().length);
            buf.put(f.firstKey());
            buf.putInt(f.lastKey().length);
            buf.put(f.lastKey());
        }

        // Deleted files
        buf.putInt(deletedFiles.size());
        for (DeletedFile d : deletedFiles) {
            buf.putInt(d.level());
            buf.putLong(d.sstableId());
        }

        buf.flip();
        byte[] bytes = new byte[buf.remaining()];
        buf.get(bytes);
        return bytes;
    }

    public static VersionEdit fromBytes(ByteBuffer buf) {
        VersionEdit edit = new VersionEdit();
        long nextId = buf.getLong();
        if (nextId != -1L) edit.setNextSstableId(nextId);

        long lastSeq = buf.getLong();
        if (lastSeq != -1L) edit.setLastSeqNum(lastSeq);

        int addedCount = buf.getInt();
        for (int i = 0; i < addedCount; i++) {
            long sstableId = buf.getLong();
            int level = buf.getInt();
            long fileSize = buf.getLong();
            long minSeq = buf.getLong();
            long maxSeq = buf.getLong();

            int fkLen = buf.getInt();
            byte[] fk = new byte[fkLen];
            buf.get(fk);

            int lkLen = buf.getInt();
            byte[] lk = new byte[lkLen];
            buf.get(lk);

            edit.addFile(new FileMetaData(sstableId, level, fileSize, fk, lk, minSeq, maxSeq));
        }

        int deletedCount = buf.getInt();
        for (int i = 0; i < deletedCount; i++) {
            int level = buf.getInt();
            long sstableId = buf.getLong();
            edit.deleteFile(level, sstableId);
        }

        return edit;
    }
}
