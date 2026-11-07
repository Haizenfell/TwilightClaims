package com.twilight.claims.managed;

import com.twilight.claims.api.ClaimBounds;
import com.twilight.claims.api.managed.*;
import com.twilight.claims.visual.BoundaryStyles;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.DigestInputStream;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.util.*;

final class ManagedJournal implements AutoCloseable {
    private static final int MAX_ENTRY_SIZE = 4_194_304;
    private static final int CHECKPOINT_MAGIC = 0x54434D32;
    private static final int ARCHIVE_MAGIC = 0x54434131;
    private static final String CHECKPOINT = "checkpoint.bin";
    record Entry(ManagedOperationContext context, ManagedAction action, ManagedClaimState state,
                 String fingerprint, boolean confirmed) {
        Entry committed() { return new Entry(context, action, state, fingerprint, true); }
        ManagedClaimSnapshot snapshot() {
            var identity = context.identity();
            return new ManagedClaimSnapshot(identity,
                    new com.twilight.claims.api.ClaimKey(identity.worldId(), identity.regionId()),
                    context.revision(), action == ManagedAction.DELETE ? ManagedLifecycle.DELETED : ManagedLifecycle.ACTIVE, state);
        }
    }
    record Retirement(ManagedClaimIdentity identity, long throughRevision) {
        Retirement {
            Objects.requireNonNull(identity);
            if (throughRevision < 1) throw new IllegalArgumentException("Retirement revision must be positive");
        }
    }
    record Loaded(String serverId, List<Entry> entries, Map<UUID, Retirement> retirements) {
        Loaded { entries = List.copyOf(entries); retirements = Map.copyOf(retirements); }
    }
    private final Path directory;
    private FileChannel lockChannel;
    private FileLock lock;
    private FileChannel archiveChannel;
    private long archiveCount;
    private String archiveName = "";
    private String serverId;
    private final Map<UUID, Entry> entries = new HashMap<>();
    private final Map<UUID, Retirement> retirements = new HashMap<>();
    private boolean poisoned;

    ManagedJournal(Path directory) { this.directory = directory; }

