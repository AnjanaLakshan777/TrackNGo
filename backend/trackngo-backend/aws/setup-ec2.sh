#!/usr/bin/env bash
#
# Prepares a fresh Amazon Linux 2023 t3.micro to run the TrackNGo backend.
#
# Run once, on the instance:
#   chmod +x setup-ec2.sh && ./setup-ec2.sh
#
# Installs a JRE, creates the service user and directories, adds swap, and
# installs Caddy for HTTPS. Idempotent - safe to run again.

set -euo pipefail

APP_USER=trackngo
APP_DIR=/opt/trackngo
SWAP_FILE=/swapfile
SWAP_SIZE_MB=2048
CADDY_VERSION=2.8.4

echo "==> Updating packages"
sudo dnf update -y

# ---------------------------------------------------------------------------
# Java.
#
# The full Corretto package, not -headless: the app resizes profile pictures
# through AWT/ImageIO (Thumbnailator and Scalr), which needs java.desktop and
# fontconfig present even though it runs with -Djava.awt.headless=true.
# ---------------------------------------------------------------------------
echo "==> Installing Java 21 and font support"
sudo dnf install -y java-21-amazon-corretto fontconfig dejavu-sans-fonts

echo "==> Installing the MySQL client (for loading the schema into RDS)"
sudo dnf install -y mariadb105 || echo "    skipped - install later if needed"

# ---------------------------------------------------------------------------
# Swap.
#
# This is the step that most often decides whether the deployment survives. A
# t3.micro has 1 GB and no swap at all. A JVM running a 15-module Spring
# application will occasionally spike past what is left after the OS takes its
# share, and with no swap the kernel's OOM killer terminates the process -
# which looks like the app dying with nothing in its own logs.
# ---------------------------------------------------------------------------
if ! sudo swapon --show | grep -q "$SWAP_FILE"; then
  echo "==> Creating ${SWAP_SIZE_MB}MB swap file"
  sudo dd if=/dev/zero of="$SWAP_FILE" bs=1M count="$SWAP_SIZE_MB" status=none
  sudo chmod 600 "$SWAP_FILE"
  sudo mkswap "$SWAP_FILE"
  sudo swapon "$SWAP_FILE"
  grep -q "$SWAP_FILE" /etc/fstab || echo "$SWAP_FILE none swap sw 0 0" | sudo tee -a /etc/fstab >/dev/null
else
  echo "==> Swap already configured"
fi

# Prefer RAM, but swap rather than kill a process.
echo "vm.swappiness=10" | sudo tee /etc/sysctl.d/99-swappiness.conf >/dev/null
sudo sysctl -p /etc/sysctl.d/99-swappiness.conf >/dev/null

# ---------------------------------------------------------------------------
# Service user and layout.
#
# The app runs as an unprivileged user that cannot log in. uploads/ is a real
# directory on disk rather than the ephemeral container filesystem the Docker
# route would have used, so profile pictures survive a redeploy.
# ---------------------------------------------------------------------------
echo "==> Creating service user and directories"
id -u "$APP_USER" >/dev/null 2>&1 || sudo useradd --system --shell /sbin/nologin --home-dir "$APP_DIR" "$APP_USER"
sudo mkdir -p "$APP_DIR" "$APP_DIR/uploads"
sudo chown -R "$APP_USER:$APP_USER" "$APP_DIR"

# The env file holds secrets, so only the service user may read it.
if [ ! -f "$APP_DIR/trackngo.env" ]; then
  sudo touch "$APP_DIR/trackngo.env"
fi
sudo chown "$APP_USER:$APP_USER" "$APP_DIR/trackngo.env"
sudo chmod 600 "$APP_DIR/trackngo.env"

# The deploying SSH user needs to be able to drop a new jar in.
sudo mkdir -p "$APP_DIR/incoming"
sudo chown "$USER:$USER" "$APP_DIR/incoming"

# ---------------------------------------------------------------------------
# Caddy, for HTTPS.
#
# Installed as a static binary because Amazon Linux 2023 has no Caddy package.
# Caddy obtains and renews a Let's Encrypt certificate on its own.
# ---------------------------------------------------------------------------
if ! command -v caddy >/dev/null 2>&1; then
  echo "==> Installing Caddy ${CADDY_VERSION}"
  ARCH=$(uname -m); case "$ARCH" in x86_64) CARCH=amd64 ;; aarch64) CARCH=arm64 ;; *) CARCH=amd64 ;; esac
  curl -fsSL "https://github.com/caddyserver/caddy/releases/download/v${CADDY_VERSION}/caddy_${CADDY_VERSION}_linux_${CARCH}.tar.gz" \
    -o /tmp/caddy.tar.gz
  sudo tar -xzf /tmp/caddy.tar.gz -C /usr/local/bin caddy
  sudo chmod +x /usr/local/bin/caddy
  rm -f /tmp/caddy.tar.gz
  id -u caddy >/dev/null 2>&1 || sudo useradd --system --shell /sbin/nologin --home-dir /var/lib/caddy caddy
  sudo mkdir -p /etc/caddy /var/lib/caddy
  sudo chown caddy:caddy /var/lib/caddy
else
  echo "==> Caddy already installed"
fi

# Lets Caddy bind 80 and 443 without running as root.
sudo setcap 'cap_net_bind_service=+ep' /usr/local/bin/caddy

echo
echo "==> Done."
free -h
echo
java -version
echo
echo "Next:"
echo "  1. put your secrets in $APP_DIR/trackngo.env  (sudo nano $APP_DIR/trackngo.env)"
echo "  2. install the unit files:  sudo cp trackngo.service caddy.service /etc/systemd/system/"
echo "  3. install the Caddyfile:   sudo cp Caddyfile /etc/caddy/Caddyfile"
echo "  4. from your laptop, run aws/deploy.sh to build and upload the jar"
