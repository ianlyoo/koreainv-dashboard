"""Release-runner probe of the native OS store using a fresh synthetic item only."""
from pathlib import Path
import json
import sys
from tempfile import TemporaryDirectory

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.credential_store import CredentialStore


def main():
    result = {"platform": sys.platform, "verified": False}
    try:
        with TemporaryDirectory(prefix="koreainv-store-check-") as directory:
            store = CredentialStore(metadata_path=Path(directory) / "profile.json")
            try:
                expected = {"email": "release-check@example.invalid", "password": "synthetic-check-only"}
                store.write(**expected)
                if store.read() != expected:
                    raise RuntimeError("Roundtrip mismatch")
                store.delete()
                if store.read() is not None:
                    raise RuntimeError("Deletion incomplete")
                result["verified"] = True
            finally:
                store.delete()
    except Exception as error:
        result["verified"] = False
        result["error_type"] = type(error).__name__
    print(json.dumps(result))
    return 0 if result["verified"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