    Loaded load(String configuredServerId) throws Exception {
        Files.createDirectories(directory);
        acquireLock();
        entries.clear();
        retirements.clear();
        closeArchive();
        poisoned = false;
        Path serverFile = directory.resolve("server-id");
        if (Files.exists(serverFile)) {
            serverId = new String(read(serverFile), StandardCharsets.UTF_8);
            if (!configuredServerId.isBlank() && !configuredServerId.equals(serverId))
                throw new IOException("Configured server ID differs from the durable managed journal");
        } else {
            serverId = configuredServerId.isBlank() ? UUID.randomUUID().toString() : configuredServerId;
            write(serverFile, serverId.getBytes(StandardCharsets.UTF_8));
        }
        Path checkpoint = directory.resolve(CHECKPOINT);
        if (Files.exists(checkpoint)) loadCheckpoint(checkpoint);
        try (var paths = Files.list(directory)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".entry")).toList()) {
                String name = file.getFileName().toString();
                UUID operationId;
                try { operationId = UUID.fromString(name.substring(0, name.length() - 6)); }
                catch (IllegalArgumentException failure) { throw new IOException("Invalid journal operation filename", failure); }
                if (!name.equals(operationId + ".entry")) throw new IOException("Invalid journal operation filename");
                if (isRetiredOperation(operationId)) continue;
                Entry entry = decode(new String(read(file), StandardCharsets.UTF_8));
                if (!file.getFileName().toString().equals(entry.context().operationId() + ".entry"))
                    throw new IOException("Journal operation filename mismatch");
                requireServer(entry.context().identity());
                Retirement floor = retirements.get(entry.context().identity().instanceId());
                if (floor != null && !floor.identity().equals(entry.context().identity()))
                    throw new IOException("Retired instance identity conflict");
                if (floor != null && entry.context().revision() <= floor.throughRevision()) continue;
                if (isRetiredOperation(entry.context().operationId())) continue;
                merge(entry);
            }
        }
        validateEntries();
        compact();
        return snapshot();
    }

    private Loaded snapshot() {
        List<Entry> ordered = entries.values().stream().sorted(Comparator
                .comparingLong((Entry e) -> e.context().revision())
                .thenComparing(e -> e.context().operationId())).toList();
        return new Loaded(serverId, ordered, retirements);
    }

    void save(Entry entry) throws Exception {
        checkLock();
        requireServer(entry.context().identity());
        Retirement floor = retirements.get(entry.context().identity().instanceId());
        if (floor != null && (!floor.identity().equals(entry.context().identity())
                || entry.context().revision() <= floor.throughRevision()))
            throw new IOException("Operation belongs to a retired delivery window");
        if (isRetiredOperation(entry.context().operationId()))
            throw new IOException("Operation UUID is permanently retired");
        Entry previous = entries.get(entry.context().operationId());
        if (previous != null && !previous.fingerprint().equals(entry.fingerprint()))
            throw new IOException("Operation UUID content conflict");
        if (previous != null && previous.confirmed() && !entry.confirmed()) return;
        write(directory.resolve(entry.context().operationId() + ".entry"), encode(entry).getBytes(StandardCharsets.UTF_8));
        entries.put(entry.context().operationId(), entry);
    }

    Retirement retire(ManagedClaimIdentity identity, long throughRevision) throws Exception {
        checkLock();
        requireServer(identity);
        if (throughRevision < 1) throw new IllegalArgumentException("Retirement revision must be positive");
        Retirement old = retirements.get(identity.instanceId());
        if (old != null && !old.identity().equals(identity)) throw new IOException("Retirement identity conflict");
        if (old != null && throughRevision <= old.throughRevision()) return old;
        Entry latest = null;
        for (Entry entry : entries.values()) {
            if (!entry.context().identity().instanceId().equals(identity.instanceId())) continue;
            if (!entry.context().identity().equals(identity)) throw new IOException("Instance identity conflict");
            if (!entry.confirmed()) throw new IOException("Retirement cannot overlap pending durable work");
            if (latest == null || entry.context().revision() > latest.context().revision()) latest = entry;
        }
        if (latest == null || throughRevision > latest.context().revision())
            throw new IOException("Retirement exceeds the latest confirmed revision");
        var advanced = new Retirement(identity, throughRevision);
        Map<UUID, Retirement> floors = new HashMap<>(retirements);
        floors.put(identity.instanceId(), advanced);
        checkpoint(floors);
        return advanced;
    }

    void compact() throws Exception {
        checkLock();
        checkpoint(new HashMap<>(retirements));
    }

    boolean isRetiredOperation(UUID operationId) throws Exception {
        checkLock();
        Objects.requireNonNull(operationId);
        if (archiveChannel == null) return false;
        long low = 0;
        long high = archiveCount - 1;
        ByteBuffer record = ByteBuffer.allocate(16);
        while (low <= high) {
            long middle = (low + high) >>> 1;
            record.clear();
            readFully(archiveChannel, record, 12 + middle * 16);
            record.flip();
            UUID candidate = new UUID(record.getLong(), record.getLong());
            int comparison = candidate.compareTo(operationId);
            if (comparison == 0) return true;
            if (comparison < 0) low = middle + 1;
            else high = middle - 1;
        }
        return false;
    }

    private void checkpoint(Map<UUID, Retirement> floors) throws Exception {
        Map<UUID, Entry> latest = new HashMap<>();
        for (Entry entry : entries.values()) {
            if (!entry.confirmed()) continue;
            latest.merge(entry.context().identity().instanceId(), entry, (a, b) ->
                    a.context().revision() >= b.context().revision() ? a : b);
        }
        Map<UUID, Entry> retained = new HashMap<>();
        TreeSet<UUID> newlyRetired = new TreeSet<>();
        for (Entry entry : entries.values()) {
            Retirement floor = floors.get(entry.context().identity().instanceId());
            boolean retired = entry.confirmed() && floor != null && entry.context().revision() <= floor.throughRevision();
            if (retired) {
                if (!isRetiredOperation(entry.context().operationId())) newlyRetired.add(entry.context().operationId());
                if (latest.get(entry.context().identity().instanceId()) == entry)
                    retained.put(entry.context().operationId(), entry);
            } else retained.put(entry.context().operationId(), entry);
        }
        String nextArchive = archiveName;
        if (!newlyRetired.isEmpty()) nextArchive = writeArchive(newlyRetired);
        String publishedArchive = nextArchive;
        try {
            writeStream(directory.resolve(CHECKPOINT), out -> {
                out.writeInt(CHECKPOINT_MAGIC);
                out.writeUTF(serverId);
                out.writeUTF(publishedArchive);
                out.writeInt(floors.size());
                for (var item : new TreeMap<>(floors).values()) {
                    writeIdentity(out, item.identity());
                    out.writeLong(item.throughRevision());
                }
                out.writeInt(retained.size());
                for (Entry entry : retained.values().stream().sorted(Comparator.comparing(e -> e.context().operationId())).toList()) {
                    byte[] encoded = encode(entry).getBytes(StandardCharsets.UTF_8);
                    if (encoded.length > MAX_ENTRY_SIZE - 40) throw new IOException("Oversized checkpoint record");
                    out.writeInt(encoded.length);
                    out.write(encoded);
                }
            });
        } catch (Exception failure) {
            poisoned = true;
            throw failure;
        }
        retirements.clear();
        retirements.putAll(floors);
        entries.clear();
        entries.putAll(retained);
        try {
            if (!nextArchive.equals(archiveName)) openArchive(nextArchive);
        } catch (Exception failure) {
            poisoned = true;
            throw failure;
        }
        try { cleanup(); }
        catch (IOException failure) {
            java.util.logging.Logger.getLogger("TwilightClaims").warning(
                    "Managed checkpoint is durable; obsolete file cleanup will retry on the next compaction: " + failure);
        }
    }

    private String writeArchive(SortedSet<UUID> added) throws Exception {
        String name = "archive-" + UUID.randomUUID() + ".bin";
        long priorCount = archiveCount;
        long total = Math.addExact(priorCount, added.size());
        writeStream(directory.resolve(name), out -> {
            out.writeInt(ARCHIVE_MAGIC);
            out.writeLong(total);
            Iterator<UUID> iterator = added.iterator();
            UUID nextAdded = iterator.hasNext() ? iterator.next() : null;
            ByteBuffer record = ByteBuffer.allocate(16);
            for (long index = 0; index < priorCount; index++) {
                record.clear();
                readFully(archiveChannel, record, 12 + index * 16);
                record.flip();
                UUID existing = new UUID(record.getLong(), record.getLong());
                while (nextAdded != null && nextAdded.compareTo(existing) < 0) {
                    writeUuid(out, nextAdded);
                    nextAdded = iterator.hasNext() ? iterator.next() : null;
                }
                writeUuid(out, existing);
            }
            while (nextAdded != null) {
                writeUuid(out, nextAdded);
                nextAdded = iterator.hasNext() ? iterator.next() : null;
            }
        });
        return name;
    }

    private void loadCheckpoint(Path file) throws Exception {
        readStream(file, in -> {
            if (in.readInt() != CHECKPOINT_MAGIC) throw new IOException("Unsupported managed checkpoint");
            if (!serverId.equals(in.readUTF())) throw new IOException("Checkpoint server identity mismatch");
            String archived = in.readUTF();
            if (!archived.isEmpty() && !archived.matches("archive-[0-9a-f-]{36}\\.bin"))
                throw new IOException("Invalid retired operation archive filename");
            openArchive(archived);
            int floorCount = count(in.readInt());
            for (int index = 0; index < floorCount; index++) {
                var identity = readIdentity(in);
                requireServer(identity);
                var floor = new Retirement(identity, in.readLong());
                if (retirements.put(identity.instanceId(), floor) != null) throw new IOException("Duplicate retirement floor");
            }
            int entryCount = count(in.readInt());
            for (int index = 0; index < entryCount; index++) {
                int size = in.readInt();
                if (size < 1 || size > MAX_ENTRY_SIZE - 40) throw new IOException("Invalid checkpoint record size");
                byte[] encoded = in.readNBytes(size);
                if (encoded.length != size) throw new EOFException("Incomplete checkpoint record");
                Entry entry = decode(new String(encoded, StandardCharsets.UTF_8));
                requireServer(entry.context().identity());
                if (entries.put(entry.context().operationId(), entry) != null) throw new IOException("Duplicate checkpoint operation");
            }
        });
    }

    private void openArchive(String name) throws Exception {
        closeArchive();
        archiveName = name;
        if (name.isEmpty()) return;
        Path file = directory.resolve(name);
        long size = Files.size(file);
        readStream(file, in -> {
            if (in.readInt() != ARCHIVE_MAGIC) throw new IOException("Invalid retired operation archive");
            long records = in.readLong();
            if (records < 0 || records > (Long.MAX_VALUE - 44) / 16 || size != 44 + records * 16)
                throw new IOException("Invalid retired operation archive length");
            UUID previous = null;
            for (long index = 0; index < records; index++) {
                UUID id = new UUID(in.readLong(), in.readLong());
                if (previous != null && previous.compareTo(id) >= 0) throw new IOException("Unordered retired operation archive");
                previous = id;
            }
            archiveCount = records;
        });
        archiveChannel = FileChannel.open(file, StandardOpenOption.READ);
    }

    private void closeArchive() {
        try { if (archiveChannel != null) archiveChannel.close(); } catch (IOException ignored) { }
        archiveChannel = null;
        archiveCount = 0;
        archiveName = "";
    }

    private void merge(Entry entry) throws IOException {
        Entry existing = entries.get(entry.context().operationId());
        if (existing != null && !existing.fingerprint().equals(entry.fingerprint()))
            throw new IOException("Conflicting operation payload during journal recovery");
        if (existing == null || !existing.confirmed() || entry.confirmed()) entries.put(entry.context().operationId(), entry);
    }

    private void validateEntries() throws IOException {
        Map<UUID, Entry> latest = new HashMap<>();
        for (Entry entry : snapshot().entries()) {
            UUID instance = entry.context().identity().instanceId();
            Entry previous = latest.get(instance);
            if (previous != null && (!previous.context().identity().equals(entry.context().identity())
                    || previous.action() == ManagedAction.DELETE && entry.action() != ManagedAction.DELETE
                    || previous.context().revision() == entry.context().revision()
                    && (!Objects.equals(previous.state(), entry.state())
                    || (previous.action() == ManagedAction.DELETE) != (entry.action() == ManagedAction.DELETE))))
                throw new IOException("Inconsistent managed checkpoint revisions");
            if (entry.confirmed()) latest.put(instance, entry);
        }
        for (Retirement floor : retirements.values()) {
            Entry state = latest.get(floor.identity().instanceId());
            if (state == null || !state.context().identity().equals(floor.identity()) || state.context().revision() < floor.throughRevision())
                throw new IOException("Retirement floor lacks its durable instance state");
        }
    }

    private void cleanup() throws IOException {
        try (var files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.endsWith(".entry") || name.startsWith("archive-") && name.endsWith(".bin") && !name.equals(archiveName)
                        || name.startsWith("write-") && name.endsWith(".tmp")) Files.deleteIfExists(file);
            }
        }
        forceDirectory();
    }

    private void requireServer(ManagedClaimIdentity identity) throws IOException {
        if (!identity.serverId().equals(serverId)) throw new IOException("Journal contains an operation for another server");
    }

    private static int count(int value) throws IOException {
        if (value < 0) throw new IOException("Invalid checkpoint record count");
        return value;
    }

    private static void writeIdentity(DataOutputStream out, ManagedClaimIdentity identity) throws IOException {
        out.writeUTF(identity.controllerId());
        writeUuid(out, identity.externalId());
        writeUuid(out, identity.instanceId());
        out.writeUTF(identity.serverId());
        writeUuid(out, identity.worldId());
    }

    private static ManagedClaimIdentity readIdentity(DataInputStream in) throws IOException {
        return new ManagedClaimIdentity(in.readUTF(), new UUID(in.readLong(), in.readLong()),
                new UUID(in.readLong(), in.readLong()), in.readUTF(), new UUID(in.readLong(), in.readLong()));
    }

    private static void writeUuid(DataOutputStream out, UUID id) throws IOException {
        out.writeLong(id.getMostSignificantBits());
        out.writeLong(id.getLeastSignificantBits());
    }

    private static void readFully(FileChannel channel, ByteBuffer target, long offset) throws IOException {
        while (target.hasRemaining()) {
            int read = channel.read(target, offset + target.position());
            if (read < 0) throw new EOFException("Incomplete retired operation archive");
        }
    }
    private synchronized void acquireLock() throws IOException {
        if (lock != null && lock.isValid()) return;
        FileChannel channel = FileChannel.open(directory.resolve("provider.lock"),
                StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        try {
            FileLock acquired = channel.tryLock();
            if (acquired == null) throw new IOException("Managed journal is already owned by another provider");
            lockChannel = channel;
            lock = acquired;
        } catch (IOException | OverlappingFileLockException error) {
            channel.close();
            throw new IOException("Cannot acquire exclusive managed journal ownership", error);
        }
    }

    private synchronized void checkLock() throws IOException {
        if (lock == null || !lock.isValid() || poisoned) throw new IOException("Managed journal ownership is unavailable; restart required after an uncertain checkpoint");
    }

    @Override public synchronized void close() {
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
        closeArchive();
        lock = null;
        lockChannel = null;
    }

    static String fingerprint(ManagedOperationContext context, ManagedAction action, ManagedClaimState state) {
        try {
            String content = encode(new Entry(context, action, state, "", false));
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }

    private static String encode(Entry entry) {
        var y = new YamlConfiguration();
        var c = entry.context();
        var i = c.identity();
        y.set("controller", i.controllerId());
        y.set("external", i.externalId().toString());
        y.set("instance", i.instanceId().toString());
        y.set("server", i.serverId());
        y.set("world", i.worldId().toString());
        y.set("operation", c.operationId().toString());
        y.set("revision", c.revision());
        y.set("initiator", c.initiator() == null ? null : c.initiator().toString());
        y.set("action", entry.action().name());
        y.set("fingerprint", entry.fingerprint());
        y.set("confirmed", entry.confirmed());
        if (entry.state() != null) {
            var s = entry.state();
            var b = s.bounds();
            y.set("bounds", List.of(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()));
            y.set("budget", s.areaBudget());
            y.set("members", s.members().stream().map(UUID::toString).sorted().toList());
            List<Map<String, String>> flags = new ArrayList<>();
            new TreeMap<>(s.flags()).forEach((name, value) -> {
                Map<String, String> flag = new LinkedHashMap<>();
                flag.put("name", name);
                flag.put("value64", Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
                flags.add(flag);
            });
            y.set("flags", flags);
            y.set("style", s.boundaryStyle() == null ? null : BoundaryStyles.encode(s.boundaryStyle()));
            y.set("priority", s.priority());
        }
        return y.saveToString();
    }

    private static Entry decode(String content) throws Exception {
        var y = new YamlConfiguration();
        y.loadFromString(content);
        var identity = new ManagedClaimIdentity(y.getString("controller"), UUID.fromString(y.getString("external")),
                UUID.fromString(y.getString("instance")), y.getString("server"), UUID.fromString(y.getString("world")));
        var context = new ManagedOperationContext(identity, UUID.fromString(y.getString("operation")),
                y.getLong("revision"), y.contains("initiator") ? UUID.fromString(y.getString("initiator")) : null);
        var action = ManagedAction.valueOf(y.getString("action"));
        ManagedClaimState state = null;
        if (y.contains("bounds")) {
            var b = y.getIntegerList("bounds");
            if (b.size() != 6) throw new IOException("Invalid journal geometry");
            Set<UUID> members = new HashSet<>();
            for (String value : y.getStringList("members")) members.add(UUID.fromString(value));
            Map<String, String> flags = new TreeMap<>();
            for (var flag : y.getMapList("flags")) flags.put((String) flag.get("name"),
                    new String(Base64.getDecoder().decode((String) flag.get("value64")), StandardCharsets.UTF_8));
            state = new ManagedClaimState(new ClaimBounds(b.get(0), b.get(1), b.get(2), b.get(3), b.get(4), b.get(5)),
                    y.getLong("budget"), members, flags, y.contains("style") ? BoundaryStyles.decode(y.getString("style")) : null,
                    y.getInt("priority"));
        }
        var entry = new Entry(context, action, state, y.getString("fingerprint"), y.getBoolean("confirmed"));
        if (!Objects.equals(entry.fingerprint(), fingerprint(context, action, state)))
            throw new IOException("Journal operation fingerprint mismatch");
        return entry;
    }

    @FunctionalInterface private interface StreamWriter { void write(DataOutputStream output) throws Exception; }
    @FunctionalInterface private interface StreamReader { void read(DataInputStream input) throws Exception; }

    private void write(Path target, byte[] payload) throws Exception {
        if (payload.length > MAX_ENTRY_SIZE - 40)
            throw new IOException("Managed journal entry exceeds the supported durable size");
        writeStream(target, out -> {
            out.writeInt(0x54434D31);
            out.writeInt(payload.length);
            out.write(payload);
        });
    }

    private void writeStream(Path target, StreamWriter writer) throws Exception {
        Path temporary = Files.createTempFile(directory, "write-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                var digest = MessageDigest.getInstance("SHA-256");
                var buffered = new BufferedOutputStream(Channels.newOutputStream(channel));
                var checked = new DigestOutputStream(buffered, digest);
                var output = new DataOutputStream(checked);
                writer.write(output);
                output.flush();
                buffered.write(digest.digest());
                buffered.flush();
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            forceDirectory();
        } finally { Files.deleteIfExists(temporary); }
    }

    private void forceDirectory() throws IOException {
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
    }

    private static byte[] read(Path file) throws Exception {
        long size = Files.size(file);
        if (size < 40 || size > MAX_ENTRY_SIZE) throw new IOException("Invalid journal entry size");
        byte[] content = Files.readAllBytes(file);
        ByteBuffer buffer = ByteBuffer.wrap(content);
        if (buffer.getInt() != 0x54434D31) throw new IOException("Unsupported managed journal format");
        int length = buffer.getInt();
        if (length != buffer.remaining() - 32) throw new IOException("Incomplete journal entry");
        byte[] payload = new byte[length];
        buffer.get(payload);
        byte[] checksum = new byte[32];
        buffer.get(checksum);
        var digest = MessageDigest.getInstance("SHA-256");
        digest.update(content, 0, content.length - 32);
        if (!MessageDigest.isEqual(checksum, digest.digest())) {
            if (!MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload)))
                throw new IOException("Managed journal checksum mismatch");
        }
        return payload;
    }

    private static void readStream(Path file, StreamReader reader) throws Exception {
        long size = Files.size(file);
        if (size < 32) throw new IOException("Incomplete managed checkpoint");
        try (var raw = new BufferedInputStream(Files.newInputStream(file))) {
            var limited = new LimitedInputStream(raw, size - 32);
            var digest = MessageDigest.getInstance("SHA-256");
            var checked = new DigestInputStream(limited, digest);
            var input = new DataInputStream(checked);
            reader.read(input);
            if (input.read() != -1) throw new IOException("Trailing checkpoint payload");
            byte[] checksum = raw.readNBytes(32);
            if (checksum.length != 32 || !MessageDigest.isEqual(checksum, digest.digest()) || raw.read() != -1)
                throw new IOException("Managed checkpoint checksum mismatch");
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;
        LimitedInputStream(InputStream input, long remaining) { super(input); this.remaining = remaining; }
        @Override public int read() throws IOException {
            if (remaining == 0) return -1;
            int value = in.read();
            if (value < 0) throw new EOFException("Incomplete checkpoint payload");
            remaining--;
            return value;
        }
        @Override public int read(byte[] data, int offset, int length) throws IOException {
            if (remaining == 0) return -1;
            int count = in.read(data, offset, (int) Math.min(length, remaining));
            if (count < 0) throw new EOFException("Incomplete checkpoint payload");
            remaining -= count;
            return count;
        }
    }
}
