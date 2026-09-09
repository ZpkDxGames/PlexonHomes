package com.plexon.homes.api;

public record HomeLimitView(int limit, boolean unlimited, String source) {
    public static HomeLimitView unlimited(String source) { return new HomeLimitView(Integer.MAX_VALUE, true, source); }
}
