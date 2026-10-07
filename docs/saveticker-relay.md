# Opt-in headless SaveTicker relay

The relay lets the separately authorized ticker-research service obtain US market
data through the Mac over the tailnet. SaveTicker email/password and session
cookies stay on the Mac, in Keychain and process memory. The relay is **opt-in**;
the GUI is unchanged. No deployment, LaunchAgent installation or live provider
validation is performed by this change.

Run from a pinned source checkout and its venv with `python -m app.relay`.
The process binds **127.0.0.1:8766 only**, with no host/port configuration. Tailnet
exposure is a separate PM/operator action through a dedicated `tailscale serve`
mapping. Do not expose the Python listener on a tailnet or public interface.

## Contract and isolation

Every request requires `Authorization: Bearer <token>`. Missing, duplicate or
incorrect authorization returns 401. The token is a 256-bit random value stored
only in OS Keychain item `KoreaInvDashboard.SaveTickerRelay.v1`, account `relay`.
The same explicit OS backend rule as `CredentialStore` forbids third-party or
plaintext keyring fallback. A missing/unreadable token refuses startup. SaveTicker
email/password are read through the existing `CredentialStore`, using the app's
`insight-profile.json` profile ID to find `KoreaInvDashboard.SaveTicker.v1`.
Unsaved SaveTicker credentials produce `saveticker_configured=false`; no migration,
login or credential write is triggered at startup.

Only these GET routes exist; there are no docs/OpenAPI, static or CORS routes:

- `/v1/health`: `schema`, `status="ok"`, `saveticker_configured` only. This is a
  passive configuration check, not an upstream availability check.
- `/v1/saveticker/{ticker}`: strict uppercase US symbol `[A-Z][A-Z0-9.\-]{0,9}`;
  invalid symbols return 400. The response contains `schema`, `ticker`, `status`,
  `fetched_at`, `cache_hit`, `sections`, and the additive `section_status` map.
  `sections` is exactly as sanitized by the existing service. `section_status`
  has exactly the same keys, with only the service's own `available`, `error`,
  or `unavailable` enum values. This preserves upstream errors separately from
  null sections with no coverage. Schema stays `koreainv.saveticker-relay.v1`;
  older responses without `section_status` remain compatible with the engine.
  `fetched_at` is copied from the
  upstream service snapshot, preserving its UTC read timestamp on cache hits;
  it is never replaced with the relay response time. When disabled, cooling down
  without a cached snapshot, or failing login, no market read is established and
  `fetched_at=null`. Cached errors also report
  `cache_hit=true`. Upstream `unavailable`/partial/disabled states retain their
  status. An unexpected internal failure returns a fixed 503 unavailable envelope
  with `fetched_at=null`, since no successful upstream read is established.

Unknown paths return 404 after authentication; unsupported methods return 405.
The relay calls `SaveTickerService.fetch(ticker, "USA")` unchanged: one service,
five-minute healthy cache, bounded LRU, 60-second error/cooldown policy, serialized
singleflight and one reauthentication attempt on 401. It adds no fallback or
second cache. On shutdown it revokes its service and clears in-memory credentials
and cookies. It never imports KIS/Toss clients, account/portfolio modules, central
server, dashboard routes or AppKit; a fresh-process entrypoint regression checks
this boundary.

## Private logs

One sanitized line per request records only method, fixed path template, HTTP
status and latency. Unknown paths become `unmatched`; ticker values, query strings,
headers, exceptions and response bodies are omitted. Uvicorn access logging is off.
The process writes to stderr and a private 0600 file at
`~/Library/Logs/KoreaInvSaveTickerRelay/access.log`, in a private 0700 directory.
It rotates at 1,000,000 bytes with two backups (three files total), retaining 0600
permissions. Symlink and unsafe log-directory targets are refused. The LaunchAgent
discards stdout/stderr to `/dev/null`, so launchd cannot grow an uncapped second
log. Token utilities print only `stored` on success, never the token.

