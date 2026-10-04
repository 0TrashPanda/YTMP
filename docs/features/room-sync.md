# Your rooms on all your devices

> Status: ✅ built (phone ↔ server). How it works: [implementation/room-sync.md](../implementation/room-sync.md).

With a YTMP account, your rooms show up on **all your hosts**: the rooms on your phone are
listed on your server's start page, and the rooms on your server on your phone's room list.
This needs the phone to use your server for accounts (*Use accounts from a YTMP server* on the
phone's room list) and you to be logged in on both.

## One home at a time

A room **lives on one device**: its home. The other device keeps an up-to-date **copy**
(queue, history, position, roles, settings), so it never plays the same room twice and nothing
drifts apart.

When you open a room on the device where it doesn't live, it can:

- **open where it lives**: the server, or the phone (only on the phone's Wi-Fi), or
- **move here**: it continues here from where it was (paused), and its old home closes it.
  People still in it there get *This room moved* with a link to where it is now.

Which one happens is an **account setting** (on the account page), and each room can override
it in its settings (owner only):

| Setting | Opening a room that lives elsewhere |
|---------|-------------------------------------|
| **Ask** (default) | *Open it there* or *Move it here* |
| **Move** | Always moves it here |
| **Move if away** | Opens it where it lives if that device checked in the last minute; otherwise moves it |

## Rules

- All rooms your account owns sync, **solo and party**. Guest rooms don't.
- The newest move wins. A phone that was **offline** while its room moved keeps playing it
  until it's back online; then it closes it, and **changes made there meanwhile are dropped**.
- A room that moves keeps its code, unless that code is taken there; the list always shows the
  right one.
- Your phone's **solo** rooms open from your other devices too, with your account (on the
  phone's Wi-Fi).
