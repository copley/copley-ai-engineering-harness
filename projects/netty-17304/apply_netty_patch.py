#!/usr/bin/env python3
from pathlib import Path
import sys


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def splice(text: str, start_marker: str, end_marker: str, replacement: str, label: str) -> str:
    start = text.find(start_marker)
    if start < 0:
        raise RuntimeError(f"{label}: start marker not found")
    end = text.find(end_marker, start)
    if end < 0:
        raise RuntimeError(f"{label}: end marker not found")
    return text[:start] + replacement + text[end:]


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_netty_patch.py <SslHandler.java>")

    path = Path(sys.argv[1])
    text = path.read_text()

    text = replace_once(
        text,
        "import java.util.List;\n",
        "import java.util.ArrayDeque;\nimport java.util.List;\n",
        "ArrayDeque import",
    )

    text = replace_once(
        text,
        "    private SslHandlerCoalescingBufferQueue pendingUnencryptedWrites;\n"
        "    private Promise<Channel> handshakePromise = new LazyChannelPromise();\n",
        "    private SslHandlerCoalescingBufferQueue pendingUnencryptedWrites;\n"
        "    private ArrayDeque<SslHandlerCoalescingBufferQueue> flushedUnencryptedWrites;\n"
        "    private SslHandlerCoalescingBufferQueue recycledUnencryptedWrites;\n"
        "    private long pendingUnencryptedBytes;\n"
        "    private boolean wrapResumeScheduled;\n"
        "    private Promise<Channel> handshakePromise = new LazyChannelPromise();\n",
        "pending write state",
    )

    handler_removed = '''    @Override
    public void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        try {
            if (hasPendingUnencryptedWrites()) {
                releaseAndFailAllPendingWrites(ctx,
                        new ChannelException("Pending write on removal of SslHandler"));
            }
            pendingUnencryptedWrites = null;
            flushedUnencryptedWrites = null;
            recycledUnencryptedWrites = null;
            pendingUnencryptedBytes = 0;
            wrapResumeScheduled = false;

            SSLException cause = null;

            // If the handshake or SSLEngine closure is not done yet we should fail corresponding promise and
            // notify the rest of the
            // pipeline.
            if (!handshakePromise.isDone()) {
                cause = new SSLHandshakeException("SslHandler removed before handshake completed");
                if (handshakePromise.tryFailure(cause)) {
                    ctx.fireUserEventTriggered(new SslHandshakeCompletionEvent(cause));
                }
            }
            if (!sslClosePromise.isDone()) {
                if (cause == null) {
                    cause = new SSLException("SslHandler removed before SSLEngine was closed");
                }
                notifyClosePromise(cause);
            }
        } finally {
            ReferenceCountUtil.release(engine);
        }
    }

'''
    text = splice(
        text,
        "    @Override\n    public void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {",
        "    @Override\n    public void bind(ChannelHandlerContext ctx, SocketAddress localAddress, ChannelPromise promise) throws Exception {",
        handler_removed,
        "handlerRemoved0",
    )

    write_flush_wrap = '''    private static IllegalStateException newPendingWritesNullException() {
        return new IllegalStateException("pendingUnencryptedWrites is null, handlerRemoved0 called?");
    }

    private SslHandlerCoalescingBufferQueue newPendingUnencryptedWritesQueue(Channel channel) {
        return new SslHandlerCoalescingBufferQueue(channel, 16, engineType.wantsDirectBuffer) {
            @Override
            protected int wrapDataSize() {
                return SslHandler.this.wrapDataSize;
            }
        };
    }

    private SslHandlerCoalescingBufferQueue acquirePendingUnencryptedWritesQueue(Channel channel) {
        SslHandlerCoalescingBufferQueue queue = recycledUnencryptedWrites;
        if (queue != null) {
            recycledUnencryptedWrites = null;
            return queue;
        }
        return newPendingUnencryptedWritesQueue(channel);
    }

    private void recyclePendingUnencryptedWritesQueue(SslHandlerCoalescingBufferQueue queue) {
        if (recycledUnencryptedWrites == null) {
            recycledUnencryptedWrites = queue;
        }
    }

    private SslHandlerCoalescingBufferQueue currentFlushedUnencryptedWrites() {
        if (flushedUnencryptedWrites == null) {
            return null;
        }
        for (;;) {
            SslHandlerCoalescingBufferQueue queue = flushedUnencryptedWrites.peekFirst();
            if (queue == null) {
                return null;
            }
            if (!queue.isEmpty()) {
                return queue;
            }
            flushedUnencryptedWrites.removeFirst();
            recyclePendingUnencryptedWritesQueue(queue);
        }
    }

    private boolean hasFlushedUnencryptedWrites() {
        return currentFlushedUnencryptedWrites() != null;
    }

    private boolean hasPendingUnencryptedWrites() {
        return pendingUnencryptedWrites != null &&
                (!pendingUnencryptedWrites.isEmpty() || hasFlushedUnencryptedWrites());
    }

    private void markPendingUnencryptedWritesFlushed(ChannelHandlerContext ctx) {
        if (pendingUnencryptedWrites.isEmpty()) {
            return;
        }
        if (flushedUnencryptedWrites == null) {
            flushedUnencryptedWrites = new ArrayDeque<SslHandlerCoalescingBufferQueue>(2);
        }
        flushedUnencryptedWrites.addLast(pendingUnencryptedWrites);
        pendingUnencryptedWrites = acquirePendingUnencryptedWritesQueue(ctx.channel());
    }

    private void writeAndRemoveAllFlushed(ChannelHandlerContext ctx) {
        for (;;) {
            SslHandlerCoalescingBufferQueue queue = currentFlushedUnencryptedWrites();
            if (queue == null) {
                return;
            }
            int before = queue.readableBytes();
            try {
                queue.writeAndRemoveAll(ctx);
            } finally {
                pendingUnencryptedBytes -= before - queue.readableBytes();
            }
        }
    }

    private long downstreamPendingBytes(ChannelHandlerContext ctx) {
        ChannelOutboundBuffer outboundBuffer = ctx.channel().unsafe().outboundBuffer();
        if (outboundBuffer == null) {
            return 0;
        }
        long downstream = outboundBuffer.totalPendingWriteBytes() - pendingUnencryptedBytes;
        return Math.max(0, downstream);
    }

    private int maxPlaintextBytesForWrap(ChannelHandlerContext ctx, SslHandlerCoalescingBufferQueue queue) {
        int readableBytes = queue.readableBytes();
        if (readableBytes == 0) {
            // Empty writes are used to preserve promise / control-frame semantics and must be processable
            // even when application-data wrapping is backpressured.
            return 0;
        }

        long available = (long) ctx.channel().config().getWriteBufferHighWaterMark() - downstreamPendingBytes(ctx);
        if (available <= 0) {
            return -1;
        }

        long desired = wrapDataSize > 0 ? Math.min((long) wrapDataSize, readableBytes) : readableBytes;
        return (int) Math.max(1, Math.min(Math.min(desired, available), Integer.MAX_VALUE));
    }

    private void suspendWrapUntilTransportDrains(final ChannelHandlerContext ctx) {
        if (wrapResumeScheduled || !hasFlushedUnencryptedWrites()) {
            return;
        }
        wrapResumeScheduled = true;

        // This marker is written below SslHandler. Its promise cannot complete until all TLS output already
        // queued ahead of it has drained from the transport. It therefore provides a transport-only resume signal
        // which is independent of Channel.isWritable(), whose value also includes queued plaintext.
        ChannelPromise markerPromise = ctx.newPromise();
        ctx.write(Unpooled.EMPTY_BUFFER, markerPromise);
        markerPromise.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(final ChannelFuture future) {
                EventExecutor executor = ctx.executor();
                try {
                    executor.execute(new Runnable() {
                        @Override
                        public void run() {
                            wrapResumeScheduled = false;
                            if (!future.isSuccess()) {
                                Throwable cause = future.cause();
                                releaseAndFailAll(ctx, cause);
                                ctx.fireExceptionCaught(cause);
                                return;
                            }
                            if (ctx.isRemoved() || !hasFlushedUnencryptedWrites()) {
                                return;
                            }
                            if (isStateSet(STATE_PROCESS_TASK)) {
                                // The delegated-task completion path will resume wrapping.
                                return;
                            }
                            try {
                                wrap(ctx, false);
                            } catch (Throwable cause) {
                                setHandshakeFailure(ctx, cause);
                                ctx.fireExceptionCaught(cause);
                            } finally {
                                forceFlush(ctx);
                            }
                        }
                    });
                } catch (RejectedExecutionException cause) {
                    wrapResumeScheduled = false;
                    releaseAndFailAll(ctx, cause);
                }
            }
        });
    }

    @Override
    public void write(final ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (!(msg instanceof ByteBuf)) {
            UnsupportedMessageTypeException exception = new UnsupportedMessageTypeException(msg, ByteBuf.class);
            ReferenceCountUtil.safeRelease(msg);
            promise.setFailure(exception);
        } else if (pendingUnencryptedWrites == null) {
            ReferenceCountUtil.safeRelease(msg);
            promise.setFailure(newPendingWritesNullException());
        } else {
            ByteBuf buffer = (ByteBuf) msg;
            pendingUnencryptedBytes += buffer.readableBytes();
            pendingUnencryptedWrites.add(buffer, promise);
        }
    }

    @Override
    public void flush(ChannelHandlerContext ctx) throws Exception {
        if (pendingUnencryptedWrites.isEmpty() && !hasFlushedUnencryptedWrites()) {
            // It's important to NOT use a voidPromise here as the user may want to add a
            // ChannelFutureListener to the ChannelPromise later. See #3364.
            pendingUnencryptedWrites.add(Unpooled.EMPTY_BUFFER, ctx.newPromise());
        }
        markPendingUnencryptedWritesFlushed(ctx);

        // Do not encrypt the first write request if this handler is created with startTLS enabled.
        if (startTls && !isStateSet(STATE_SENT_FIRST_MESSAGE)) {
            setState(STATE_SENT_FIRST_MESSAGE);
            writeAndRemoveAllFlushed(ctx);
            forceFlush(ctx);
            startHandshakeProcessing(true);
            return;
        }

        if (isStateSet(STATE_PROCESS_TASK)) {
            return;
        }

        try {
            wrapAndFlush(ctx);
        } catch (Throwable cause) {
            setHandshakeFailure(ctx, cause);
            PlatformDependent.throwException(cause);
        }
    }

    private void wrapAndFlush(ChannelHandlerContext ctx) throws SSLException {
        if (!handshakePromise.isDone()) {
            setState(STATE_FLUSHED_BEFORE_HANDSHAKE);
        }
        try {
            wrap(ctx, false);
        } finally {
            // We may have written some parts of data before an exception was thrown so ensure we always flush.
            // See https://github.com/netty/netty/issues/3900#issuecomment-172481830
            forceFlush(ctx);
        }
    }

    // This method will not call setHandshakeFailure(...) !
    private void wrap(ChannelHandlerContext ctx, boolean inUnwrap) throws SSLException {
        ByteBuf out = null;
        ByteBufAllocator alloc = ctx.alloc();
        try {
            // Only continue to loop if the handler was not removed in the meantime.
            // See https://github.com/netty/netty/issues/5860
            outer: while (!ctx.isRemoved()) {
                SslHandlerCoalescingBufferQueue queue = currentFlushedUnencryptedWrites();
                if (queue == null) {
                    break;
                }

                int maxPlaintextBytes = maxPlaintextBytesForWrap(ctx, queue);
                if (maxPlaintextBytes < 0) {
                    suspendWrapUntilTransportDrains(ctx);
                    break;
                }

                ChannelPromise promise = ctx.newPromise();
                ByteBuf buf = queue.remove(alloc, maxPlaintextBytes, promise);
                if (buf == null) {
                    continue;
                }
                int removedBytes = buf.readableBytes();
                pendingUnencryptedBytes -= removedBytes;

                SSLEngineResult result = null;

                try {
                    if (buf.readableBytes() > MAX_PLAINTEXT_LENGTH) {
                        int readableBytes = buf.readableBytes();
                        int numPackets = readableBytes / MAX_PLAINTEXT_LENGTH;
                        if (readableBytes % MAX_PLAINTEXT_LENGTH != 0) {
                            numPackets += 1;
                        }

                        if (out == null) {
                            out = allocateOutNetBuf(ctx, readableBytes, buf.nioBufferCount() + numPackets);
                        }
                        result = wrapMultiple(alloc, engine, buf, out);
                    } else {
                        if (out == null) {
                            out = allocateOutNetBuf(ctx, buf.readableBytes(), buf.nioBufferCount());
                        }
                        result = wrap(alloc, engine, buf, out);
                    }
                } catch (Throwable e) {
                    buf.release();
                    promise.setFailure(e);
                    PlatformDependent.throwException(e);
                }

                if (buf.isReadable()) {
                    pendingUnencryptedBytes += buf.readableBytes();
                    queue.addFirst(buf, promise);
                    // When we add the buffer/promise pair back we need to be sure we don't complete the promise
                    // later. We only complete the promise if the buffer is completely consumed.
                    promise = null;
                } else {
                    buf.release();
                }

                // We need to write any data before we invoke any methods which may trigger re-entry, otherwise
                // writes may occur out of order and TLS sequencing may be off (e.g. SSLV3_ALERT_BAD_RECORD_MAC).
                if (out.isReadable()) {
                    final ByteBuf b = out;
                    out = null;
                    if (promise != null) {
                        ctx.write(b, promise);
                    } else {
                        ctx.write(b);
                    }
                } else if (promise != null) {
                    ctx.write(Unpooled.EMPTY_BUFFER, promise);
                }
                // else out is not readable we can re-use it and so save an extra allocation

                if (result.getStatus() == Status.CLOSED) {
                    if (hasPendingUnencryptedWrites()) {
                        Throwable exception = handshakePromise.cause();
                        if (exception == null) {
                            exception = sslClosePromise.cause();
                            if (exception == null) {
                                exception = new SslClosedEngineException("SSLEngine closed already");
                            }
                        }
                        releaseAndFailAllPendingWrites(ctx, exception);
                    }
                    return;
                } else {
                    switch (result.getHandshakeStatus()) {
                        case NEED_TASK:
                            if (!runDelegatedTasks(inUnwrap)) {
                                break outer;
                            }
                            break;
                        case FINISHED:
                        case NOT_HANDSHAKING:
                            setHandshakeSuccess();
                            break;
                        case NEED_WRAP:
                            // Keep the control-frame continuation in the already-flushed segment so later
                            // application writes cannot cross this flush boundary.
                            if (result.bytesProduced() > 0 && queue.isEmpty()) {
                                queue.add(Unpooled.EMPTY_BUFFER);
                            }
                            break;
                        case NEED_UNWRAP:
                            readIfNeeded(ctx);
                            return;
                        default:
                            throw new IllegalStateException(
                                    "Unknown handshake status: " + result.getHandshakeStatus());
                    }
                }
            }
        } finally {
            if (out != null) {
                out.release();
            }
            if (inUnwrap) {
                setState(STATE_NEEDS_FLUSH);
            }
        }
    }

'''
    text = splice(
        text,
        "    private static IllegalStateException newPendingWritesNullException() {",
        "    /**\n     * This method will not call\n     * {@link #setHandshakeFailure(ChannelHandlerContext, Throwable, boolean, boolean, boolean)}",
        write_flush_wrap,
        "write/flush/wrap",
    )

    text = text.replace(
        "setHandshakeSuccess() && inUnwrap && !pendingUnencryptedWrites.isEmpty()",
        "setHandshakeSuccess() && inUnwrap && hasFlushedUnencryptedWrites()",
    )
    text = text.replace(
        "(pendingUnencryptedWrites != null  && !pendingUnencryptedWrites.isEmpty())",
        "(pendingUnencryptedWrites != null && hasFlushedUnencryptedWrites())",
    )

    release_old = '''    private void releaseAndFailAll(ChannelHandlerContext ctx, Throwable cause) {
        if (resumptionController != null &&
                (!engine.getSession().isValid() || cause instanceof SSLHandshakeException)) {
            resumptionController.remove(engine());
        }
        if (pendingUnencryptedWrites != null) {
            pendingUnencryptedWrites.releaseAndFailAll(ctx, cause);
        }
    }
'''
    release_new = '''    private void releaseAndFailAllPendingWrites(ChannelHandlerContext ctx, Throwable cause) {
        if (flushedUnencryptedWrites != null) {
            SslHandlerCoalescingBufferQueue queue;
            while ((queue = flushedUnencryptedWrites.pollFirst()) != null) {
                if (!queue.isEmpty()) {
                    queue.releaseAndFailAll(ctx, cause);
                }
            }
        }
        if (pendingUnencryptedWrites != null && !pendingUnencryptedWrites.isEmpty()) {
            pendingUnencryptedWrites.releaseAndFailAll(ctx, cause);
        }
        pendingUnencryptedBytes = 0;
        wrapResumeScheduled = false;
    }

    private void releaseAndFailAll(ChannelHandlerContext ctx, Throwable cause) {
        if (resumptionController != null &&
                (!engine.getSession().isValid() || cause instanceof SSLHandshakeException)) {
            resumptionController.remove(engine());
        }
        releaseAndFailAllPendingWrites(ctx, cause);
    }
'''
    text = replace_once(text, release_old, release_new, "releaseAndFailAll")

    handler_added_old = '''    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        Channel channel = ctx.channel();
        pendingUnencryptedWrites = new SslHandlerCoalescingBufferQueue(channel, 16, engineType.wantsDirectBuffer) {
            @Override
            protected int wrapDataSize() {
                return SslHandler.this.wrapDataSize;
            }
        };
'''
    handler_added_new = '''    @Override
    public void handlerAdded(final ChannelHandlerContext ctx) throws Exception {
        this.ctx = ctx;
        Channel channel = ctx.channel();
        pendingUnencryptedWrites = newPendingUnencryptedWritesQueue(channel);
'''
    text = replace_once(text, handler_added_old, handler_added_new, "handlerAdded queue creation")

    if "setHandshakeSuccess() && inUnwrap && !pendingUnencryptedWrites.isEmpty()" in text:
        raise RuntimeError("unconverted wrapNonAppData pending queue check")

    path.write_text(text)


if __name__ == "__main__":
    main()
