import os
import json
import base64
import tempfile
from typing import Optional, Dict
from cryptography.fernet import Fernet
from passlib.context import CryptContext
from app import runtime_paths

# Settings File Path (store writable data under user profile, not app bundle path)
SETTINGS_FILE = os.path.join(runtime_paths.get_user_data_dir(), "settings.json")
LEGACY_SETTINGS_FILE = os.path.join(
    os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data", "settings.json"
)

# Password Hashing Context
pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")

def _get_key_from_pin(pin: str) -> bytes:
    """Generate a valid Fernet key from a string PIN (simple hash-based approach)"""
    import hashlib
    # Sha256 gives 32 bytes, which we base64 encode to meet Fernet's 44-byte url-safe base64 requirement
    hashed = hashlib.sha256(pin.encode()).digest()
    return base64.urlsafe_b64encode(hashed)

def generate_kdf_salt() -> str:
    """Generate a random salt for KDF-based encryption."""
    return base64.urlsafe_b64encode(os.urandom(16)).decode("utf-8")

def _get_key_from_pin_kdf(pin: str, salt_b64: str, iterations: int = 390000) -> bytes:
    """Derive a Fernet key using PBKDF2-HMAC-SHA256 (v2)."""
    import hashlib
    salt = base64.urlsafe_b64decode(salt_b64.encode("utf-8"))
    derived = hashlib.pbkdf2_hmac("sha256", pin.encode("utf-8"), salt, iterations, dklen=32)
    return base64.urlsafe_b64encode(derived)

def encrypt_data(data: str, pin: str) -> str:
    """Encrypt string data using the PIN"""
    key = _get_key_from_pin(pin)
    f = Fernet(key)
    encrypted_data = f.encrypt(data.encode())
    return encrypted_data.decode()

def decrypt_data(encrypted_data: str, pin: str) -> Optional[str]:
    """Decrypt string data using the PIN"""
    try:
        key = _get_key_from_pin(pin)
        f = Fernet(key)
        decrypted_data = f.decrypt(encrypted_data.encode())
        return decrypted_data.decode()
    except Exception:
        return None

def encrypt_data_v2(data: str, pin: str, salt_b64: str) -> str:
    """Encrypt string data using PIN + PBKDF2-derived key."""
    key = _get_key_from_pin_kdf(pin, salt_b64)
    f = Fernet(key)
    encrypted_data = f.encrypt(data.encode("utf-8"))
    return encrypted_data.decode("utf-8")

def decrypt_data_v2(encrypted_data: str, pin: str, salt_b64: str) -> Optional[str]:
    """Decrypt string data using PIN + PBKDF2-derived key."""
    try:
        key = _get_key_from_pin_kdf(pin, salt_b64)
        f = Fernet(key)
        decrypted_data = f.decrypt(encrypted_data.encode("utf-8"))
        return decrypted_data.decode("utf-8")
    except Exception:
        return None

import bcrypt

def hash_pin(pin: str) -> str:
    """Hash the PIN for storage"""
    salt = bcrypt.gensalt()
    hashed = bcrypt.hashpw(pin.encode('utf-8'), salt)
    return hashed.decode('utf-8')

def verify_pin(plain_pin: str, hashed_pin: str) -> bool:
    """Verify if a plain PIN matches the hashed PIN"""
    try:
        return bcrypt.checkpw(plain_pin.encode('utf-8'), hashed_pin.encode('utf-8'))
    except Exception:
        return False

def load_settings() -> Dict:
    """Load settings from JSON file"""
    # Backward compatibility: migrate old local file if present.
    if not os.path.exists(SETTINGS_FILE) and os.path.exists(LEGACY_SETTINGS_FILE):
        try:
            with open(LEGACY_SETTINGS_FILE, "r", encoding="utf-8") as f:
                legacy = json.load(f)
            save_settings(legacy)
        except Exception:
            pass

    if os.path.exists(SETTINGS_FILE):
        try:
            with open(SETTINGS_FILE, "r", encoding="utf-8") as f:
                return json.load(f)
        except Exception:
            return {}
    return {}

def save_settings(settings: Dict) -> bool:
    """Save settings to JSON file"""
    # Ensure data directory exists
    os.makedirs(os.path.dirname(SETTINGS_FILE), exist_ok=True)
    try:
        fd, tmp_path = tempfile.mkstemp(prefix="settings_", suffix=".tmp", dir=os.path.dirname(SETTINGS_FILE))
        try:
            with os.fdopen(fd, "w", encoding="utf-8") as f:
                json.dump(settings, f, indent=4)
                f.flush()
                os.fsync(f.fileno())
            os.replace(tmp_path, SETTINGS_FILE)
        finally:
            if os.path.exists(tmp_path):
                os.remove(tmp_path)
        try:
            os.chmod(SETTINGS_FILE, 0o600)
        except OSError:
            # Windows may not fully support POSIX chmod semantics.
            pass
        return True
    except Exception:
        return False

def is_setup_complete() -> bool:
    """Check if the initial setup (API info + PIN) is complete"""
    settings = load_settings()
    return settings.get("setup_complete", False)

def delete_settings() -> bool:
    """Delete the settings file to reset API credentials"""
    ok = True
    if os.path.exists(SETTINGS_FILE):
        try:
            os.remove(SETTINGS_FILE)
        except Exception:
            ok = False
    if os.path.exists(LEGACY_SETTINGS_FILE):
        try:
            os.remove(LEGACY_SETTINGS_FILE)
        except Exception:
            ok = False
    return ok
