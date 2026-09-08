# Deploying TrackNGo on the AWS Free Tier — A to Z

Every step, in order, from a fresh AWS account to a running HTTPS backend.

**What you end up with**

```
  Mobile apps ──HTTPS──> Caddy (:443)  ──> Spring Boot (:8080)  ──> RDS MySQL
                         └─ auto TLS       └─ systemd service       └─ private,
                            Let's Encrypt      runs as `trackngo`       same region
                    ─────────── one EC2 t3.micro ───────────
```

**Why no Docker.** You have Maven and Java locally but not Docker or the AWS
CLI, the fat jar is 100 MB (ECR's free tier is 500 MB — barely one image), and
the Docker daemon would eat ~150 MB of the instance's 1 GB. Building the jar
locally and running it under systemd is simpler, needs no extra tooling, and
leaves noticeably more RAM for the JVM.

**Time:** about 60–90 minutes the first time. Redeploys after that are one
command.

---

## What the Free Tier covers

| Service | Free allowance | What we use |
|---|---|---|
| EC2 `t3.micro` | 750 h/month for 12 months | One instance, running full time |
| RDS `db.t3.micro` | 750 h/month, 20 GB | One MySQL 8 instance |
| Elastic IP | Free **while attached to a running instance** | One |
| Data transfer out | 100 GB/month | Well within it |

**Do not use these — they bill from the first hour:** Application Load Balancer,
ECS Fargate, App Runner, NAT Gateway. Nothing below needs them.

> Accounts created from mid-2025 get a credit-based plan instead of the classic
> 12-month allowance. Check **Billing → Free Tier** in the console before you
> start. Either way, do Step 1.

---

## Step 1 — Billing alarm (do this first)

1. Console → search **Billing and Cost Management** → **Budgets** → **Create budget**
2. Choose **Zero spend budget** (a template)
3. Put your email in → **Create budget**

This emails you the moment anything costs money. It is the difference between
noticing on day two and noticing on the invoice.

---

## Step 2 — Choose a region and stay in it

Use **`ap-south-1` (Mumbai)** — the closest region to Sri Lanka.

Set it in the console's top-right region picker **now**, and check it every time
you log in. Resources created in the wrong region are invisible from the right
one, and this is the single most common way to end up with a second instance you
forgot to delete.

> **EC2 and RDS must be in the same region.** This is the largest performance
> factor in the whole deployment. A query to a database in another region costs
> 150–250 ms of network time, and one API call makes several. Same region is
> 1–3 ms. It is free — it just requires not getting it wrong.

---

## Step 3 — Security groups

Create these **before** the instances, so you can attach them during creation.

**EC2 → Security Groups → Create security group**

**a) `trackngo-app-sg`** — for the EC2 instance

| Type | Port | Source | Why |
|---|---|---|---|
| SSH | 22 | **My IP** | Your laptop only. Never `0.0.0.0/0`. |
| HTTP | 80 | `0.0.0.0/0` | Let's Encrypt validates over port 80 |
| HTTPS | 443 | `0.0.0.0/0` | The apps |

**b) `trackngo-db-sg`** — for RDS

| Type | Port | Source |
|---|---|---|
| MySQL/Aurora | 3306 | **`trackngo-app-sg`** |

For the source, start typing `trackngo-app-sg` and pick the group itself, not an
IP range. That means "only the app instance may reach the database", and it
keeps working if the instance's address ever changes.

**Never open 3306 to `0.0.0.0/0`.** An internet-facing MySQL is found by
scanners within hours.

---

## Step 4 — RDS MySQL

**RDS → Create database**

- **Standard create**, engine **MySQL**, version **8.0.x**
- Templates: **Free tier**
- DB instance identifier: `trackngo-db`
- Master username: `admin`, and set a strong password — **write it down now**
- Instance: `db.t3.micro`
- Storage: 20 GB gp3, and **turn off storage autoscaling** (it can silently grow
  past the free allowance)
- **Public access: No**
- VPC security group: **Choose existing** → `trackngo-db-sg`, and remove `default`
- Additional configuration → **Initial database name: `trackngo`** ← easy to miss,
  and without it there is no database to connect to
- Backups: 7 days is fine and within the allowance

Creation takes 5–10 minutes. When it finishes, copy the **Endpoint** from the
Connectivity tab — it looks like
`trackngo-db.abc123.ap-south-1.rds.amazonaws.com`.

---

## Step 5 — EC2 instance

**EC2 → Launch instance**

- Name: `trackngo-backend`
- AMI: **Amazon Linux 2023**
- Instance type: **t3.micro** (look for "Free tier eligible")
- Key pair: **Create new**, name `trackngo`, type **RSA**, format **.pem**.
  It downloads once — losing it means losing SSH access.
