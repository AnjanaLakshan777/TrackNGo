#!/usr/bin/env bash
#
# Builds the backend on your machine and deploys it to the EC2 instance.
#
#   export EC2_HOST=13.234.56.78          # the Elastic IP
#   export EC2_KEY=~/keys/trackngo.pem    # the .pem you downloaded from AWS
#   ./aws/deploy.sh
#
# Optional:
#   EC2_USER=ec2-user   (default)
#   SKIP_BUILD=1        re-upload the existing jar without rebuilding
#
# Run this from your laptop, never on the instance: a 15-module Maven reactor
# needs far more than one vCPU and 1 GB, and building there would starve the
# running app of the memory it needs to serve traffic.

set -euo pipefail

EC2_HOST="${EC2_HOST:?set EC2_HOST to the Elastic IP of the instance}"
EC2_KEY="${EC2_KEY:?set EC2_KEY to the path of your .pem key}"
EC2_USER="${EC2_USER:-ec2-user}"

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$ROOT_DIR/app/target/app-1.0.0-SNAPSHOT.jar"
SSH="ssh -i $EC2_KEY -o StrictHostKeyChecking=accept-new $EC2_USER@$EC2_HOST"

if [ "${SKIP_BUILD:-0}" != "1" ]; then
  echo "==> Building (this runs the full test suite; set SKIP_BUILD=1 to skip)"
  ( cd "$ROOT_DIR" && mvn -B clean install -T 1C )
fi

[ -f "$JAR" ] || { echo "No jar at $JAR - build failed?"; exit 1; }
echo "==> Built $(du -h "$JAR" | cut -f1) jar"

# Uploaded to a staging path first, then moved into place by the remote step.
# Copying straight onto app.jar would truncate the file the running service is
# executing from, which is a good way to get a confusing crash mid-deploy.
echo "==> Uploading to $EC2_HOST"
scp -i "$EC2_KEY" -o StrictHostKeyChecking=accept-new \
    "$JAR" "$EC2_USER@$EC2_HOST:/opt/trackngo/incoming/app.jar"

echo "==> Swapping in the new jar and restarting"
$SSH 'sudo install -o trackngo -g trackngo -m 644 /opt/trackngo/incoming/app.jar /opt/trackngo/app.jar \
      && sudo systemctl restart trackngo'

# The health endpoint is the app telling us it finished starting. A cold JVM on
# one vCPU takes a while, so poll rather than checking once and giving up.
echo "==> Waiting for the app to report healthy"
if $SSH 'for i in $(seq 1 60); do
           if curl -fsS http://localhost:8080/actuator/health >/dev/null 2>&1; then
             echo "healthy after ${i}0s"; exit 0
           fi
           sleep 10
         done
         exit 1'; then
  echo
  echo "==> Deployed."
  $SSH 'curl -s http://localhost:8080/actuator/health; echo'
else
  echo
  echo "!! The app did not come up. Recent logs:"
  $SSH 'sudo journalctl -u trackngo -n 60 --no-pager'
  exit 1
fi
