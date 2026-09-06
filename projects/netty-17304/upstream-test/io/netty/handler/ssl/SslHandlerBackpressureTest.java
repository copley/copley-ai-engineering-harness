/*
 * Copyright 2026 The Netty Project
 *
 * The Netty Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package io.netty.handler.ssl;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelPromise;
import io.netty.channel.SslTestPendingBytesAccess;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLEngine;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SslHandlerBackpressureTest {

    @Test
    void wrapsOnlyFlushedPlaintextAndResumesAsCiphertextDrains() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        SslContext serverContext = SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey())
                .sslProvider(SslProvider.JDK)
                .build();
        SslContext clientContext = SslContextBuilder.forClient()
                .sslProvider(SslProvider.JDK)
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .build();

        SSLEngine serverEngine = serverContext.newEngine(UnpooledByteBufAllocator.DEFAULT);
        SSLEngine clientEngine = clientContext.newEngine(UnpooledByteBufAllocator.DEFAULT);
        SslHandler serverSsl = new SslHandler(serverEngine);
        SslHandler clientSsl = new SslHandler(clientEngine);
        EmbeddedChannel server = new EmbeddedChannel(serverSsl);
        EmbeddedChannel client = new EmbeddedChannel(clientSsl);
        try {
            completeHandshake(client, server, clientSsl, serverSsl);

            server.config().setWriteBufferWaterMark(new WriteBufferWaterMark(4096, 8192));

            final int writes = 16;
            final int bytesPerWrite = 4096;
            final int firstBatchBytes = writes * bytesPerWrite;
            List<ChannelPromise> firstBatchPromises = new ArrayList<ChannelPromise>(writes);
            for (int i = 0; i < writes; i++) {
                ChannelPromise promise = server.newPromise();
                firstBatchPromises.add(promise);
                server.write(Unpooled.buffer(bytesPerWrite).writeZero(bytesPerWrite), promise);
            }

            assertEquals(firstBatchBytes, SslTestPendingBytesAccess.totalPendingWriteBytes(server));
            assertFalse(server.isWritable());
            assertEquals(0, outboundBytes(server), "plaintext must not be wrapped before flush");

            server.flush();
            int firstBurst = outboundBytes(server);
            assertTrue(firstBurst > 0, "flush must produce some ciphertext");
            assertTrue(firstBurst < firstBatchBytes,
                    "a single flush must not convert the complete plaintext batch");
            assertTrue(firstBurst <= server.config().getWriteBufferHighWaterMark() * 2,
                    "TLS conversion burst should stay near the transport watermark");

            ChannelPromise afterBoundary = server.newPromise();
            server.write(Unpooled.buffer(2048).writeZero(2048), afterBoundary);
            assertFalse(afterBoundary.isDone(), "write after the flush boundary must remain pending");

            int cycles = 0;
            while (!allDone(firstBatchPromises) && cycles++ < 100) {
                drainOutbound(server);
                server.runPendingTasks();
                server.runScheduledPendingTasks();
            }

            assertTrue(allDone(firstBatchPromises), "flushed plaintext must eventually drain");
            assertFalse(afterBoundary.isDone(), "later write must not cross the earlier flush boundary");
            assertEquals(0, outboundBytes(server), "all ciphertext from the first flush should be drained");

            server.flush();
            assertTrue(outboundBytes(server) > 0, "second flush must wrap the later write");
            drainOutbound(server);
            server.runPendingTasks();
            server.runScheduledPendingTasks();
            assertTrue(afterBoundary.isSuccess(), "later write must complete after its own flush");
        } finally {
            server.finishAndReleaseAll();
            client.finishAndReleaseAll();
            serverContext.release();
            clientContext.release();
            certificate.delete();
        }
    }

    private static void completeHandshake(
            EmbeddedChannel client,
            EmbeddedChannel server,
            SslHandler clientSsl,
            SslHandler serverSsl) {
        for (int i = 0;
             i < 100 && !(clientSsl.handshakeFuture().isDone() && serverSsl.handshakeFuture().isDone());
             i++) {
            boolean progressed = transferOutbound(client, server);
            progressed |= transferOutbound(server, client);
            client.runPendingTasks();
            server.runPendingTasks();
            if (!progressed) {
                client.runScheduledPendingTasks();
                server.runScheduledPendingTasks();
            }
        }
        assertTrue(clientSsl.handshakeFuture().isSuccess(), "client handshake failed");
        assertTrue(serverSsl.handshakeFuture().isSuccess(), "server handshake failed");
    }

    private static boolean transferOutbound(EmbeddedChannel source, EmbeddedChannel target) {
        boolean progressed = false;
        ByteBuf message;
        while ((message = source.readOutbound()) != null) {
            progressed = true;
            target.writeInbound(message);
        }
        return progressed;
    }

    private static int outboundBytes(EmbeddedChannel channel) {
        int bytes = 0;
        ByteBuf message;
        List<ByteBuf> messages = new ArrayList<ByteBuf>();
        while ((message = channel.readOutbound()) != null) {
            bytes += message.readableBytes();
            messages.add(message);
        }
        for (ByteBuf queued : messages) {
            channel.writeOutbound(queued);
        }
        return bytes;
    }

    private static void drainOutbound(EmbeddedChannel channel) {
        ByteBuf message;
        while ((message = channel.readOutbound()) != null) {
            message.release();
        }
    }

    private static boolean allDone(List<ChannelPromise> promises) {
        for (ChannelFuture promise : promises) {
            if (!promise.isDone()) {
                return false;
            }
            assertTrue(promise.isSuccess(), "flushed write failed");
        }
        return true;
    }
}
