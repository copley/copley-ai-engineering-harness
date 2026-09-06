#!/usr/bin/env python3
from pathlib import Path
import sys


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected one match, found {count}")
    return text.replace(old, new, 1)


def main() -> None:
    if len(sys.argv) != 2:
        raise SystemExit("usage: apply_alert_compat_patch.py <SslHandler.java>")

    path = Path(sys.argv[1])
    text = path.read_text()

    old = '''    private void wrapAndFlush(ChannelHandlerContext ctx) throws SSLException {
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
'''

    new = '''    private void addFlushedControlWrite(ChannelHandlerContext ctx) {
        // wrapAndFlush() is also used internally after an unwrap failure to give SSLEngine a chance to
        // generate a TLS alert. That control wrap must not consume application writes which have not crossed
        // an explicit flush boundary, so give it an isolated, already-flushed empty segment.
        SslHandlerCoalescingBufferQueue queue = acquirePendingUnencryptedWritesQueue(ctx.channel());
        queue.add(Unpooled.EMPTY_BUFFER, ctx.newPromise());
        if (flushedUnencryptedWrites == null) {
            flushedUnencryptedWrites = new ArrayDeque<SslHandlerCoalescingBufferQueue>(2);
        }
        flushedUnencryptedWrites.addLast(queue);
    }

    private void wrapAndFlush(ChannelHandlerContext ctx) throws SSLException {
        if (!hasFlushedUnencryptedWrites()) {
            // Preserve the historical SslHandler behavior of performing an empty wrap when there is no
            // application data. This is required for TLS alerts / handshake control frames. Keep the empty
            // wrap in its own flushed segment so unflushed application data cannot cross a flush boundary.
            addFlushedControlWrite(ctx);
        }
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
'''

    text = replace_once(text, old, new, "wrapAndFlush control-frame compatibility")
    path.write_text(text)


if __name__ == "__main__":
    main()
