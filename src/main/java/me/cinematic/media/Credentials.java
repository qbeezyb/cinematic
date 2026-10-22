package me.cinematic.media;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/** Tokens never appear in URLs or logs. Refresh keeps the prior lease valid until its expiry. */
public final class Credentials {
    public record Lease(String token, long expiresMs) {}
    private record Grant(UUID player, long expiresMs) {}
    private static final long TTL_MS = Duration.ofMinutes(10).toMillis();
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final LongSupplier clock;

    public Credentials(LongSupplier clock) { this.clock = clock; }

    public synchronized Lease issue(UUID player) {
        long now = clock.getAsLong();
        grants.values().removeIf(g -> g.expiresMs <= now);
        // Bound outstanding tokens for a connection, including malicious repeated requests.
        long own = grants.values().stream().filter(g -> g.player.equals(player)).count();
        if (own >= 2) {
            var oldest = grants.entrySet().stream().filter(e -> e.getValue().player.equals(player))
                    .min(java.util.Comparator.comparingLong(e -> e.getValue().expiresMs));
            oldest.ifPresent(e -> grants.remove(e.getKey()));
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        long expires = now + TTL_MS;
        grants.put(hash(token), new Grant(player, expires));
        return new Lease(token, expires);
    }

    public boolean authenticate(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.length() != 50) { return false; }
        Grant grant = grants.get(hash(authorization.substring(7)));
        return grant != null && grant.expiresMs > clock.getAsLong();
    }

    public void revoke(UUID player) { grants.values().removeIf(g -> g.player.equals(player)); }
    public void clear() { grants.clear(); }

    private static String hash(String token) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
