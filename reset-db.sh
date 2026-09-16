#!/usr/bin/env bash
#
# Wipes the TrackNGo RDS database and reloads it from the two SQL files.
# Run this ON THE EC2 INSTANCE, not from a laptop: RDS is only reachable inside
# the VPC.
#
#   scp -i ~/Downloads/trackngo-key.pem \
#       trackngo_complete.sql trackngo_sample_data.sql reset-db.sh \
#       ec2-user@3.110.117.172:~
#   ssh -i ~/Downloads/trackngo-key.pem ec2-user@3.110.117.172
#   chmod +x reset-db.sh && ./reset-db.sh
#
# THIS DELETES EVERYTHING in the trackngo database. There is no undo.

set -euo pipefail

ENV_FILE=/opt/trackngo/trackngo.env
COMPLETE=${1:-$HOME/trackngo_complete.sql}
SAMPLE=${2:-$HOME/trackngo_sample_data.sql}

[ -f "$COMPLETE" ] || { echo "Missing schema file: $COMPLETE"; exit 1; }
[ -f "$SAMPLE" ]   || { echo "Missing sample data file: $SAMPLE"; exit 1; }

echo "==> Reading database settings from $ENV_FILE"
DB_URL=$(sudo grep -E '^DB_URL=' "$ENV_FILE" | sed 's/^DB_URL=//')
DB_USER=$(sudo grep -E '^DB_USERNAME=' "$ENV_FILE" | sed 's/^DB_USERNAME=//')
DB_PASS=$(sudo grep -E '^DB_PASSWORD=' "$ENV_FILE" | sed 's/^DB_PASSWORD=//')

# jdbc:mysql://host:3306/dbname?params...
DB_HOST=$(echo "$DB_URL" | sed -E 's#^jdbc:mysql://([^:/]+).*#\1#')
DB_PORT=$(echo "$DB_URL" | sed -E 's#^jdbc:mysql://[^:/]+:([0-9]+).*#\1#')
DB_NAME=$(echo "$DB_URL" | sed -E 's#^jdbc:mysql://[^/]+/([^?]+).*#\1#')
[ "$DB_PORT" = "$DB_URL" ] && DB_PORT=3306

echo "    host = $DB_HOST"
echo "    port = $DB_PORT"
echo "    db   = $DB_NAME"
echo "    user = $DB_USER"

if ! command -v mysql >/dev/null 2>&1; then
  echo "==> Installing the MySQL client"
  sudo dnf install -y mariadb105 >/dev/null
fi

export MYSQL_PWD="$DB_PASS"
MYSQL="mysql -h $DB_HOST -P $DB_PORT -u $DB_USER"

echo "==> Checking connectivity"
$MYSQL -e "SELECT VERSION();" >/dev/null
echo "    ok"

echo
echo "About to DROP DATABASE \`$DB_NAME\` on $DB_HOST and reload it."
echo "Everything currently in it will be lost: accounts, bookings, chats, SOS alerts."
read -r -p "Type RESET to continue: " CONFIRM
[ "$CONFIRM" = "RESET" ] || { echo "Aborted."; exit 1; }

echo "==> Stopping the app so nothing writes mid-reload"
sudo systemctl stop trackngo

echo "==> Recreating the database"
$MYSQL -e "DROP DATABASE IF EXISTS \`$DB_NAME\`; CREATE DATABASE \`$DB_NAME\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;"

echo "==> Loading the schema ($(basename "$COMPLETE"))"
$MYSQL "$DB_NAME" < "$COMPLETE"

echo "==> Loading the sample data ($(basename "$SAMPLE"))"
$MYSQL "$DB_NAME" < "$SAMPLE"

echo "==> Row counts"
$MYSQL "$DB_NAME" -e "
  SELECT 'user' AS table_name, COUNT(*) AS rows_loaded FROM \`user\`
  UNION ALL SELECT 'bus', COUNT(*) FROM bus
  UNION ALL SELECT 'route', COUNT(*) FROM route
  UNION ALL SELECT 'seat_booking', COUNT(*) FROM seat_booking
  UNION ALL SELECT 'trip_booking', COUNT(*) FROM trip_booking
  UNION ALL SELECT 'notification', COUNT(*) FROM notification;"

echo "==> Starting the app (its startup tasks rebuild ratings, registration_otp and the AI tables)"
sudo systemctl start trackngo

echo "==> Waiting for it to report healthy"
for i in $(seq 1 60); do
  if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
    echo "    healthy after ${i}0s"
    curl -s http://localhost:8080/actuator/health; echo
    echo
    echo "Done. Every seeded account uses the password Test@1234."
    exit 0
  fi
  sleep 10
done

echo "The app did not come back. Check: sudo journalctl -u trackngo -n 60"
exit 1