- Network: **same VPC as RDS**. Firewall: **Select existing** → `trackngo-app-sg`
- Storage: 8 GB gp3 is enough (30 GB is the free limit)
- **Launch instance**

Then give it a fixed address, so it survives a stop/start:

**EC2 → Elastic IPs → Allocate Elastic IP address** → Allocate → select it →
**Actions → Associate** → choose your instance → Associate.

Write the Elastic IP down. Everything below calls it `<EIP>`.

> An Elastic IP is free **while attached to a running instance**. If you later
> stop the instance and leave the IP allocated, it starts costing. Release it if
> you tear things down.

---

## Step 6 — Connect

On Windows, use **Git Bash** (you already have it).

```bash
chmod 400 ~/Downloads/trackngo.pem      # ssh refuses a world-readable key
ssh -i ~/Downloads/trackngo.pem ec2-user@<EIP>
```

Type `yes` at the host-key prompt. If it hangs, port 22 isn't open to your
current IP — go back and fix the `My IP` rule in `trackngo-app-sg` (it changes
when your network does).

---

## Step 7 — Prepare the instance

From your **laptop**, in a second Git Bash tab:

```bash
cd "D:/L2S2/Software Development Project/TrackNGo/backend/trackngo-backend"
scp -i ~/Downloads/trackngo.pem \
    aws/setup-ec2.sh aws/trackngo.service aws/caddy.service aws/Caddyfile aws/trackngo.env.example \
    ec2-user@<EIP>:~
```

Back on the **instance**:

```bash
chmod +x setup-ec2.sh && ./setup-ec2.sh
```

This installs Java 21, the MySQL client, **2 GB of swap**, the `trackngo`
service user and `/opt/trackngo`, and Caddy. Takes a few minutes.

> The swap matters more than it sounds. A t3.micro has 1 GB and none by
> default. A JVM this size occasionally spikes past what is left after the OS,
> and with no swap the kernel kills it — which looks like the app dying with
> nothing in its own logs.

---

## Step 8 — Load the database schema

Still on the instance:

```bash
# Copy the two SQL files up from your laptop first, in the laptop tab:
#   cd "D:/L2S2/Software Development Project/TrackNGo"
#   scp -i ~/Downloads/trackngo.pem trackngo_complete.sql trackngo_sample_data.sql ec2-user@<EIP>:~

mysql -h <rds-endpoint> -u admin -p trackngo < trackngo_complete.sql
mysql -h <rds-endpoint> -u admin -p trackngo < trackngo_sample_data.sql

# Check it landed
mysql -h <rds-endpoint> -u admin -p trackngo -e "SHOW TABLES;" | head
```

You should see 55 tables. If it hangs instead, `trackngo-db-sg` isn't allowing
`trackngo-app-sg` on 3306.

---

## Step 9 — Configuration and secrets

On the instance:

```bash
sudo cp trackngo.env.example /opt/trackngo/trackngo.env
sudo nano /opt/trackngo/trackngo.env
```

Fill in at minimum `DB_URL` (your RDS endpoint), `DB_PASSWORD`, `JWT_SECRET`,
and the two Stripe keys. Generate a JWT secret with:

```bash
openssl rand -base64 48
```

Then lock the file down — it holds your database password:

```bash
sudo chown trackngo:trackngo /opt/trackngo/trackngo.env
sudo chmod 600 /opt/trackngo/trackngo.env
```

**It is a systemd EnvironmentFile, not a shell script:** no `export`, no
`${VAR}` expansion, and quotes are taken literally. Write every value out in
full.

---

## Step 10 — HTTPS

The mobile apps need real HTTPS — Android blocks cleartext and iOS ATS blocks
plain HTTP by default.

**No domain?** Use **nip.io**, which resolves `<dashed-ip>.nip.io` to that IP.
Let's Encrypt issues genuine certificates for those names, so you get real HTTPS
without buying anything. For `13.234.56.78`, the hostname is
`13-234-56-78.nip.io`.

On the instance:

```bash
sudo mkdir -p /etc/caddy
sudo cp Caddyfile /etc/caddy/Caddyfile
echo "DOMAIN=13-234-56-78.nip.io" | sudo tee /etc/caddy/caddy.env    # your value

sudo cp trackngo.service caddy.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now caddy
sudo systemctl status caddy --no-pager
```

Caddy will fail to get a certificate until port 80 is reachable and DNS points
at the instance — both already true if you followed Steps 3 and 5.

If you own a real domain instead, point an **A record** at `<EIP>` and use that
as `DOMAIN`.

