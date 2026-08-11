package com.example.loadbalancer.routing.hash;

import java.nio.charset.StandardCharsets;

/**
 * MurmurHash3 (x86, 32-bit), used for IP hashing and for placing virtual nodes on the
 * consistent-hash ring.
 *
 * <h2>Why not {@code String.hashCode()}</h2>
 * Two reasons, both of which produce real incidents:
 * <ol>
 *   <li><b>Distribution.</b> {@code String.hashCode} is a weak polynomial hash. IPv4
 *       addresses from one subnet are near-identical strings, so their hashes cluster and
 *       {@code hash % n} maps whole subnets onto the same backend.</li>
 *   <li><b>Stability.</b> Anything derived from {@code Object.hashCode} — or from a hash
 *       seeded per JVM — gives a different answer in each ALB instance, so "the same
 *       client reaches the same backend" silently stops being true the moment you run two
 *       load balancers. Murmur3 is a pure function of the bytes: every instance, every
 *       restart, every JVM version agrees.</li>
 * </ol>
 *
 * <p>Murmur3 is not a cryptographic hash and is not used as one here. An attacker who can
 * choose their source IP could try to collide onto one backend; if that matters, seed the
 * hash per deployment or switch to SipHash. The trade-off is deliberate: consistent
 * hashing is called on every request and SHA-256 per request is far more expensive for a
 * property (uniformity) that Murmur3 already provides.
 */
public final class Murmur3 {

    private static final int C1 = 0xcc9e2d51;
    private static final int C2 = 0x1b873593;

    private Murmur3() {
    }

    /** Hashes a string's UTF-8 bytes with the default seed. */
    public static int hash32(String input) {
        return hash32(input, 0);
    }

    /** Hashes a string's UTF-8 bytes with an explicit seed (used to spread virtual nodes). */
    public static int hash32(String input, int seed) {
        return hash32(input.getBytes(StandardCharsets.UTF_8), seed);
    }

    /**
     * MurmurHash3 x86_32.
     *
     * @param data bytes to hash
     * @param seed initial state
     * @return a 32-bit hash; may be negative, so callers must use {@code Math.floorMod}
     *         rather than {@code %} when reducing it to an index
     */
    public static int hash32(byte[] data, int seed) {
        int length = data.length;
        int h1 = seed;
        int blocks = length >>> 2;

        for (int i = 0; i < blocks; i++) {
            int index = i << 2;
            int k1 = (data[index] & 0xff)
                    | ((data[index + 1] & 0xff) << 8)
                    | ((data[index + 2] & 0xff) << 16)
                    | ((data[index + 3] & 0xff) << 24);
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;

            h1 ^= k1;
            h1 = Integer.rotateLeft(h1, 13);
            h1 = h1 * 5 + 0xe6546b64;
        }

        int k1 = 0;
        int tailStart = blocks << 2;
        switch (length & 3) {
            case 3 -> {
                k1 ^= (data[tailStart + 2] & 0xff) << 16;
                k1 ^= (data[tailStart + 1] & 0xff) << 8;
                k1 ^= (data[tailStart] & 0xff);
            }
            case 2 -> {
                k1 ^= (data[tailStart + 1] & 0xff) << 8;
                k1 ^= (data[tailStart] & 0xff);
            }
            case 1 -> k1 ^= (data[tailStart] & 0xff);
            default -> {
                // no tail bytes
            }
        }
        if (k1 != 0) {
            k1 *= C1;
            k1 = Integer.rotateLeft(k1, 15);
            k1 *= C2;
            h1 ^= k1;
        }

        h1 ^= length;
        return fmix32(h1);
    }

    private static int fmix32(int h) {
        h ^= h >>> 16;
        h *= 0x85ebca6b;
        h ^= h >>> 13;
        h *= 0xc2b2ae35;
        h ^= h >>> 16;
        return h;
    }

    /** Reduces a possibly-negative hash to {@code [0, bound)}. */
    public static int toIndex(int hash, int bound) {
        return Math.floorMod(hash, bound);
    }
}
