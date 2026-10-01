# Chromecast Output Module

Feature: [host outputs](../features/playback-and-outputs.md#host-outputs).

## How casting works

1. The host **finds** Cast devices on the network (mDNS, `_googlecast._tcp`).
2. It tells the device to open the **Default Media Receiver** (Google's built-in player)
   and gives it a **URL** to play, plus title, artist and album art for the TV screen.
3. The Chromecast fetches the audio **from that URL itself**.

Because the Chromecast fetches the audio itself, the URL must be one it can reach and
play. That means the host's **proxied audio URL** (see [playback-sync](playback-sync.md)),
not a direct YouTube URL, because those are tied to the host's IP address.

## Per platform

| Host | How |
|------|-----|
| Android | Official **Google Cast SDK** (needs Google Play Services) |
| Linux server | No official SDK. Use an open-source Cast protocol library for the JVM, or talk the Cast protocol directly (TLS + protobuf on port 8009) |

## Sync

The Chromecast plays on its own clock, with a few seconds of buffering. It's controlled
like any output (play, pause, seek, volume), and the host keeps it roughly in sync through
the receiver's media status. It is not sample-accurate with client playback.

## Later

- A **custom Cast receiver** (a small web app that runs on the Chromecast) could show the
  [party screen](../features/ui.md#party-screen) on a TV.
