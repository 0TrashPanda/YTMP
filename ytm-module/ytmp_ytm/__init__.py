"""YTMP source module for YouTube Music and YouTube."""

import os as _os

# A folder of its own (the desktop app): yt-dlp keeps itself up to date there (update.py).
# Before anything imports yt-dlp, so here. Off on the server: its image updates nightly.
if _os.environ.get("YTMP_YTM_UPDATE_DIR"):
    from . import update as _update

    _update.activate(_os.environ["YTMP_YTM_UPDATE_DIR"])
