package com.twilight.claims.managed;

import com.twilight.claims.api.ClaimBounds;
import com.twilight.claims.api.managed.*;
import com.twilight.claims.visual.BoundaryStyles;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

final class ManagedJournal implements AutoCloseable {
    private static final int MAX_ENTRY_SIZE = 4_194_304;
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
    record Loaded(String serverId, List<Entry> entries) {}
    private final Path directory;
    private FileChannel lockChannel;
    private FileLock lock;

    ManagedJournal(Path directory) { this.directory = directory; }

    Loaded load(String configuredServerId) throws Exception {
        Files.createDirectories(directory);
        acquireLock();
        Path serverFile = directory.resolve("server-id");
        String serverId;
        if (Files.exists(serverFile)) {
            serverId = new String(read(serverFile), StandardCharsets.UTF_8);
            if (!configuredServerId.isBlank() && !configuredServerId.equals(serverId))
                throw new IOException("Configured server ID differs from the durable managed journal");
        } else {
            serverId = configuredServerId.isBlank() ? UUID.randomUUID().toString() : configuredServerId;
            write(serverFile, serverId.getBytes(StandardCharsets.UTF_8));
        }
        List<Entry> entries = new ArrayList<>();
        try (var paths = Files.list(directory)) {
            for (Path file : paths.filter(p -> p.getFileName().toString().endsWith(".entry")).toList()) {
                Entry entry = decode(new String(read(file), StandardCharsets.UTF_8));
                if (!file.getFileName().toString().equals(entry.context().operationId() + ".entry"))
                    throw new IOException("Journal operation filename mismatch");
                if (!entry.context().identity().serverId().equals(serverId))
                    throw new IOException("Journal contains an operation for another server");
                entries.add(entry);
            }
        }
        return new Loaded(serverId, entries);
    }

    void save(Entry entry) throws Exception {
        checkLock();
        write(directory.resolve(entry.context().operationId() + ".entry"), encode(entry).getBytes(StandardCharsets.UTF_8));
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
        if (lock == null || !lock.isValid()) throw new IOException("Managed journal ownership is not held");
    }

    @Override public synchronized void close() {
        try { if (lock != null) lock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
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

    private void write(Path target, byte[] payload) throws Exception {
        if (payload.length > MAX_ENTRY_SIZE - 40)
            throw new IOException("Managed journal entry exceeds the supported durable size");
        byte[] checksum = MessageDigest.getInstance("SHA-256").digest(payload);
        Path temporary = Files.createTempFile(directory, "write-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                ByteBuffer buffer = ByteBuffer.allocate(8 + payload.length + checksum.length);
                buffer.putInt(0x54434D31).putInt(payload.length).put(payload).put(checksum).flip();
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) { channel.force(true); }
        } finally { Files.deleteIfExists(temporary); }
    }

    private static byte[] read(Path file) throws Exception {
        long size = Files.size(file);
        if (size < 40 || size > MAX_ENTRY_SIZE) throw new IOException("Invalid journal entry size");
        ByteBuffer buffer = ByteBuffer.wrap(Files.readAllBytes(file));
        if (buffer.getInt() != 0x54434D31) throw new IOException("Unsupported managed journal format");
        int length = buffer.getInt();
        if (length != buffer.remaining() - 32) throw new IOException("Incomplete journal entry");
        byte[] payload = new byte[length];
        buffer.get(payload);
        byte[] checksum = new byte[32];
        buffer.get(checksum);
        if (!MessageDigest.isEqual(checksum, MessageDigest.getInstance("SHA-256").digest(payload)))
            throw new IOException("Managed journal checksum mismatch");
        return payload;
    }
}
