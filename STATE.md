# Engineering Harness State

This file is the persistent handoff state for the AI Engineering Harness. Keep it concise, current, and evidence-based.

## Harness Status

```text
Status: ACTIVE
Lifecycle Stage: ROOT CAUSE / DESIGN
Current Project: Netty #17304
Upstream: netty/netty
Issue: https://github.com/netty/netty/issues/17304
```

## Current Objective

Design and verify the smallest upstream-safe repair for the TLS direct-memory amplification exposed by Netty #17304.

The proven failure path is:

```text
large pre-flush application batch
-> ChunkedWriteHandler pass-through (4.1.105+)
-> HttpResponseEncoder
-> SslHandler pending plaintext
-> one flush
-> SslHandler drains the whole pending plaintext queue
-> TLS ciphertext buffers
-> slow transport retains ciphertext
```

## Confirmed Facts

1. Netty #13711 changed `ChunkedWriteHandler` so ordinary non-`ChunkedInput` messages bypass its internal queue when no chunked write is pending.
2. This creates a behavior boundary between 4.1.104 and 4.1.105+: ordinary writes now reach downstream encoders and `SslHandler` before the later `flush()`.
3. `SslHandler.write(...)` itself does not encrypt immediately. It queues plaintext in `pendingUnencryptedWrites`.
4. That queue already participates in Netty pending-byte / channel-writability accounting through `AbstractCoalescingBufferQueue` and `PendingBytesTracker`.
5. Therefore the earlier hypothesis that `SslHandler` plaintext is invisible to write-buffer watermarks is false.
6. `SslHandler.flush()` calls `wrapAndFlush()`, and `wrap()` loops over the pending plaintext queue without a downstream-writability/backpressure bound.
7. For native OpenSSL/TCNative engines, `SslHandler` allocates TLS output with `allocator.directBuffer(...)`, so excessive flush-time conversion becomes pooled direct-memory pressure.
8. Vert.x 4.4.1 user `Buffer` instances are heap-backed by default, and HTTP/1 response writes pass their underlying `ByteBuf` into Netty. The bulk direct-memory conversion therefore need not originate in the user buffer itself.

## Reproduction Evidence

The harness has a real JDK-TLS client/server handshake plus a simulated slow transport that retains ciphertext and contributes those retained bytes to Netty's pending-byte accounting.

Configuration:

```text
payload batch: 64 KiB
write-buffer low watermark: 2 KiB
write-buffer high watermark: 4 KiB
pipeline: ChunkedWriteHandler -> HttpResponseEncoder -> SslHandler -> slow transport
```

Observed in GitHub Actions:

### Netty 4.1.104.Final

```text
before flush: writable=true
plaintext already at TLS boundary: 0 bytes
ciphertext retained after flush: 4,209 bytes
```

### Netty 4.1.135.Final

```text
before flush: writable=false
plaintext already at TLS boundary: 66,031 bytes
ciphertext retained after flush: 66,221 bytes
```

Both tests pass deterministically.

This is the regression mechanism: the old `ChunkedWriteHandler` forwarding loop admitted roughly a watermark-sized amount into TLS before stopping on `Channel.isWritable()`. After #13711, the full batch can already be staged in `SslHandler`; its subsequent flush converts the whole staged batch even though the channel is already unwritable.

## Root Cause

The root cause is not missing `SslHandler` pending-byte accounting.

It is a mismatch between two forms of backpressure:

- `SslHandler` correctly marks the channel unwritable as plaintext accumulates.
- once `flush()` is invoked, `SslHandler.wrap()` does not use downstream capacity to bound how much already-staged plaintext it converts into ciphertext.

Before #13711, `ChunkedWriteHandler` accidentally supplied that bound by forwarding queued ordinary messages only while the channel remained writable. #13711 removed that accidental pre-TLS gate.

## Rejected Approaches

### Add writability accounting to `SslHandler`

Rejected. The accounting already exists and is execution-verified.

### Blindly revert #13711

Rejected as the default repair. It restores the old accidental interaction but conflicts with maintainer direction that `ChunkedWriteHandler` should not generally buffer unrelated messages.

### Simply stop `SslHandler.wrap()` whenever `Channel.isWritable()` is false

Rejected. The queued plaintext itself contributes to channel unwritability; stopping solely on `isWritable()` can deadlock a flushed backlog because the plaintext cannot leave the queue to make the channel writable again.

## Design Requirement

A correct `SslHandler` repair must:

1. preserve write-vs-flush semantics;
2. preserve TLS handshake/control-message progress;
3. bound plaintext-to-ciphertext conversion by downstream capacity;
4. continue making progress after slow-transport writes drain;
5. avoid wrapping writes that arrived after the relevant flush boundary;
6. preserve promise ordering and failure propagation;
7. avoid throughput regressions for normally writable channels.

A likely design needs an explicit flushed-plaintext boundary plus resumable wrapping, rather than a bare `channel.isWritable()` check.

## Current Next Action

Prototype the smallest `SslHandler` design that records how many pending plaintext bytes were covered by a user flush, wraps only a bounded portion when downstream transport is backpressured, and resumes that flushed backlog when emitted ciphertext completes.

Then run:

- the new #17304 regression test;
- existing `SslHandler` tests;
- existing `ChunkedWriteHandler` tests;
- JDK SSL and native OpenSSL variants where available;
- stress checks for promise ordering, later unflushed writes, handshake, close-notify, and partial engine consumption.
