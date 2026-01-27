package dev.lsm.sstable;

import dev.lsm.filter.BloomFilter;
import dev.lsm.model.ByteUtils;
import dev.lsm.model.KeyValueRecord;
import dev.lsm.model.RecordType;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

/**
 * SSTable dosya okuyucusu.
 * Dosya açıldığında Footer, Sparse Index ve Bloom Filter bloklarını belleğe yükler.
 * Bloom Filter sayesinde gereksiz disk okumalarını önler, seyrek indeks ve LRU blok önbelleği
 * ile alt-milisaniye rastgele okuma gecikmesi sağlar.
 */
public final class SSTableReader implements AutoCloseable {

    private final long sstableId;
    private final Path filePath;
    private final Footer footer;
    private final List<BlockMeta> indexBlock;
    private final BloomFilter bloomFilter;
    private final BlockCache blockCache;
    private final byte[] firstKey;
    private final byte[] lastKey;
    private final FileChannel channel;

    private SSTableReader(long sstableId, Path filePath, Footer footer,
                          List<BlockMeta> indexBlock, BloomFilter bloomFilter,
                          BlockCache blockCache, FileChannel channel) {
        this.sstableId = sstableId;
        this.filePath = filePath;
        this.footer = footer;
        this.indexBlock = indexBlock;
        this.bloomFilter = bloomFilter;
        this.blockCache = blockCache;
        this.channel = channel;

        if (!indexBlock.isEmpty()) {
            this.firstKey = indexBlock.getFirst().firstKey();
            this.lastKey = indexBlock.getLast().lastKey();
        } else {
            this.firstKey = null;
            this.lastKey = null;
        }
    }

    /**
     * SSTable dosyasını açar ve meta/indeks bloklarını yükler.
     */
    public static SSTableReader open(long sstableId, Path filePath, BlockCache blockCache) throws IOException {
        long fileSize = Files.size(filePath);
        if (fileSize < Footer.FOOTER_SIZE) {
            throw new IOException("Dosya boyutu Footer için çok küçük: " + filePath);
        }

        FileChannel channel = FileChannel.open(filePath, StandardOpenOption.READ);

        // 1. Footer Oku
        ByteBuffer footerBuf = ByteBuffer.allocate(Footer.FOOTER_SIZE);
        channel.position(fileSize - Footer.FOOTER_SIZE);
        readFully(channel, footerBuf);
        footerBuf.flip();
        Footer footer = Footer.readFrom(footerBuf);

        // 2. Index Bloğunu Oku
        ByteBuffer indexBuf = ByteBuffer.allocate(footer.indexLength());
        channel.position(footer.indexOffset());
        readFully(channel, indexBuf);
        indexBuf.flip();
        int blockCount = indexBuf.getInt();
        List<BlockMeta> indexList = new ArrayList<>(blockCount);
        for (int i = 0; i < blockCount; i++) {
            indexList.add(BlockMeta.readFrom(indexBuf));
        }

        // 3. Bloom Filtresi Bloğunu Oku
        ByteBuffer filterBuf = ByteBuffer.allocate(footer.filterLength());
        channel.position(footer.filterOffset());
        readFully(channel, filterBuf);
        filterBuf.flip();
        BloomFilter bloomFilter = BloomFilter.readFrom(filterBuf);

        return new SSTableReader(sstableId, filePath, footer, indexList, bloomFilter, blockCache, channel);
    }

    /**
     * Tek bir anahtar için $O(\log M)$ seyrek indeks + ikili arama yapar.
     * Bloom Filter negatif ise disk G/Ç yapmadan null döner.
     */
    public KeyValueRecord get(byte[] key) throws IOException {
        if (key == null || indexBlock.isEmpty()) {
            return null;
        }

        // 1. Aralık dışı kontrolü
        if (ByteUtils.compare(key, firstKey) < 0 || ByteUtils.compare(key, lastKey) > 0) {
            return null;
        }

        // 2. Bloom Filter $O(1)$ Negatif Filtreleme
        if (!bloomFilter.mightContain(key)) {
            return null;
        }

        // 3. Seyrek İndeks üzerinde İkili Arama (Binary Search)
        int candidateBlockIdx = findCandidateBlockIndex(key);
        if (candidateBlockIdx < 0 || candidateBlockIdx >= indexBlock.size()) {
            return null;
        }

        BlockMeta blockMeta = indexBlock.get(candidateBlockIdx);

        // 4. Blok Önbellekten veya Diskten Bloğu Yükle
        List<KeyValueRecord> records = loadBlock(blockMeta);

        // 5. Blok içi arama
        return searchInBlock(records, key);
    }

