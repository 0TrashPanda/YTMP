# Client ⇄ Host Protocol

> Status: **decided: 1A (Kotlin → TS types) + 2.1 (commands + events, snapshot on connect)**.
> The other options are kept below for reference.

Transport: **WebSocket** (`ws://` on the LAN by default, `wss://` on servers, see
[networking](networking.md)). The protocol must be implemented by the Kotlin host and the
Svelte/TS frontend, and the types on both sides must never drift apart.

Message list: [protocol-messages.md](protocol-messages.md).

## Generating the TypeScript types ✅

A **small custom generator** (`protocol/src/main/kotlin/.../tsgen`) walks the
kotlinx.serialization `SerialDescriptor`s of the protocol classes and writes
`frontend/src/lib/protocol.gen.ts` (types, plus constants like `PROTOCOL_VERSION`). Run
`./gradlew :protocol:generateTs` after changing `protocol/`; the file is checked in, and the
frontend's TypeScript compiler (`pnpm check`) turns a mismatch into an error.
[kxs-ts-gen](https://github.com/adamko-dev/kotlinx-serialization-typescript-generator) was the
other option; the own generator can't break because of someone else's project.

## Question 1: where do the message types come from?

### A. Kotlin is the source of truth ⭐ chosen

Messages are Kotlin `@Serializable` classes (kotlinx.serialization, JSON). TypeScript
types are **generated** from them during the build.

- ➕ One place to edit, in the language the host is written in. JSON is easy to debug in
  browser devtools.
- ➖ Relies on a Kotlin → TS generator tool. Only types are shared, not validation.

### B. A neutral schema as the source of truth

Messages are defined in **JSON Schema** or **TypeSpec**, and Kotlin, TS (and Python, for the
module API) types are generated from it.

- ➕ Language-neutral. The same approach can define the module HTTP API for the Python
  service.
- ➖ An extra language to learn and maintain. Generated Kotlin code is often clunky.

### C. Protobuf

Messages are `.proto` files, binary over WebSocket, with generated code for Kotlin, TS and Python.

- ➕ Strict schema, compact, very mature codegen, versioning built in.
- ➖ Binary is harder to debug. Heavier toolchain. Size savings don't matter for a few
  friends.

### D. Kotlin Multiplatform shared module

The protocol (and maybe client logic) is written in Kotlin Multiplatform and compiled to
JS for the frontend.

- ➕ Real shared *code*, not just types.
- ➖ Large JS bundle, awkward to use from Svelte. Defeats the point of choosing TS.

## Question 2: how is state synced?

### 1. Commands + events ⭐ chosen

- The client sends **commands** (`AddSongs`, `Skip`, …).
- The host validates them against permissions and broadcasts **events** (`QueueItemsAdded`,
  `PlaybackState`, …).
- On (re)connect, the client receives a full **snapshot** of the room state, then events.

➕ Small messages, a clear permission check per command, and events can feed the history.
➖ Client must apply events correctly (a bug means drifted state until the next snapshot).

### 2. Full state on every change

- Clients send commands, and the host sends the **entire room state** after every change.

➕ Very simple, and the client can't drift.
➖ Bigger messages (still fine for small rooms). Harder to animate "what changed".

### 3. Snapshot + JSON patches

- Same as 2, but the host sends a **diff** (JSON Patch) of the state.

➕ Small messages, no hand-written event handling.
➖ Diffs are less meaningful than named events (for example for history, notifications).
