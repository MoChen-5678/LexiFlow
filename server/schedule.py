import logging
import sqlite3
import threading
import time
from contextlib import closing

DAY = 24 * 60 * 60


def reserve(path, name, now):
    """Persist the attempt before accessing the network. Restarts keep the same deadline."""
    with closing(sqlite3.connect(path, timeout=20)) as db, db:
        db.execute("CREATE TABLE IF NOT EXISTS sync_clock (name TEXT PRIMARY KEY, due INTEGER NOT NULL)")
        claimed = db.execute("""INSERT INTO sync_clock VALUES(?, ?)
            ON CONFLICT(name) DO UPDATE SET due=excluded.due WHERE sync_clock.due<=?""",
            (name, now + DAY, now)).rowcount
        due = db.execute("SELECT due FROM sync_clock WHERE name=?", (name,)).fetchone()[0]
    return bool(claimed), due


def daily(path, name, work):
    def run():
        while True:
            try:
                claimed, due = reserve(path, name, int(time.time()))
                if claimed:
                    work()
                delay = max(1, due - time.time())
            except Exception:
                logging.exception("Daily job %s failed", name)
                # A reserved attempt keeps its 24-hour deadline on the next iteration.
                delay = 10
            time.sleep(delay)
    threading.Thread(target=run, daemon=True, name=name).start()