## Installation by the authorized Mac operator

Use the same logged-in Mac user as the dashboard, with their login Keychain
unlocked. Keychain ACL behavior must be vetted interactively on that Mac before
enabling unattended startup: a non-app Python executable reading the item the GUI
created may show an **allow access** prompt or be refused. This Linux/offline task
cannot establish whether the existing GUI item's ACL already permits that binary.

### Keychain prompt rule

The owner authorized (2026-10-07) exactly one Keychain grant: the pinned relay
interpreter (`$relay_checkout/.venv/bin/python`) reading
`KoreaInvDashboard.SaveTicker.v1`. When the macOS prompt names that item **and**
that interpreter path, the owner clicks **Always Allow** once, during the
foreground vetting run. The relay token item `KoreaInvDashboard.SaveTickerRelay.v1`
is created by the same interpreter and should not prompt.

Any other prompt is a stop. This includes a different item, a different
executable (Homebrew shim, system Python, a moved checkout or a recreated venv),
or a repeat prompt after Always Allow. In that case click **Deny**, then run:

```bash
launchctl bootout gui/$UID/company.koreainv.saveticker-relay
```

Report to the PM and stop installation, activation or rotation. Never edit the
ACL in **Keychain Access** or `security`, and never re-enter or re-save
credentials from the relay interpreter to work around a prompt. The same rule
applies to **Re-vet access** after moving the checkout, recreating the venv or
upgrading Python: a new interpreter path needs a new owner decision.

The LaunchAgent uses `KeepAlive={SuccessfulExit: false}` and retains a 30-second
`ThrottleInterval`. A refused start exits with code 0 after one fixed-text stderr
line (`Relay startup refused`), so launchd does not relaunch it or re-prompt.
Other unsuccessful exits retain launchd's throttled restart behavior.

Pin the release checkout and interpreter. Create the venv with `--copies`, rather
than a Homebrew shim or a symlink that resolves outside the pinned checkout:

```bash
relay_checkout=/absolute/path/to/pinned/koreainv-release
python3 -m venv --copies "$relay_checkout/.venv"
"$relay_checkout/.venv/bin/python" -m pip install -r "$relay_checkout/requirements.txt"
relay_python="$relay_checkout/.venv/bin/python"
cd "$relay_checkout"
"$relay_python" scripts/mac/relay_token.py create
```

`create` refuses an existing token; use the rotation steps below to replace it.
Keychain ACLs bind to the executable path. Recreating the venv, moving the checkout
or upgrading Python may bring back the macOS allow access prompt. **Re-vet access
under the Keychain prompt rule above**, then regenerate the plist after a
prompt-free check. Store SaveTicker credentials through
the existing dashboard settings; never put any token/password in argv, shell
variables, environment variables/dumps, source, logs or messages.

Render the template with the **resolved venv interpreter inside the pinned
release checkout**, then install only this LaunchAgent. The renderer refuses an
existing plist and never puts a credential into it:

```bash
"$relay_python" - "$relay_checkout" <<'RENDER_RELAY_AGENT'
import os
import plistlib
import sys
from pathlib import Path

checkout = Path(sys.argv[1]).resolve(strict=True)
interpreter = (checkout / ".venv/bin/python").resolve(strict=True)
if not interpreter.is_file() or not interpreter.is_relative_to(checkout):
    raise SystemExit("Use a --copies venv inside the pinned release checkout")
template = checkout / "scripts/mac/company.koreainv.saveticker-relay.plist.template"
agent = plistlib.loads(template.read_bytes())
agent["ProgramArguments"][0] = str(interpreter)
agent["WorkingDirectory"] = str(checkout)
target = Path.home() / "Library/LaunchAgents/company.koreainv.saveticker-relay.plist"
target.parent.mkdir(parents=True, exist_ok=True)
fd = os.open(target, os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW, 0o600)
with os.fdopen(fd, "wb") as output:
    plistlib.dump(agent, output)
RENDER_RELAY_AGENT
```

