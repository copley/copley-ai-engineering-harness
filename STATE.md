# Engineering Harness State

This file is the persistent handoff state for the AI Engineering Harness. Keep it concise, current, and evidence-based.

## Harness Status

```text
Status: ACTIVE
Lifecycle Stage: REPRODUCTION / EVIDENCE
Current Project: Netty #17304
Upstream: netty/netty
Issue: https://github.com/netty/netty/issues/17304
```

## Current Objective

Determine the actual ownership boundary behind the pooled direct-memory increase reported in Netty #17304, produce a real TLS reproducer, and only then design the smallest upstream-safe repair.

The target failure class is buffering/backpressure behavior across:

```text
application writes
-> ChunkedWriteHandler
-> HTTP encoder
-> SslHandler pending plaintext
-> TLS wrap
-> ChannelOutboundBuffer
-> slow peer
```

## Confirmed Facts

1. Netty #13711 changed `ChunkedWriteHandler` so ordinary non-`ChunkedInput` messages bypass its internal queue when no chunked write is pending.
2. This creates a real behavior boundary between 4.1.104 and 4.1.105+: ordinary writes now reach downstream encoders before the later `flush()`.
3. The reporter's reproducer proves that propagation boundary and demonstrates direct-buffer staging before flush using a synthetic downstream direct-buffering handler.
4. Reverting #13711 is not currently the preferred architectural direction in the upstream discussion; maintainers have questioned whether `ChunkedWriteHandler` should buffer unrelated messages at all.
5. `SslHandler.write(...)` does not encrypt immediately. It adds outbound `ByteBuf`s to `pendingUnencryptedWrites`.
6. In current 4.1 and in 4.1.105, that queue is backed by `AbstractCoalescingBufferQueue` with the channel supplied to its constructor.
7. `AbstractCoalescingBufferQueue` creates a `PendingBytesTracker` and increments/decrements pending outbound bytes as buffers enter/leave the queue. The tracker updates the channel pipeline / outbound-buffer writability accounting.

## Important Contradiction Found

The latest issue hypothesis says that bytes retained in `SslHandler.pendingUnencryptedWrites` do not participate in channel write-buffer watermarks.

Source inspection contradicts that hypothesis: the queue explicitly tracks its readable bytes as pending outbound bytes.

Therefore no `SslHandler` writability-accounting patch should be written until an executable test proves a gap that source inspection has missed.

## Active Hypotheses

### H1 — source-level writability accounting is correct

`SslHandler` pending plaintext bytes already make the channel unwritable at the configured high watermark. If confirmed, the current issue hypothesis about missing watermark accounting is rejected.

Status: SUPPORTED BY SOURCE, NOT YET EXECUTION-VERIFIED.

### H2 — direct-memory amplification is caused earlier in the outbound pipeline

After #13711, ordinary messages reach encoders immediately. Any upstream encoder/application allocation of pooled direct `ByteBuf`s therefore happens before flush and those direct buffers are retained by `SslHandler` until flush. The memory representation changes even if logical pending-byte accounting remains correct.

Status: SUPPORTED BY PIPELINE MECHANICS; REAL-TLS REPRODUCTION REQUIRED.

### H3 — application/framework batching can still exceed useful memory bounds

Even with correct channel writability accounting, a framework may enqueue a large batch before reacting to writability changes, or many simultaneously writable channels may each retain up to their per-channel high watermark. This can produce a large aggregate direct-memory plateau under many slow consumers.

Status: OPEN.

## Rejected / Suspended Approaches

### Revert #13711 immediately

Suspended. This restores historical buffering but makes `ChunkedWriteHandler` responsible for non-chunked messages again and conflicts with maintainer direction.

### Add pending-byte accounting to `SslHandler`

Rejected as a speculative fix unless reproduction disproves current source behavior. The accounting already exists in `AbstractCoalescingBufferQueue`.

## Current Experiment

Build a self-contained executable test against released Netty versions that uses the real outbound pipeline shape:

```text
ChunkedWriteHandler
-> HttpResponseEncoder
-> SslHandler
-> EmbeddedChannel transport
```

The experiment must:

- write without flushing;
- configure a deliberately low `WriteBufferWaterMark`;
- observe exactly when `channel.isWritable()` changes;
- distinguish heap/direct input buffers;
- measure pooled direct-memory allocation;
- compare 4.1.104.Final with 4.1.135.Final;
- confirm whether `SslHandler` encrypts/copies before flush;
- record how much data can be queued before backpressure is visible.

## Definition of the Next Gate

Do not modify upstream production code until the real-pipeline experiment answers these questions:

1. Do `SslHandler` pending writes affect `Channel.isWritable()` before flush?
2. Which component allocates the pooled direct buffers observed before flush?
3. Is the memory growth bounded by configured channel watermarks when the producer respects writability?
4. Does the answer differ between 4.1.104 and 4.1.135?

## Next Recommended Action

Run the Netty 4.1.104 / 4.1.135 experiment in CI, inspect the measurements, then either:

- reject the current `SslHandler` hypothesis and move the investigation to encoder/framework batching, or
- isolate a concrete accounting gap and write a minimal Netty regression test plus patch.
