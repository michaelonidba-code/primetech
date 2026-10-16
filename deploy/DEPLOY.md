# Deploying with HTTPS

This puts the whole platform on one server, with the dashboard app at `https://<your domain>`
behind sign-in. Caddy gets and renews the HTTPS certificate from Let's Encrypt automatically.
Only ports 80 and 443 are open to the internet; everything else stays on the server's internal
Docker network.

It takes about 30 minutes, most of it waiting for the first build.

## 1. Get a server

Any cloud provider works (Hetzner, DigitalOcean, AWS Lightsail, Linode, Vultr, …). Choose:

| | |
|---|---|
| System | **Ubuntu 24.04** (or Debian 12) |
| Memory | **16 GB** recommended. 8 GB works: the script adds swap, but it runs slower |
| CPU | 4 vCPU or more |
| Disk | 80 GB or more |

When you create it, add your SSH key (or note the root password the provider gives you), and
write down the server's **public IPv4 address**.

## 2. Point your domain at it

Where your domain's DNS is managed (your registrar, or Cloudflare), add a record:

| Type | Name | Value |
|---|---|---|
| `A` | `ads` (for `ads.yourdomain.com`), or `@` for the bare domain | the server's IPv4 address |

On Cloudflare, set the record to **DNS only** (grey cloud) so Let's Encrypt can reach the server.
DNS usually updates within minutes; you can check with `ping ads.yourdomain.com`.

## 3. Copy the code onto the server

```bash
ssh root@<server IP>
git clone https://github.com/michaelonidba-code/primetech.git
cd primetech
```

If the repository is private, GitHub asks for a user name and password. Use your GitHub user
name and a **personal access token** with read access to the repository as the password
(GitHub → Settings → Developer settings → Personal access tokens → Fine-grained tokens).

## 4. Run the installer

```bash
sudo ./deploy/install.sh ads.yourdomain.com
```

It installs Docker, adds swap if the server has less than 16 GB, checks that the domain points at
this server, opens ports 80 and 443 if the firewall is on, creates all secrets and a sign-in user
called `admin`, builds everything and starts it. At the end it prints:

```
Deployed. Open https://ads.yourdomain.com
  user:      admin
  password:  <generated password>
```

**Save the password.** It is also kept in `deploy/.admin-password` on the server (root only).
To use a different user name: `sudo ./deploy/install.sh ads.yourdomain.com alice`.

## 5. Install the app

Open `https://ads.yourdomain.com`, sign in, then:

* **Windows, macOS, Linux (Chrome or Edge) and Android:** press **Install app** in the header.
* **iPhone and iPad:** in Safari, tap **Share → Add to Home Screen**.

To get alerts as notifications, press **Turn on notifications** in the Alerts panel on each device.

## Day to day

| Task | Command (in `primetech/` on the server) |
|---|---|
| Update to the latest code | `git pull && sudo ./deploy/install.sh ads.yourdomain.com` |
| See what is running | `docker compose ps` |
| Read logs | `docker compose logs -f --tail 100 dashboard caddy` |
| Stop everything | `docker compose -f docker-compose.yml -f deploy/docker-compose.prod.yml --env-file deploy/.env down` |
| Restart after a stop | `sudo ./deploy/install.sh ads.yourdomain.com` |

### Add a user or change a password

```bash
docker compose run --rm --no-deps dashboard node --experimental-strip-types tools/scripts/hash-password.ts bob
```

Type the password (at least 12 characters). Add the printed `bob=scrypt…` line to
`DASHBOARD_USERS` in `deploy/.env`, separated from the others by a comma, then re-run the
installer. Replacing a user's line changes their password and signs them out.

### Open Grafana

Grafana is not public. On the server, start a small relay that exposes it on the server's own
loopback address only:

```bash
docker run -d --rm --name grafana-relay --network ads-eventing_default -p 127.0.0.1:3001:3000 \
  alpine/socat TCP-LISTEN:3000,fork,reuseaddr TCP:grafana:3000
```

Then, from your own computer, open an SSH tunnel and browse to `http://localhost:3001`
(user `admin`, password `GRAFANA_ADMIN_PASSWORD` from `deploy/.env`):

```bash
ssh -L 3001:127.0.0.1:3001 root@<server IP>
```

Stop the relay when done: `docker stop grafana-relay`.

## If something goes wrong

| Message | Fix |
|---|---|
| `does not resolve yet` / `points at … but this server is …` | Fix the DNS `A` record (step 2), wait a few minutes, re-run. Behind a proxy or CDN on purpose? Run with `SKIP_DNS_CHECK=1` |
| `did not answer within 10 minutes` | Run `docker compose logs caddy`. Certificate errors usually mean port 80 or 443 is blocked by the provider's firewall: open them in the provider's control panel |
| The site loads but panels say a service is unreachable | Druid and Flink take a few minutes to start after a reboot; wait, then reload |
| Out of memory, or containers restarting | Use a 16 GB server |

`deploy/.env` holds every secret. It is never committed; back it up somewhere private.
