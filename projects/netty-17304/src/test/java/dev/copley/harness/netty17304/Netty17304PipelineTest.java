package dev.copley.harness.netty17304;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.stream.ChunkedWriteHandler;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class Netty17304PipelineTest {
    private static final int LOW_WATER_MARK = 2 * 1024;
    private static final int HIGH_WATER_MARK = 4 * 1024;
    private static final int CHUNK_SIZE = 1024;
    private static final int MAX_CHUNKS = 64;

    @Test
    void realPipelineLocatesThePreFlushBufferingBoundary() throws Exception {
        String version = System.getProperty("netty.version", "unknown");
        boolean prePassThroughVersion = version.startsWith("4.1.104.");

        SSLContext sslContext = SSLContext.getInstance("TLS");
        sslContext.init(null, null, null);
        SSLEngine engine = sslContext.createSSLEngine();
        // Server mode does not initiate a handshake merely because the channel becomes active.
        // We deliberately do not flush in this experiment: the question is where plaintext waits before flush.
        engine.setUseClientMode(false);

        RecordingOutboundHandler belowSsl = new RecordingOutboundHandler();
        RecordingOutboundHandler aboveSsl = new RecordingOutboundHandler();
        SslHandler sslHandler = new SslHandler(engine);

        EmbeddedChannel channel = new EmbeddedChannel(
                belowSsl,
                sslHandler,
                aboveSsl,
                new HttpResponseEncoder(),
                new ChunkedWriteHandler());

        PooledByteBufAllocator allocator = new PooledByteBufAllocator(true);
        channel.config().setAllocator(allocator);
        channel.config().setWriteBufferWaterMark(
                new WriteBufferWaterMark(LOW_WATER_MARK, HIGH_WATER_MARK));

        long directBefore = allocator.metric().usedDirectMemory();
        int payloadBytes = 0;
        int chunksWritten = 0;

        try {
            HttpResponse response = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            HttpUtil.setTransferEncodingChunked(response, true);
            channel.write(response);

            while (chunksWritten < MAX_CHUNKS && channel.isWritable()) {
                // Heap payload is intentional. If pooled direct memory appears before flush, the pipeline
                // itself created it rather than the test supplying direct content.
                ByteBuf payload = channel.alloc().heapBuffer(CHUNK_SIZE, CHUNK_SIZE).writeZero(CHUNK_SIZE);
                channel.write(new DefaultHttpContent(payload));
                payloadBytes += CHUNK_SIZE;
                chunksWritten++;
            }

            long directAfter = allocator.metric().usedDirectMemory();

            System.out.printf(
                    "NETTY_17304 version=%s chunks=%d payloadBytes=%d writable=%s "
                            + "aboveSslWrites=%d aboveSslBytes=%d aboveSslDirectBytes=%d "
                            + "belowSslWrites=%d belowSslBytes=%d directMemoryBefore=%d directMemoryAfter=%d%n",
                    version,
                    chunksWritten,
                    payloadBytes,
                    channel.isWritable(),
                    aboveSsl.writeCount,
                    aboveSsl.totalBytes,
                    aboveSsl.directBytes,
                    belowSsl.writeCount,
                    belowSsl.totalBytes,
                    directBefore,
                    directAfter);

            // SslHandler queues plaintext on write; encryption/output must not pass below it before flush.
            assertEquals(0, belowSsl.writeCount,
                    "SslHandler must not emit TLS records before flush in this experiment");

            if (prePassThroughVersion) {
                // 4.1.104 and earlier: ChunkedWriteHandler queues every write, so neither the HTTP encoder
                // nor SslHandler has seen the response yet. Its private queue does not drive Channel.isWritable().
                assertEquals(0, aboveSsl.writeCount,
                        "pre-4.1.105 ChunkedWriteHandler should retain ordinary writes until flush");
                assertTrue(channel.isWritable(),
                        "pre-4.1.105 ChunkedWriteHandler queue should not trip channel watermarks here");
                assertEquals(MAX_CHUNKS, chunksWritten,
                        "the producer should reach the experiment cap while the channel remains writable");
            } else {
                // 4.1.105+: ordinary writes pass through ChunkedWriteHandler immediately. The HTTP encoder
                // reaches SslHandler, whose pending-unencrypted queue is expected to participate in writability.
                assertTrue(aboveSsl.writeCount > 0,
                        "post-4.1.104 writes should reach the HTTP/TLS boundary before flush");
                assertFalse(channel.isWritable(),
                        "SslHandler pending plaintext should trip the configured high watermark");
                assertTrue(payloadBytes <= HIGH_WATER_MARK + (2 * CHUNK_SIZE),
                        "a producer that checks writability after every write should stop close to the high watermark");
            }
        } finally {
            // Closing an EmbeddedChannel releases queued messages. We intentionally never flush application data.
            channel.finishAndReleaseAll();
        }
    }

    private static final class RecordingOutboundHandler extends ChannelDuplexHandler {
        private int writeCount;
        private long totalBytes;
        private long directBytes;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            writeCount++;
            if (msg instanceof ByteBuf) {
                ByteBuf buffer = (ByteBuf) msg;
                int readable = buffer.readableBytes();
                totalBytes += readable;
                if (buffer.isDirect()) {
                    directBytes += readable;
                }
            }
            ctx.write(msg, promise);
        }
    }
}
