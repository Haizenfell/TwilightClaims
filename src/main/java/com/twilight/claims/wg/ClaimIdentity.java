package com.twilight.claims.wg;

import com.sk89q.worldguard.protection.regions.ProtectedRegion;

import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ClaimIdentity {
    private static final Pattern NUMBERED_ID = Pattern.compile("[1-9][0-9]{15}_claim_([1-9][0-9]*)");
    private static final Pattern LEGACY_ID = Pattern.compile("claim_[0-9a-f]{8}(?:_[1-9][0-9]*)?");
    private static final long MIN_PREFIX = 1_000_000_000_000_000L;
    private static final long MAX_PREFIX = 10_000_000_000_000_000L;

    private ClaimIdentity() {}

    public static boolean isClaimId(String id) {
        return id != null && (numberOf(id).isPresent() || LEGACY_ID.matcher(id).matches());
    }

    public static OptionalLong numberOf(String id) {
        if (id == null) return OptionalLong.empty();
        Matcher matcher = NUMBERED_ID.matcher(id);
        if (!matcher.matches()) return OptionalLong.empty();
        try {
            return OptionalLong.of(Long.parseLong(matcher.group(1)));
        } catch (NumberFormatException ignored) {
            return OptionalLong.empty();
        }
    }

    public static String displayName(ProtectedRegion region) {
        return displayName(region.getId());
    }

    public static String displayName(String id) {
        OptionalLong number = numberOf(id);
        return number.isPresent() ? "claim_" + number.getAsLong() : id;
    }

    static long nextNumber(Set<Long> occupied) {
        long number = 1;
        while (occupied.contains(number)) {
            if (number == Long.MAX_VALUE) throw new IllegalStateException("No claim numbers available");
            number++;
        }
        return number;
    }

    static String newId(long number, Predicate<String> inUse) {
        return newId(number, () -> ThreadLocalRandom.current().nextLong(MIN_PREFIX, MAX_PREFIX), inUse);
    }

    static String newId(long number, LongSupplier prefixes, Predicate<String> inUse) {
        if (number < 1) throw new IllegalArgumentException("Claim number must be positive");
        String id;
        do {
            long prefix = prefixes.getAsLong();
            if (prefix < MIN_PREFIX || prefix >= MAX_PREFIX) {
                throw new IllegalArgumentException("Claim prefix must contain 16 decimal digits");
            }
            id = prefix + "_claim_" + number;
        } while (inUse.test(id));
        return id;
    }
}
