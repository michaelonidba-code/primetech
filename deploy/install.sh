#!/usr/bin/env bash
# One-command HTTPS deployment on a fresh Ubuntu or Debian server. See deploy/DEPLOY.md.
#
#   sudo ./deploy/install.sh <domain> [user]
#
# Installs Docker if needed, adds swap on small machines, checks the domain points at this
# server, opens ports 80/443 if a ufw firewall is on, creates deploy/.env with fresh secrets and
# a sign-in user (first run only), then builds and starts the stack behind Caddy, which gets a
# Let's Encrypt certificate for <domain>. Safe to re-run: it keeps deploy/.env and redeploys,
# which is also how to update after `git pull`.
#
# Environment: SKIP_DNS_CHECK=1 skips the DNS check (for example behind a proxy or CDN).
#              NO_BUILD=1 uses images already built or loaded on this host instead of building.
set -euo pipefail

DOMAIN="${1:-}"
DASH_USER="${2:-admin}"
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="$ROOT/deploy/.env"
PASSWORD_FILE="$ROOT/deploy/.admin-password"
COMPOSE=(docker compose -f "$ROOT/docker-compose.yml" -f "$ROOT/deploy/docker-compose.prod.yml" --env-file "$ENV_FILE")

say() { printf '\n\033[1m==> %s\033[0m\n' "$*"; }
die() { printf '\n\033[31mError:\033[0m %s\n' "$*" >&2; exit 1; }

[[ -n "$DOMAIN" ]] || die "usage: sudo $0 <domain> [user]   e.g. sudo $0 ads.example.com"
[[ "$DOMAIN" =~ ^[A-Za-z0-9.-]+$ ]] || die "'$DOMAIN' is not a valid domain name"
[[ "$DASH_USER" =~ ^[A-Za-z0-9._-]{1,64}$ ]] || die "user names may use letters, digits, . _ -"
[[ $EUID -eq 0 ]] || die "run as root: sudo $0 $*"
cd "$ROOT"

# --- Docker -----------------------------------------------------------------------------
if ! command -v docker >/dev/null || ! docker compose version >/dev/null 2>&1; then
  say "Installing Docker"
  command -v curl >/dev/null || { apt-get update -qq && apt-get install -y -qq curl; }
  curl -fsSL https://get.docker.com | sh
fi
systemctl enable --now docker >/dev/null 2>&1 || true
docker info >/dev/null 2>&1 || die "Docker is installed but not running"

# --- Memory -----------------------------------------------------------------------------
# The stack needs about 10 GB. Below 14 GB of memory plus swap, add an 8 GB swap file.
mem_kb=$(awk '/MemTotal/ {print $2}' /proc/meminfo)
swap_kb=$(awk '/SwapTotal/ {print $2}' /proc/meminfo)
if (( (mem_kb + swap_kb) < 14 * 1024 * 1024 )) && [[ ! -f /swapfile-ads ]]; then
  say "Adding an 8 GB swap file ($(( mem_kb / 1024 / 1024 )) GB of memory found; 16 GB recommended)"
  fallocate -l 8G /swapfile-ads && chmod 600 /swapfile-ads && mkswap -q /swapfile-ads && swapon /swapfile-ads
  grep -q swapfile-ads /etc/fstab || echo '/swapfile-ads none swap sw 0 0' >> /etc/fstab
fi

