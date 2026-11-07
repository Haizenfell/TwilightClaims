package com.twilight.claims.api;

public record ClaimPoints(long used, long total, int purchased, int hoursPlayed, int perHour) {
    public long free() { return total - used; }
    public long hoursPoints() { return (long) hoursPlayed * perHour; }
}
