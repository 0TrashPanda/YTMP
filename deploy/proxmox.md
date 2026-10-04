# Running a YTMP server on Proxmox

This sets up YTMP in a Proxmox **LXC container with Docker**, reachable from the internet
on your own domain with HTTPS, and **without spending your upload bandwidth on music**.

For HTTPS, pick one:

- **A. Caddy** in the same Docker setup: gets and renews certificates by itself. Easiest
  when you don't have a reverse proxy yet.
- **B. Your own nginx** (or another reverse proxy you already run): YTMP listens on port
  8080 and nginx forwards to it.

## How the audio gets to you

YTMP never hosts music itself. For every song the server asks YouTube for a stream link
and gives that link to the players (browsers and the app), which play it straight from
YouTube. The server only sends small messages: the queue, search results, play/pause.

The catch: YouTube ties every stream link to the **public IP address of the server**. So:

| Where you listen | What happens |
|------------------|--------------|
| At home (same internet connection as the server) | Plays straight from YouTube. ✅ |
| Speakers at home (Chromecast, Sonos) | They fetch the audio from the server over your home network: costs no internet bandwidth. ✅ |
| Away from home (mobile data, a friend's Wi-Fi) | The link doesn't work there. By default the server then streams the audio itself, over your upload. |

The `[audio] proxy` setting decides that last row:

| `proxy` | Away from home |
|---------|----------------|
| `always` (default) | Streams through the server: works, uses your upload. |
| `lan` (this guide) | Refused: the player says it can't play the song. Devices on your home network can still fall back to the server. |
| `never` | Refused for everyone, also Chromecast and Sonos. |

With `lan`, a room on this server is for listening at home (and for friends who visit).
Away from home, **host a room on your phone instead**: the YTMP app resolves the links on
the phone itself, on whatever connection it has.

## What you need

- Proxmox VE 8.1 or newer (Docker inside LXC on ZFS storage needs 8.1+).
- A domain or subdomain you can point at your home, e.g. `ytmp.example.com`. If your home IP
  changes, a dynamic DNS service (DuckDNS, Cloudflare, your router's own) keeps it up to date.
- Access to your router, to forward ports (or a reverse proxy that already gets them).

## 1. Create the container

In the Proxmox web UI, first download a template: your storage (e.g. `local`) →
**CT Templates** → **Templates** → **debian-13-standard** (Debian 12 works too).

Then **Create CT**:

| Tab | Setting |
|-----|---------|
| General | Hostname `ytmp`, set a root password. Keep **Unprivileged container** on. |
| Template | The Debian template |
| Disks | 20 GB |
| CPU | 2 cores |
| Memory | 4096 MB, swap 1024 MB (building the images needs it; 2048 MB is enough once it runs) |
| Network | Bridge `vmbr0`, IPv4 DHCP, or a static address |

Before starting it: the container → **Options** → **Features** → tick **nesting** and
**keyctl** (Docker needs them). Also set **Start at boot** to yes.

Or from the Proxmox shell (use the template name `pveam available` shows):

```sh
pveam update
pveam download local debian-13-standard_13.1-2_amd64.tar.zst
pct create 120 local:vztmpl/debian-13-standard_13.1-2_amd64.tar.zst \
  --hostname ytmp --cores 2 --memory 4096 --swap 1024 --rootfs local-lvm:20 \
  --net0 name=eth0,bridge=vmbr0,ip=dhcp --unprivileged 1 \
  --features nesting=1,keyctl=1 --onboot 1 --password
pct start 120
```

Give the container a fixed address: a DHCP reservation on your router, or a static IP
under **Network**. This guide calls it `192.168.1.50`.

## 2. Install Docker

Open the container's console (or `pct enter 120`), then:

```sh
apt update && apt install -y curl git
curl -fsSL https://get.docker.com | sh
docker run --rm hello-world
```

If `hello-world` prints its greeting, Docker works. If it fails with a permission error,
check that **nesting** and **keyctl** are on (step 1) and restart the container.

## 3. Get YTMP and configure it

```sh
git clone https://github.com/0TrashPanda/YTMP.git /opt/ytmp
cd /opt/ytmp/deploy
cp ytmp.example.toml ytmp.toml
```

Edit `ytmp.toml` (`nano ytmp.toml`) and change these parts:

```toml
[audio]
proxy = "lan"

[cast]
# Docker hides the network from mDNS, so list your Chromecasts (or speakers with
# Chromecast built in) yourself, and say where they fetch the audio.
discovery = false
devices = ["Living room=192.168.1.20"]
audio_base_url = "http://192.168.1.50:8080"

[sonos]
# The same for Sonos speakers, by IP address.
discovery = false
devices = []
```

Then make `.env`, with your domain:

```sh
cat > .env <<EOF
YTMP_MODULE_KEY=$(openssl rand -hex 32)
YTMP_URL=https://ytmp.example.com
YTMP_DOMAIN=ytmp.example.com
EOF
```

`YTMP_URL` is where YTMP accounts log in. `YTMP_DOMAIN` is the domain Caddy gets a
certificate for: only for option A, leave it out with nginx.

## 4. Domain and HTTPS

Point your domain at your home: an **A record** for `ytmp.example.com` with your home's
public IPv4 address (or a dynamic DNS name with a CNAME). **Never forward port 8080** on
your router: from the internet, everything goes through the reverse proxy.

### A. Caddy

On your router, forward **TCP 80 and 443** (and UDP 443 for HTTP/3, optional) to
`192.168.1.50`. Caddy needs port 80 reachable to get its certificate from Let's Encrypt.

With Caddy, every `docker compose` command below needs both files:
`docker compose -f docker-compose.yml -f docker-compose.caddy.yml …`

### B. Your own nginx

Your router already forwards 80 and 443 to nginx. Add a site for YTMP that forwards to the
container (replace the domain, address and certificate paths with yours):

```nginx
server {
    listen 443 ssl;
    http2 on;
    server_name ytmp.example.com;

    ssl_certificate     /etc/letsencrypt/live/ytmp.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/ytmp.example.com/privkey.pem;

    location / {
        proxy_pass http://192.168.1.50:8080;
        proxy_http_version 1.1;

        # Rooms are WebSocket connections (/ws).
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection $connection_upgrade;

        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-Proto $scheme;
        # The visitor's own address, for [audio] proxy = "lan". Set it (don't append to
        # what the visitor sent), so it can't be faked.
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header X-Real-IP $remote_addr;

        # Room connections and audio streams stay open for minutes; stream audio as it comes.
        proxy_read_timeout 1h;
        proxy_send_timeout 1h;
        proxy_buffering off;
    }
}

server {
    listen 80;
    server_name ytmp.example.com;
    return 301 https://$host$request_uri;
}
```

`$connection_upgrade` needs this once in the `http { }` block (many setups already have it,
e.g. in `/etc/nginx/nginx.conf`):

```nginx
map $http_upgrade $connection_upgrade {
    default upgrade;
    ''      close;
}
```

Get the certificate the way you do for your other sites (e.g. `certbot --nginx -d
ytmp.example.com`), then `nginx -t && systemctl reload nginx`.

nginx has to reach the container on port 8080, and must itself have a **local network
address** (as it does on the Proxmox host or in another container): YTMP only trusts
`X-Forwarded-For` from a proxy on the local network.

## 5. Start it

```sh
cd /opt/ytmp/deploy
docker compose -f docker-compose.yml -f docker-compose.caddy.yml up -d --build   # A: Caddy
docker compose up -d --build                                                     # B: nginx
```

The first build takes a few minutes (it compiles the server and the web app). Then check
(add the two `-f` files with Caddy):

```sh
docker compose logs ytmp | grep "Streaming audio"
# ... Streaming audio through the server: lan
```

Open `https://ytmp.example.com`.

- **Accounts:** go to `https://ytmp.example.com/account`. The first account you create
  becomes the server admin. After that, new accounts need an invite (`signup = "invite"`).
- **The Android app:** on the room list, **Change server** → `https://ytmp.example.com`.

## Updating

```sh
cd /opt/ytmp && git pull
cd deploy && docker compose up -d --build    # add the two -f files with Caddy
docker image prune -f
```

Rooms, accounts and history are kept in Docker volumes. Back up the whole container with
Proxmox (**Backup** → **Backup now**, or a scheduled backup job).

## Troubleshooting

- **"Can't play this song on this device" away from home:** expected with `proxy = "lan"`
  (see above). Host a room on your phone, or set `proxy = "always"` and accept the upload.
- **The domain doesn't open at home, but it works on mobile data:** your router doesn't
  support NAT loopback (hairpinning). At home, use `http://192.168.1.50:8080`, or add a
  local DNS entry for the domain pointing at your reverse proxy.
- **nginx: rooms keep saying "Connection lost, reconnecting…":** the WebSocket headers
  (`Upgrade`, `Connection`) or the `$connection_upgrade` map are missing, or
  `proxy_read_timeout` is short.
- **nginx: also visitors from outside can stream through the server:** nginx appends to
  `X-Forwarded-For` (`$proxy_add_x_forwarded_for`) instead of setting it; use
  `proxy_set_header X-Forwarded-For $remote_addr;`.
- **Songs fail with "Sign in to confirm you're not a bot":** YouTube has rate-limited your
  home IP, usually after very many songs in a short time. It passes after a few hours.
- **Docker containers won't start in the LXC:** check **nesting** and **keyctl**. If it
  still fails, a small Debian VM with the same steps (2 and on) avoids LXC's limits.
- **Logs:** `docker compose logs -f` (add the two `-f` files with Caddy).