# --- DNS --------------------------------------------------------------------------------
# Caddy can only get a certificate once the domain points here; failed attempts count against
# Let's Encrypt rate limits, so check first.
if [[ "${SKIP_DNS_CHECK:-0}" != 1 && "$DOMAIN" != localhost ]]; then
  say "Checking that $DOMAIN points at this server"
  public_ip=$(curl -fs4 --max-time 10 https://api.ipify.org || curl -fs4 --max-time 10 https://ifconfig.me || true)
  resolved=$(getent ahostsv4 "$DOMAIN" | awk '{print $1}' | sort -u | tr '\n' ' ' || true)
  resolved=${resolved% }
  [[ -n "$resolved" ]] || die "$DOMAIN does not resolve yet. Add a DNS A record for it pointing at ${public_ip:-the IP address of this server}, wait a few minutes, and re-run."
  [[ -n "$public_ip" ]] || die "could not find this server's public IP to compare with $DOMAIN ($resolved). If you are sure the A record points here, re-run with SKIP_DNS_CHECK=1."
  if [[ " $resolved " != *" $public_ip "* ]]; then
    die "$DOMAIN points at $resolved but this server is $public_ip. Fix the DNS A record (or set SKIP_DNS_CHECK=1 if a proxy sits in front) and re-run."
  fi
  echo "ok: $DOMAIN -> $resolved"
fi

# --- Firewall ---------------------------------------------------------------------------
if command -v ufw >/dev/null && ufw status | grep -q '^Status: active'; then
  say "Opening ports 80 and 443 in ufw"
  ufw allow 80/tcp >/dev/null && ufw allow 443/tcp >/dev/null && ufw allow 443/udp >/dev/null
fi

# --- Secrets and sign-in user (first run only) ------------------------------------------
rand() { openssl rand -base64 "$1" | tr -d '\n=+/' | cut -c1-"$2"; }
b64url() { base64 -w0 | tr '+/' '-_' | tr -d '='; }

if [[ ! -f "$ENV_FILE" ]]; then
  if [[ "${NO_BUILD:-0}" != 1 ]]; then
    say "Building the dashboard image (needed to hash the password)"
    docker compose -f "$ROOT/docker-compose.yml" build dashboard
  fi

  say "Creating deploy/.env with new secrets and user '$DASH_USER'"
  password=$(rand 32 24)
  user_line=$(printf '%s\n' "$password" | docker compose -f "$ROOT/docker-compose.yml" run --rm --no-deps -T dashboard \
    node --experimental-strip-types --no-warnings tools/scripts/hash-password.ts "$DASH_USER" | grep "^$DASH_USER=")
  [[ -n "$user_line" ]] || die "could not hash the password"

  # Web Push (VAPID) key pair on P-256: raw private scalar and uncompressed public point, base64url.
  key=$(mktemp)
  openssl ecparam -name prime256v1 -genkey -noout -out "$key"
  vapid_private=$(openssl ec -in "$key" -outform DER 2>/dev/null | tail -c +8 | head -c 32 | b64url)
  vapid_public=$(openssl ec -in "$key" -pubout -outform DER 2>/dev/null | tail -c 65 | b64url)
  rm -f "$key"

  umask 077
  cat > "$ENV_FILE" <<EOF
# Created by deploy/install.sh on $(date -u +%F). Keep this file private; it is git-ignored.
DASHBOARD_DOMAIN=$DOMAIN
DASHBOARD_USERS=$user_line
DASHBOARD_SESSION_SECRET=$(rand 64 64)
ALERT_WEBHOOK_TOKEN=$(rand 48 40)
GRAFANA_ADMIN_PASSWORD=$(rand 32 24)
VAPID_PUBLIC_KEY=$vapid_public
VAPID_PRIVATE_KEY=$vapid_private
EOF
  printf '%s\n' "$password" > "$PASSWORD_FILE"
  umask 022
else
  say "Keeping existing deploy/.env"
  current=$(grep '^DASHBOARD_DOMAIN=' "$ENV_FILE" | cut -d= -f2-)
  if [[ "$current" != "$DOMAIN" ]]; then
    sed -i "s|^DASHBOARD_DOMAIN=.*|DASHBOARD_DOMAIN=$DOMAIN|" "$ENV_FILE"
    echo "domain changed: $current -> $DOMAIN"
  fi
fi

# Pseudonymises device ids in the gateway and ad server. Added to older .env files that lack it.
if ! grep -q '^DEVICE_ID_SECRET=' "$ENV_FILE"; then
  echo "DEVICE_ID_SECRET=$(rand 64 64)" >> "$ENV_FILE"
  echo "added DEVICE_ID_SECRET to deploy/.env"
fi

# --- Start ------------------------------------------------------------------------------
if [[ "${NO_BUILD:-0}" == 1 ]]; then
  say "Starting the stack"
  "${COMPOSE[@]}" up -d --no-build
else
  say "Building and starting the stack (the first build takes 10-20 minutes)"
  "${COMPOSE[@]}" up -d --build
fi

# The dashboard (user node) writes the sponsor catalog and videos into shared volumes.
"${COMPOSE[@]}" exec -T -u root dashboard chown node:node /app/catalog /app/media >/dev/null 2>&1 || true

say "Waiting for https://$DOMAIN to answer"
curl_opts=(-fsS --max-time 10)
[[ "$DOMAIN" == localhost ]] && curl_opts+=(-k)
for _ in $(seq 1 60); do
  if curl "${curl_opts[@]}" "https://$DOMAIN/api/health" >/dev/null 2>&1; then
    printf '\n\033[32mDeployed.\033[0m Open https://%s\n' "$DOMAIN"
    if [[ -f "$PASSWORD_FILE" ]]; then
      printf '  user:      %s\n  password:  %s\n' "$(grep '^DASHBOARD_USERS=' "$ENV_FILE" | cut -d= -f2 | cut -d= -f1)" "$(cat "$PASSWORD_FILE")"
      printf '  (saved in deploy/.admin-password, readable by root only)\n'
    fi
    exit 0
  fi
  sleep 10
done
die "https://$DOMAIN did not answer within 10 minutes. Check: ${COMPOSE[*]} logs caddy dashboard"