    /**
     * Verilen anahtarı kapsayabilecek blok indeksini ikili aramayla bulur.
     */
    private int findCandidateBlockIndex(byte[] key) {
        int low = 0;
        int high = indexBlock.size() - 1;
        int result = -1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            BlockMeta meta = indexBlock.get(mid);
            int cmp = ByteUtils.compare(key, meta.firstKey());

            if (cmp >= 0) {
                result = mid; // Aday blok
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }

        return result;
    }

    private List<KeyValueRecord> loadBlock(BlockMeta blockMeta) throws IOException {
        if (blockCache != null) {
            List<KeyValueRecord> cached = blockCache.get(sstableId, blockMeta.offset());
            if (cached != null) {
                return cached;
            }
        }

        ByteBuffer buffer = ByteBuffer.allocate(blockMeta.length());
        synchronized (channel) {
            channel.position(blockMeta.offset());
            readFully(channel, buffer);
        }
        buffer.flip();

        List<KeyValueRecord> records = deserializeBlock(buffer, blockMeta.recordCount());

        if (blockCache != null) {
            blockCache.put(sstableId, blockMeta.offset(), records);
        }

        return records;
    }

    private List<KeyValueRecord> deserializeBlock(ByteBuffer buffer, int recordCount) throws IOException {
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(buffer.array(), buffer.position(), buffer.remaining()));
        List<KeyValueRecord> records = new ArrayList<>(recordCount);

        for (int i = 0; i < recordCount; i++) {
            byte typeCode = dis.readByte();
            int keyLen = dis.readInt();
            byte[] key = new byte[keyLen];
            dis.readFully(key);

            int valLen = dis.readInt();
            byte[] val = new byte[valLen];
            dis.readFully(val);

            long seq = dis.readLong();
            long ts = dis.readLong();

            records.add(new KeyValueRecord(key, val, RecordType.fromCode(typeCode), seq, ts));
        }

        return records;
    }

    private KeyValueRecord searchInBlock(List<KeyValueRecord> records, byte[] key) {
        int low = 0;
        int high = records.size() - 1;

        while (low <= high) {
            int mid = (low + high) >>> 1;
            KeyValueRecord midVal = records.get(mid);
            int cmp = ByteUtils.compare(key, midVal.key());

            if (cmp < 0) {
                high = mid - 1;
            } else if (cmp > 0) {
                low = mid + 1;
            } else {
                return midVal; // Bulundu
            }
        }

        return null;
    }

    public Iterator<KeyValueRecord> iterator() {
        return new SSTableIterator(this);
    }

    public List<BlockMeta> getIndexBlock() {
        return Collections.unmodifiableList(indexBlock);
    }

    public BloomFilter getBloomFilter() {
        return bloomFilter;
    }

    public Footer getFooter() {
        return footer;
    }

    public long getSstableId() {
        return sstableId;
    }

    public Path getFilePath() {
        return filePath;
    }

    public byte[] getFirstKey() {
        return firstKey;
    }

    public byte[] getLastKey() {
        return lastKey;
    }

    List<KeyValueRecord> readBlockByIndex(int blockIdx) throws IOException {
        if (blockIdx < 0 || blockIdx >= indexBlock.size()) {
            return Collections.emptyList();
        }
        return loadBlock(indexBlock.get(blockIdx));
    }

    int getBlockCount() {
        return indexBlock.size();
    }

    private static void readFully(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read == -1) {
                break;
            }
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (channel.isOpen()) {
            channel.close();
        }
    }
}
