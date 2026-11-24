package io.github.yuvrajtiwary.earlybird.common;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.util.UUID;

/**
 * UUIDs in 22 characters (0-9A-Za-z) instead of 36, so feedback buttons ({@code fb:r:<id>}) fit Telegram's
 * 64-byte callback data limit with room to spare.
 */
public final class Base62 {

    private static final String DIGITS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final BigInteger BASE = BigInteger.valueOf(62);

    private Base62() {}

    public static String encode(UUID id) {
        ByteBuffer bytes = ByteBuffer.allocate(16).putLong(id.getMostSignificantBits()).putLong(id.getLeastSignificantBits());
        BigInteger n = new BigInteger(1, bytes.array());
        StringBuilder out = new StringBuilder();
        while (n.signum() > 0) {
            BigInteger[] qr = n.divideAndRemainder(BASE);
            out.append(DIGITS.charAt(qr[1].intValue()));
            n = qr[0];
        }
        return out.isEmpty() ? "0" : out.reverse().toString();
    }

    /** @throws IllegalArgumentException if {@code text} isn't a base62 UUID */
    public static UUID decode(String text) {
        BigInteger n = BigInteger.ZERO;
        for (char c : text.toCharArray()) {
            int digit = DIGITS.indexOf(c);
            if (digit < 0) {
                throw new IllegalArgumentException("not base62: " + text);
            }
            n = n.multiply(BASE).add(BigInteger.valueOf(digit));
        }
        if (n.bitLength() > 128) {
            throw new IllegalArgumentException("too large for a UUID");
        }
        byte[] raw = n.toByteArray();
        byte[] bytes = new byte[16];
        int copy = Math.min(raw.length, 16);
        System.arraycopy(raw, raw.length - copy, bytes, 16 - copy, copy);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
