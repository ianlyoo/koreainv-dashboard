"""Private bounded access logs. No URLs, headers, exceptions or response bodies."""
from __future__ import annotations

import logging
import os
import stat
import sys
from logging.handlers import RotatingFileHandler
from pathlib import Path

LOGGER_NAME = "app.relay.access"
MAX_LOG_BYTES = 1_000_000
LOG_BACKUPS = 2  # Current file plus two backups: at most three capped files.


class PrivateRotatingHandler(RotatingFileHandler):
    def _open(self):
        fd = os.open(self.baseFilename, os.O_WRONLY | os.O_APPEND | os.O_CREAT | os.O_NOFOLLOW | os.O_NONBLOCK, 0o600)
        try:
            info = os.fstat(fd)
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1:
                raise ValueError("Private relay log unavailable")
            os.fchmod(fd, 0o600)
            return os.fdopen(fd, self.mode, encoding=self.encoding, errors=self.errors)
        except Exception:
            os.close(fd)
            raise


def configure_access_log(path: Path | None = None):
    path = path or Path.home() / "Library" / "Logs" / "KoreaInvSaveTickerRelay" / "access.log"
    directory = path.parent
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    info = directory.lstat()
    if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.geteuid() or stat.S_IMODE(info.st_mode) & 0o077:
        raise ValueError("Private relay log directory required")
    for candidate in (path, *(Path(str(path) + f".{i}") for i in range(1, LOG_BACKUPS + 1))):
        if candidate.is_symlink():
            raise ValueError("Private relay log required")
        if candidate.exists():
            info = candidate.stat()
            if not stat.S_ISREG(info.st_mode) or info.st_uid != os.geteuid() or info.st_nlink != 1:
                raise ValueError("Private relay log required")
            candidate.chmod(0o600)
    handler = PrivateRotatingHandler(path, maxBytes=MAX_LOG_BYTES, backupCount=LOG_BACKUPS, encoding="utf-8")
    logger = logging.getLogger(LOGGER_NAME)
    for old in logger.handlers[:]:
        logger.removeHandler(old)
        old.close()
    formatter = logging.Formatter("%(message)s")
    handler.setFormatter(formatter)
    stderr = logging.StreamHandler(sys.stderr)
    stderr.setFormatter(formatter)
    logger.addHandler(handler)
    logger.addHandler(stderr)
    logger.setLevel(logging.INFO)
    logger.propagate = False
    # A logging failure must not print a traceback containing arbitrary values.
    logging.raiseExceptions = False
    return logger
