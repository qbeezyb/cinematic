package me.cinematic.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import me.cinematic.core.ScreenKey;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Protocol 1. Every string, collection and enum is bounded before allocating client-controlled data. */
public final class Payloads {
    public enum Action { SELECT, PLAY_PAUSE, STOP, SEEK, SKIP, LOOP, RESIZE }
    public enum RequestKind { LIBRARY, ACCESS, CLOCK }
    public static final UUID NO_SCREEN = new UUID(0, 0);

    public record Request(RequestKind kind, UUID screen, int offset, long sentAt, String query) implements CustomPacketPayload {
        public static final Type<Request> TYPE = Payloads.type("request_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = codec((b, p) -> {
            b.writeEnum(p.kind); b.writeUUID(p.screen); b.writeVarInt(p.offset); b.writeLong(p.sentAt); b.writeUtf(p.query, 64);
        }, b -> new Request(b.readEnum(RequestKind.class), b.readUUID(), b.readVarInt(), b.readLong(), b.readUtf(64)));
        @Override public Type<Request> type() { return TYPE; }
    }

    public record Control(UUID screen, long revision, Action action, String mediaId, long value, int width, int height) implements CustomPacketPayload {
        public static final Type<Control> TYPE = Payloads.type("control_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Control> CODEC = codec((b, p) -> {
            b.writeUUID(p.screen); b.writeLong(p.revision); b.writeEnum(p.action); b.writeUtf(p.mediaId, 64);
            b.writeLong(p.value); b.writeVarInt(p.width); b.writeVarInt(p.height);
        }, b -> new Control(b.readUUID(), b.readLong(), b.readEnum(Action.class), b.readUtf(64), b.readLong(), b.readVarInt(), b.readVarInt()));
        @Override public Type<Control> type() { return TYPE; }
    }

    public record Snapshot(UUID id, ScreenKey key, int facing, int width, int height, String mediaId,
            String name, long durationMs, int videoWidth, int videoHeight, boolean audio,
            long positionMs, long serverTimeMs, boolean playing, boolean looping, long revision, int audioRange, String error) {
        private static void encode(RegistryFriendlyByteBuf b, Snapshot s) {
            b.writeUUID(s.id); b.writeUtf(s.key.dimension(), 256);
            b.writeInt(s.key.x()); b.writeInt(s.key.y()); b.writeInt(s.key.z());
            b.writeVarInt(s.facing); b.writeVarInt(s.width); b.writeVarInt(s.height); b.writeUtf(s.mediaId, 64);
            b.writeUtf(s.name, 256); b.writeLong(s.durationMs); b.writeVarInt(s.videoWidth); b.writeVarInt(s.videoHeight); b.writeBoolean(s.audio);
            b.writeLong(s.positionMs); b.writeLong(s.serverTimeMs); b.writeBoolean(s.playing); b.writeBoolean(s.looping); b.writeLong(s.revision);
            b.writeVarInt(s.audioRange); b.writeUtf(s.error, 512);
        }
        private static Snapshot decode(RegistryFriendlyByteBuf b) {
            return new Snapshot(b.readUUID(), new ScreenKey(b.readUtf(256), b.readInt(), b.readInt(), b.readInt()),
                    b.readVarInt(), b.readVarInt(), b.readVarInt(), b.readUtf(64), b.readUtf(256), b.readLong(),
                    b.readVarInt(), b.readVarInt(), b.readBoolean(), b.readLong(), b.readLong(), b.readBoolean(),
                    b.readBoolean(), b.readLong(), b.readVarInt(), b.readUtf(512));
        }
    }

    public record Snapshots(List<Snapshot> screens) implements CustomPacketPayload {
        public static final Type<Snapshots> TYPE = Payloads.type("snapshots_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshots> CODEC = codec((b, p) -> {
            b.writeVarInt(p.screens.size()); p.screens.forEach(s -> Snapshot.encode(b, s));
        }, b -> {
            int count = boundedCount(b, 64);
            var list = new ArrayList<Snapshot>(count);
            for (int i = 0; i < count; i++) { list.add(Snapshot.decode(b)); }
            return new Snapshots(List.copyOf(list));
        });
        @Override public Type<Snapshots> type() { return TYPE; }
    }

    public record LibraryItem(String id, String name, long durationMs, boolean video) {}
    public record Library(UUID screen, long requestId, int offset, int total, List<LibraryItem> items, String status) implements CustomPacketPayload {
        public static final Type<Library> TYPE = Payloads.type("library_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Library> CODEC = codec((b, p) -> {
            b.writeUUID(p.screen); b.writeLong(p.requestId); b.writeVarInt(p.offset); b.writeVarInt(p.total); b.writeUtf(p.status, 512);
            b.writeVarInt(p.items.size());
            p.items.forEach(item -> { b.writeUtf(item.id, 64); b.writeUtf(item.name, 256); b.writeLong(item.durationMs); b.writeBoolean(item.video); });
        }, b -> {
            UUID screen = b.readUUID(); long requestId = b.readLong(); int offset = b.readVarInt(); int total = b.readVarInt(); String status = b.readUtf(512);
            int count = boundedCount(b, 20);
            var items = new ArrayList<LibraryItem>(count);
            for (int i = 0; i < count; i++) { items.add(new LibraryItem(b.readUtf(64), b.readUtf(256), b.readLong(), b.readBoolean())); }
            return new Library(screen, requestId, offset, total, List.copyOf(items), status);
        });
        @Override public Type<Library> type() { return TYPE; }
    }

    public record Access(String baseUrl, String token, long expiresMs, String error) implements CustomPacketPayload {
        public static final Type<Access> TYPE = Payloads.type("access_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Access> CODEC = codec((b, p) -> {
            b.writeUtf(p.baseUrl, 2048); b.writeUtf(p.token, 64); b.writeLong(p.expiresMs); b.writeUtf(p.error, 512);
        }, b -> new Access(b.readUtf(2048), b.readUtf(64), b.readLong(), b.readUtf(512)));
        @Override public Type<Access> type() { return TYPE; }
    }

    public record Clock(long sentAt, long serverTimeMs) implements CustomPacketPayload {
        public static final Type<Clock> TYPE = Payloads.type("clock_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Clock> CODEC = codec((b, p) -> {
            b.writeLong(p.sentAt); b.writeLong(p.serverTimeMs);
        }, b -> new Clock(b.readLong(), b.readLong()));
        @Override public Type<Clock> type() { return TYPE; }
    }

    public record Open(UUID screen) implements CustomPacketPayload {
        public static final Type<Open> TYPE = Payloads.type("open_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Open> CODEC = codec((b, p) -> b.writeUUID(p.screen), b -> new Open(b.readUUID()));
        @Override public Type<Open> type() { return TYPE; }
    }

    public record Notice(UUID screen, String message) implements CustomPacketPayload {
        public static final Type<Notice> TYPE = Payloads.type("notice_v1");
        public static final StreamCodec<RegistryFriendlyByteBuf, Notice> CODEC = codec((b, p) -> {
            b.writeUUID(p.screen); b.writeUtf(p.message, 512);
        }, b -> new Notice(b.readUUID(), b.readUtf(512)));
        @Override public Type<Notice> type() { return TYPE; }
    }

    private static int boundedCount(RegistryFriendlyByteBuf b, int max) {
        int size = b.readVarInt();
        if (size < 0 || size > max) { throw new IllegalArgumentException("Invalid Cinematic payload size"); }
        return size;
    }
    private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> type(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath("cinematic", path));
    }
    private static <T> StreamCodec<RegistryFriendlyByteBuf, T> codec(BiConsumer<RegistryFriendlyByteBuf, T> encoder, Function<RegistryFriendlyByteBuf, T> decoder) {
        return StreamCodec.of(encoder::accept, decoder::apply);
    }
    private Payloads() {}
}