---

## Step 11 — Build and deploy

From your **laptop**:

```bash
cd "D:/L2S2/Software Development Project/TrackNGo/backend/trackngo-backend"
export EC2_HOST=<EIP>
export EC2_KEY=~/Downloads/trackngo.pem
./aws/deploy.sh
```

The script builds the reactor (running the full test suite), uploads the jar to
a staging path, swaps it into place, restarts the service, and polls
`/actuator/health` until it answers — printing the last 60 log lines if it
doesn't.

First run also needs the service enabled on the instance:

```bash
sudo systemctl enable --now trackngo
```

**Build on your laptop, never on the instance.** A 15-module Maven reactor on
one vCPU and 1 GB will crawl or be killed, and it would starve the running app.

---

## Step 12 — Verify

```bash
curl https://13-234-56-78.nip.io/health
curl https://13-234-56-78.nip.io/actuator/health
```

Both should answer. `/actuator/health` is the only public actuator endpoint;
everything else requires authentication.

Then check the things that were slow before:

```bash
sudo journalctl -u trackngo -n 50 --no-pager        # startup log
free -h                                             # swap in use?
curl -s localhost:8080/actuator/health              # from the instance itself
```

---

## Step 13 — Point the apps at it

In `frontend/mobile-app/TrackNgo-Mobile/.env` and the driver app's, set the API
base URL to `https://13-234-56-78.nip.io`, then rebuild the apps. The admin
dashboard's domain also needs to be in `FRONTEND_URL` on the instance, or the
browser will be refused by CORS.

---

## Running it day to day

```bash
# Redeploy after a code change — from the laptop
./aws/deploy.sh

# Logs, live
sudo journalctl -u trackngo -f

# Restart / stop / start
sudo systemctl restart trackngo
sudo systemctl status trackngo --no-pager

# Change configuration, then restart to pick it up
sudo nano /opt/trackngo/trackngo.env && sudo systemctl restart trackngo
```

Every tuning value in `trackngo.env` takes effect on restart — no rebuild. The
rollback table in `BACKEND_PERFORMANCE_GUIDE.md` lists what each one undoes.

---

## When something goes wrong

| Symptom | Cause | Fix |
|---|---|---|
| `ssh` hangs | Your IP changed | Update the `My IP` rule in `trackngo-app-sg` |
| `mysql` hangs | DB security group | `trackngo-db-sg` must allow 3306 from `trackngo-app-sg` |
| App exits on start, log says "Communications link failure" | Wrong `DB_URL`, wrong password, or security group | Check the endpoint and try `mysql -h ... -u admin -p` |
| App exits, "Unable to determine Dialect" | Same — it could not reach the database at all | As above |
| Killed with nothing in the app log | Out of memory | `free -h` — swap should show 2 GB. Then lower `-Xmx` in `trackngo.service` |
| Caddy won't get a certificate | Port 80 blocked, or DNS not resolving | `curl http://<EIP>` from your laptop; check `DOMAIN` |
| Chat/live map won't connect | `FRONTEND_URL` missing the dashboard origin (browser only) | Add it and restart |
| Deploy says unhealthy | Read the 60 log lines it prints | Usually configuration, not code |

Anything else: `sudo journalctl -u trackngo -n 200 --no-pager` almost always
names the cause on the first line of the stack trace.

---

## Cost guardrails

- The billing alarm from Step 1 is your safety net — don't skip it.
- **Stop, don't terminate**, if you want to pause. Terminating destroys the
  instance; stopping keeps it (though a stopped instance with an Elastic IP
  attached starts charging for the IP).
- Check **Billing → Free Tier** monthly for how much of the 750 hours you've
  used. One instance running continuously is ~730 h/month — right at the limit,
  so do not run two.

**To tear it all down:** terminate the EC2 instance, delete the RDS instance
(uncheck "create final snapshot" if you don't want it), **release the Elastic
IP**, and delete the two security groups. In that order — the security groups
won't delete while something still uses them.

---

## The files here

| File | Where it goes | What it does |
|---|---|---|
| `setup-ec2.sh` | run once on the instance | Java, swap, service user, Caddy |
| `trackngo.service` | `/etc/systemd/system/` | Runs the app, JVM sized for 1 GB |
| `caddy.service` | `/etc/systemd/system/` | Runs Caddy |
| `Caddyfile` | `/etc/caddy/` | HTTPS + reverse proxy + WebSocket passthrough |
| `trackngo.env.example` | copy to `/opt/trackngo/trackngo.env` | Secrets and tuning |
| `deploy.sh` | run from your laptop | Build, upload, restart, health-check |
