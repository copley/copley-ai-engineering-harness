package io.netty.channel;

/**
 * Test-only access to Netty's package-private pending-byte accounting.
 * This lets the reproducer model a transport that accepts ciphertext writes
 * but cannot drain them to a slow peer.
 */
public final class PendingBytesAccess {
    private PendingBytesAccess() {
    }

    public static void increment(Channel channel, long bytes) {
        ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
        if (buffer == null) {
            throw new IllegalStateException("channel has no outbound buffer");
        }
        buffer.incrementPendingOutboundBytes(bytes);
    }

    public static void decrement(Channel channel, long bytes) {
        ChannelOutboundBuffer buffer = channel.unsafe().outboundBuffer();
        if (buffer == null) {
            throw new IllegalStateException("channel has no outbound buffer");
        }
        buffer.decrementPendingOutboundBytes(bytes);
    }
}
