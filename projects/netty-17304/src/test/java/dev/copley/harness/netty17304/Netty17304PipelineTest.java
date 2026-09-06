package dev.copley.harness.netty17304;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.channel.PendingBytesAccess;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import java.util.ArrayList;
import java.util.List;

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
                    "NETTY_17304_PRE_FLUSH version=%s chunks=%d payloadBytes=%d writable=%s "
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

            assertEquals(0, belowSsl.writeCount,
                    "SslHandler must not emit TLS records before flush in this experiment");

            if (prePassThroughVersion) {
                assertEquals(0, aboveSsl.writeCount,
                        "pre-4.1.105 ChunkedWriteHandler should retain ordinary writes until flush");
                assertTrue(channel.isWritable(),
                        "pre-4.1.105 ChunkedWriteHandler queue should not trip channel watermarks here");
                assertEquals(MAX_CHUNKS, chunksWritten,
                        "the producer should reach the experiment cap while the channel remains writable");
            } else {
                assertTrue(aboveSsl.writeCount > 0,
                        "post-4.1.104 writes should reach the HTTP/TLS boundary before flush");
                assertFalse(channel.isWritable(),
                        "SslHandler pending plaintext should trip the configured high watermark");
                assertTrue(payloadBytes <= HIGH_WATER_MARK + (2 * CHUNK_SIZE),
                        "a producer that checks writability after every write should stop close to the high watermark");
            }
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    void flushTimeTlsConversionShowsTheRegressionMechanism() throws Exception {
        String version = System.getProperty("netty.version", "unknown");
        boolean prePassThroughVersion = version.startsWith("4.1.104.");

        SelfSignedCertificate certificate = new SelfSignedCertificate();
        SslContext serverContext = SslContextBuilder
                .forServer(certificate.certificate(), certificate.privateKey())
                .sslProvider(SslProvider.JDK)
                .build();
        SslContext clientContext = SslContextBuilder
                .forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .sslProvider(SslProvider.JDK)
                .build();

        SlowOutboundHandler slowTransport = new SlowOutboundHandler();
        RecordingOutboundHandler aboveSsl = new RecordingOutboundHandler();
        SslHandler serverSsl = serverContext.newHandler(PooledByteBufAllocator.DEFAULT);
        SslHandler clientSsl = clientContext.newHandler(PooledByteBufAllocator.DEFAULT);

        EmbeddedChannel server = new EmbeddedChannel(
                slowTransport,
                serverSsl,
                aboveSsl,
                new HttpResponseEncoder(),
                new ChunkedWriteHandler());
        EmbeddedChannel client = new EmbeddedChannel(clientSsl);

        server.config().setWriteBufferWaterMark(
                new WriteBufferWaterMark(LOW_WATER_MARK, HIGH_WATER_MARK));

        try {
            completeHandshake(client, server, clientSsl, serverSsl);
            slowTransport.enableSlowMode();
            aboveSsl.reset();

            HttpResponse response = new DefaultHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            HttpUtil.setTransferEncodingChunked(response, true);
            server.write(response);

            // Deliberately enqueue one event-loop-sized batch without consulting writability between writes.
            // This models code that batches work before the next drain/writability reaction.
            int payloadBytes = 0;
            for (int i = 0; i < MAX_CHUNKS; i++) {
                ByteBuf payload = server.alloc().heapBuffer(CHUNK_SIZE, CHUNK_SIZE).writeZero(CHUNK_SIZE);
                server.write(new DefaultHttpContent(payload));
                payloadBytes += CHUNK_SIZE;
            }

            boolean writableBeforeFlush = server.isWritable();
            long plaintextObservedBeforeFlush = aboveSsl.totalBytes;

            server.flush();
            server.runPendingTasks();

            long ciphertextHeldBySlowTransport = slowTransport.pendingBytes();

            System.out.printf(
                    "NETTY_17304_FLUSH version=%s payloadBytes=%d writableBeforeFlush=%s "
                            + "plaintextAboveSsl=%d ciphertextHeld=%d writableAfterFlush=%s%n",
                    version,
                    payloadBytes,
                    writableBeforeFlush,
                    plaintextObservedBeforeFlush,
                    ciphertextHeldBySlowTransport,
                    server.isWritable());

            if (prePassThroughVersion) {
                assertTrue(writableBeforeFlush,
                        "pre-4.1.105 data is still hidden in ChunkedWriteHandler before flush");
                assertTrue(ciphertextHeldBySlowTransport <= HIGH_WATER_MARK * 4L,
                        "old ChunkedWriteHandler should stop forwarding near the channel backpressure boundary");
            } else {
                assertFalse(writableBeforeFlush,
                        "post-4.1.104 SslHandler plaintext accounting should already mark the channel unwritable");
                assertTrue(plaintextObservedBeforeFlush >= payloadBytes,
                        "the whole batch should already be staged at the TLS boundary before flush");
                assertTrue(ciphertextHeldBySlowTransport > HIGH_WATER_MARK * 4L,
                        "SslHandler flush should expose whether it drains far beyond an already-hit high watermark");
            }
        } finally {
            slowTransport.disableAndRelease();
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
            certificate.delete();
        }
    }

    private static void completeHandshake(
            EmbeddedChannel client,
            EmbeddedChannel server,
            SslHandler clientSsl,
            SslHandler serverSsl) {
        for (int i = 0; i < 100 && !(clientSsl.handshakeFuture().isDone() && serverSsl.handshakeFuture().isDone()); i++) {
            boolean progressed = transferOutbound(client, server);
            progressed |= transferOutbound(server, client);
            client.runPendingTasks();
            server.runPendingTasks();
            if (!progressed) {
                client.runScheduledPendingTasks();
                server.runScheduledPendingTasks();
            }
        }
        // Deliver any final handshake flight generated by the iteration that completed one side first.
        transferOutbound(client, server);
        transferOutbound(server, client);

        assertTrue(clientSsl.handshakeFuture().isSuccess(),
                () -> "client TLS handshake failed: " + clientSsl.handshakeFuture().cause());
        assertTrue(serverSsl.handshakeFuture().isSuccess(),
                () -> "server TLS handshake failed: " + serverSsl.handshakeFuture().cause());
    }

    private static boolean transferOutbound(EmbeddedChannel from, EmbeddedChannel to) {
        boolean progressed = false;
        for (;;) {
            Object message = from.readOutbound();
            if (message == null) {
                return progressed;
            }
            progressed = true;
            to.writeInbound(message);
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

        void reset() {
            writeCount = 0;
            totalBytes = 0;
            directBytes = 0;
        }
    }

    private static final class SlowOutboundHandler extends ChannelDuplexHandler {
        private final List<PendingWrite> pending = new ArrayList<>();
        private boolean slowMode;
        private long pendingBytes;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (!slowMode || !(msg instanceof ByteBuf)) {
                ctx.write(msg, promise);
                return;
            }

            ByteBuf buffer = (ByteBuf) msg;
            int bytes = buffer.readableBytes();
            PendingBytesAccess.increment(ctx.channel(), bytes);
            pendingBytes += bytes;
            pending.add(new PendingWrite(buffer, promise, bytes));
            // Intentionally do not propagate or complete the promise. This models ciphertext waiting
            // in a transport that cannot currently send to a slow peer.
        }

        void enableSlowMode() {
            slowMode = true;
        }

        long pendingBytes() {
            return pendingBytes;
        }

        void disableAndRelease() {
            slowMode = false;
            for (PendingWrite write : pending) {
                PendingBytesAccess.decrement(write.promise.channel(), write.bytes);
                ReferenceCountUtil.safeRelease(write.buffer);
                write.promise.trySuccess();
            }
            pending.clear();
            pendingBytes = 0;
        }
    }

    private static final class PendingWrite {
        private final ByteBuf buffer;
        private final ChannelPromise promise;
        private final int bytes;

        PendingWrite(ByteBuf buffer, ChannelPromise promise, int bytes) {
            this.buffer = buffer;
            this.promise = promise;
            this.bytes = bytes;
        }
    }
}