Before bootstrap, run the relay once **in the foreground** while watching for
Keychain prompts:

```bash
"$relay_python" -m app.relay
```

After a prompt-free startup, verify authenticated health and stop with **Ctrl-C**.
Apart from the one authorized Always Allow above, if a prompt appears or startup
is refused, follow the Keychain prompt rule and do not continue. Only after the foreground check passes, bootstrap:

```bash
launchctl bootstrap "gui/$UID" "$HOME/Library/LaunchAgents/company.koreainv.saveticker-relay.plist"
launchctl kickstart "gui/$UID/company.koreainv.saveticker-relay"
lsof -nP -iTCP -sTCP:LISTEN
```

Verify the relay listener is **127.0.0.1:8766 only**; no `*:8766`, IPv6 wildcard
or tailnet-address listener is acceptable. Validate authenticated health and a
separately authorized upstream read before exposing it. Clients must read the
token into memory and set the Authorization header there; do not use a curl header
containing a token in argv. The token export and its transfer are separate from
the SaveTicker credential, which never leaves the Mac.

After the PM approves tailnet exposure, use the dedicated HTTPS port 8766:

```bash
tailscale serve --bg --https=8766 http://127.0.0.1:8766
```

Retain the existing 6767 mapping. Apply the reviewed tailnet access policy and
confirm the dedicated mapping on the actual installed Tailscale version before
activation; no global `serve reset` is part of this procedure.

### Activation check: GUI and relay sessions

During the separately authorized activation read, run one GUI insight fetch and
one relay fetch back to back and confirm both stay logged in. If either session
is invalidated, do not use them concurrently; stop activation and report the
result to the PM. The GUI and relay have independent SaveTicker sessions on the
same account, and concurrent-session support is unverified offline.

## Token export and rotation

The one-time export takes a file **path**, never a token argument. It creates the
file with 0600 and `O_EXCL`, refusing an existing file or symlink:

```bash
"$relay_python" scripts/mac/relay_token.py export /private/approved/path/relay-token-export
```

The PM transfers this file through the separately authorized secure procedure;
never display/cat the token, insert it in a shell command, or include it in logs.
Remove the transient export copy after the authorized handoff. This worker does
not export or transfer a real token.

To rotate, stop the process first, replace the Keychain item, export to a **new**
path for the authorized client update, then restart. The process reads the token
only at startup, so restarting is required to invalidate its old bearer token:

```bash
launchctl bootout gui/$UID/company.koreainv.saveticker-relay
"$relay_python" scripts/mac/relay_token.py rotate
"$relay_python" scripts/mac/relay_token.py export /private/approved/path/new-relay-token-export
launchctl bootstrap "gui/$UID" "$HOME/Library/LaunchAgents/company.koreainv.saveticker-relay.plist"
lsof -nP -iTCP -sTCP:LISTEN
```

After the authorized token handoff, **install the new credential on Oracle and restart `ticker-research`**
using the PM's secure credential installation procedure, then confirm authenticated
relay access. Never place the new credential in argv, environment dumps or logs.

## Uninstall

```bash
launchctl bootout gui/$UID/company.koreainv.saveticker-relay
rm -f "$HOME/Library/LaunchAgents/company.koreainv.saveticker-relay.plist"
tailscale serve --https=8766 off
```

Remove only the dedicated port 8766 mapping; leave the 6767 mapping untouched.
Uninstalling the relay does not delete the GUI's SaveTicker Keychain item or the
GUI's data. No global tailnet configuration reset is required.

## Release-note wording

“Added an opt-in headless SaveTicker market-data relay for the authorized tailnet
research service. SaveTicker credentials remain on the Mac; the GUI is unchanged.”

The PM chooses the release/version after integration. `app/version.py` is unchanged.
