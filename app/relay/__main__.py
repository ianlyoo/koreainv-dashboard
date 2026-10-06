"""Run headlessly from a source checkout: python -m app.relay."""
from __future__ import annotations

import argparse
import sys

import uvicorn

from app.credential_store import credential_store
from app.services.saveticker_service import SaveTickerService
from .access_log import configure_access_log
from .saveticker_relay import HOST, PORT, RelayStartupError, create_app, validate_host
from .token_store import read_token


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Relay arguments refused\n")


def run(*, host=HOST, token_reader=None, store=None, log_path=None):
    validate_host(host)
    service = None
    try:
        token = (token_reader or read_token)()
        credentials = (store or credential_store).read()
        try:
            service = SaveTickerService(**credentials) if credentials else SaveTickerService(enabled=False)
        finally:
            credentials = None
        app = create_app(token, service, host=host)
        token = None
        configure_access_log(log_path)
    except Exception:
        if service is not None:
            service.revoke()
        raise RelayStartupError() from None
    # Explicit arguments ignore Uvicorn host/port environment defaults. The
    # relay owns sanitized access logging; native URL/header logging is off.
    uvicorn.run(app, host=HOST, port=PORT, access_log=False, log_config=None, log_level="critical")


def main(argv=None):
    parser = SafeArgumentParser(description="Opt-in headless SaveTicker market-data relay")
    parser.parse_args(argv)  # No host, port, token or credential command-line flags.
    try:
        run()
    except RelayStartupError:
        print("Relay startup refused", file=sys.stderr)
        # SuccessfulExit=false restarts crashes, but never a refused Keychain
        # read. Do not let launchd repeatedly present an access prompt.
        return 0
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
