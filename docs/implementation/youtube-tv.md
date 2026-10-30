# YouTube TV Output Module

Feature: [host outputs](../features/playback-and-outputs.md#host-outputs).

Some TVs and projectors have no Chromecast, only the YouTube app (for example the Yoton Y9
projector at home, "LeBeamski"). Phones still cast to them from the YouTube app: they find the
TV with **DIAL** and remote-control its YouTube app through YouTube's **Lounge API**. YTMP does
the same, so these TVs show up under *Play on* (with a TV icon). The TV plays each song itself,
straight from YouTube, with its video.

## Implementation ✅

- **Discovery (`cast/.../Dial.kt`):** an SSDP M-SEARCH for
  `urn:dial-multiscreen-org:service:dial:1`; each answer's `LOCATION` is the device
  description, whose `Application-URL` header is where its apps are. `GET <apps>/YouTube` says
  whether the YouTube app exists and runs, and while it runs its `screenId`. DIAL servers check
  the `Origin` header (YouTube's app answers 403 without one), and Java's `HttpURLConnection`
  silently drops it, so DIAL requests are plain HTTP over a socket.
- **Not Chromecasts:** Cast devices speak DIAL too (port 8008). A host that answers Cast's
  `/setup/eureka_info` is skipped: it's already a Chromecast output.
- **Server:** found every 5 minutes (`[youtube_tv] discovery`, needs multicast replies, as for
  Sonos), plus fixed TVs (`[youtube_tv] devices = ["192.168.68.100:56790"]`; without a port the
  usual DIAL ports are tried). **Phone:** `TvFinder`, every minute while the other finders
  run, holding a Wi-Fi multicast lock during each search (Android drops multicast otherwise);
  on another Wi-Fi network the TVs found before go, like the other devices.
- **Remote control (`cast/.../YoutubeLounge.kt`):** the screen id gets a lounge token
  (`/api/lounge/pairing/get_lounge_token_batch`), then a BrowserChannel session (`/bc/bind`)
  as a `REMOTE_CONTROL` named *YTMP*: the bind answers with the session ids (`c`, `S`), commands
  are form posts (`req0__sc=setPlaylist`, `play`, `pause`, `seekTo`, `setVolume`,
  `setAutoplayMode`, `stopVideo`, `getNowPlaying`), and the TV's events come down a long-polled
  stream of `length\n[[id,[event,args]],…]` chunks (`nowPlaying`, `onStateChange`,
  `onVolumeChanged`, `onAutoplayModeChanged`, `loungeScreenDisconnected`, …). Not a public API:
  it's what YouTube's own apps use.
- **Driver (`host/.../YoutubeTvOutputs.kt`),** a `DeviceDriver` like the Sonos one: if the YouTube app doesn't
  run, DIAL starts it (`POST <apps>/YouTube`) and waits for its screen id. Each song is
  `setPlaylist` with only its video id (`ytm:<id>`; songs from elsewhere can't play there,
  which the room is told) and the room's position, a little ahead. With a `listId`, `videoIds`
  or `currentIndex` too, the Yoton ignored it or went back to the video in its old queue. Play/pause follow the room. The TV only
  reports its position when something changes, so the driver asks (`getNowPlaying`) every 10 s
  and seeks when it's more than 3 s off (not during an ad). Volume works both ways (0–100 on
  the TV).
- **The TV's autoplay** is switched off while the room plays there (the room picks the next
  song, not the TV), and put back as it was when the room stops playing there. Switching the
  output off stops the video only if it's still ours.
- **Another remote takes it:** once the TV has shown our video, another video means someone
  played something else (the YouTube app on a phone): the room lets go of it, as with other
  outputs. A TV that doesn't show the song within ~20 s gets it again.
- **Near this phone:** it's one of the phone's `OutputDrivers`, so a room on a server can play
  on a TV at your place too.
- A podcast episode at another speed sits out on the TV, as on Sonos (the driver doesn't set
  the TV's playback speed).

## Limits

- The TV shows the video and plays YouTube's own audio, so it's only loosely in sync, like a
  Chromecast. Without YouTube Premium on the TV, ads play first; the room doesn't wait, and the
  TV is moved to the right place afterwards.
- Discovery needs SSDP multicast replies to reach the host. The Yoton doesn't answer SSDP sent
  straight to its address, only multicast; where that's blocked, add the TV by address.

## Tests

`YoutubeTvOutputsTest` drives a simulated TV (`FakeYoutubeTv`: a DIAL server and the Lounge
API on one port). `YoutubeTvRealDeviceTest` reads a real TV and pairs with it without playing
anything: `YOUTUBE_TV=http://192.168.68.100:56790/dd.xml ./gradlew :host:test --tests '*YoutubeTvRealDevice*'`;
with `YOUTUBE_TV_PLAY=1` a room plays a song there for a minute at 20% volume. On the Yoton
(2026-10-30): the song started 2 s after switching the output on, pause and play took a
second, a seek in the room moved the TV within 2 s, and it was 0.3 s off the room after that.
