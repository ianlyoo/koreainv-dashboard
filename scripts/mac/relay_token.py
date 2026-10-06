"""Generate/rotate a Keychain-only relay token or make one private export."""
from __future__ import annotations

import argparse
import sys
from pathlib import Path

# Direct source-checkout execution; never import the dashboard entrypoint.
sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
from app.relay.token_store import RelayTokenError, export_token, store_token


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Relay token arguments refused\n")


def main(argv=None, *, backend_factory=None):
    parser = SafeArgumentParser(description="Manage the headless relay Keychain token")
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("create", help="Create a 256-bit token; refuse an existing item")
    commands.add_parser("rotate", help="Replace the Keychain item with a new 256-bit token")
    export = commands.add_parser("export", help="Write a single private export; refuse an existing path")
    export.add_argument("path", type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == "export":
            export_token(args.path, backend_factory=backend_factory)
        else:
            store_token(rotate=args.command == "rotate", backend_factory=backend_factory)
    except RelayTokenError:
        print("Relay token operation refused", file=sys.stderr)
        return 1
    print("stored")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
