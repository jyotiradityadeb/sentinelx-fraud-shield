from __future__ import annotations

import datetime as dt
import fnmatch
import json
import threading
import uuid
from pathlib import Path
from typing import Any

from supabase import Client, create_client

import config


class _DummyResult:
    def __init__(self, data=None, count: int | None = 0) -> None:
        self.data = data or []
        self.count = count


class _DummyTable:
    def __init__(self, store: "_LocalStore", name: str) -> None:
        self._store = store
        self._name = name
        self._action = "select"
        self._payload: Any = None
        self._filters: list[tuple[str, str, Any]] = []
        self._limit: int | None = None
        self._order: tuple[str, bool] | None = None
        self._count_exact = False

    def select(self, *_args, **kwargs):
        self._action = "select"
        self._count_exact = kwargs.get("count") == "exact"
        return self

    def limit(self, value: int):
        self._limit = int(value)
        return self

    def eq(self, column: str, value: Any):
        self._filters.append(("eq", column, value))
        return self

    def like(self, column: str, pattern: str):
        self._filters.append(("like", column, pattern))
        return self

    def order(self, column: str, desc: bool = False):
        self._order = (column, bool(desc))
        return self

    def update(self, payload: dict[str, Any]):
        self._action = "update"
        self._payload = payload
        return self

    def insert(self, payload: dict[str, Any] | list[dict[str, Any]]):
        self._action = "insert"
        self._payload = payload
        return self

    def delete(self):
        self._action = "delete"
        return self

    def execute(self):
        if self._action == "insert":
            rows = self._store.insert(self._name, self._payload)
            return _DummyResult(data=rows, count=len(rows))
        if self._action == "update":
            rows = self._store.update(self._name, self._filters, self._payload)
            return _DummyResult(data=rows, count=len(rows))
        if self._action == "delete":
            rows = self._store.delete(self._name, self._filters)
            return _DummyResult(data=rows, count=len(rows))

        rows = self._store.select(self._name, self._filters, self._order, self._limit)
        count = len(rows) if self._count_exact else None
        return _DummyResult(data=rows, count=count)


class _DummyClient:
    def __init__(self, store: "_LocalStore | None" = None) -> None:
        self._store = store

    def table(self, *args, **kwargs):
        name = str(args[0] if args else kwargs.get("name", ""))
        if self._store is None:
            raise RuntimeError("Local store is not configured")
        return _DummyTable(self._store, name)


class _LocalStore:
    def __init__(self, db_path: Path) -> None:
        self._db_path = db_path
        self._lock = threading.Lock()
        self._data: dict[str, list[dict[str, Any]]] = {}
        self._load()

    def _load(self) -> None:
        if self._db_path.exists():
            try:
                self._data = json.loads(self._db_path.read_text(encoding="utf-8"))
            except Exception:
                self._data = {}
        for table in ("sessions", "events", "threat_network", "guardians", "blocklist_numbers"):
            self._data.setdefault(table, [])

    def _save(self) -> None:
        self._db_path.parent.mkdir(parents=True, exist_ok=True)
        self._db_path.write_text(json.dumps(self._data, ensure_ascii=False, indent=2), encoding="utf-8")

    @staticmethod
    def _apply_filters(rows: list[dict[str, Any]], filters: list[tuple[str, str, Any]]) -> list[dict[str, Any]]:
        matched = rows
        for op, column, value in filters:
            if op == "eq":
                matched = [row for row in matched if row.get(column) == value]
            elif op == "like":
                wildcard = str(value).replace("%", "*")
                matched = [row for row in matched if fnmatch.fnmatch(str(row.get(column, "")), wildcard)]
        return matched

    def select(
        self,
        table: str,
        filters: list[tuple[str, str, Any]],
        order: tuple[str, bool] | None,
        limit: int | None,
    ) -> list[dict[str, Any]]:
        with self._lock:
            rows = [dict(row) for row in self._data.get(table, [])]
        rows = self._apply_filters(rows, filters)
        if order:
            column, desc = order
            rows.sort(key=lambda item: item.get(column), reverse=desc)
        if limit is not None:
            rows = rows[:limit]
        return rows

    def insert(self, table: str, payload: dict[str, Any] | list[dict[str, Any]]) -> list[dict[str, Any]]:
        rows = payload if isinstance(payload, list) else [payload]
        now = dt.datetime.utcnow().isoformat()
        inserted: list[dict[str, Any]] = []
        with self._lock:
            bucket = self._data.setdefault(table, [])
            for row in rows:
                item = dict(row)
                if "id" not in item:
                    item["id"] = str(uuid.uuid4())
                if "created_at" not in item:
                    item["created_at"] = now
                bucket.append(item)
                inserted.append(dict(item))
            self._save()
        return inserted

    def update(
        self,
        table: str,
        filters: list[tuple[str, str, Any]],
        patch: dict[str, Any],
    ) -> list[dict[str, Any]]:
        updated: list[dict[str, Any]] = []
        with self._lock:
            bucket = self._data.setdefault(table, [])
            for row in bucket:
                if self._apply_filters([row], filters):
                    row.update(patch)
                    updated.append(dict(row))
            if updated:
                self._save()
        return updated

    def delete(self, table: str, filters: list[tuple[str, str, Any]]) -> list[dict[str, Any]]:
        removed: list[dict[str, Any]] = []
        with self._lock:
            bucket = self._data.setdefault(table, [])
            keep: list[dict[str, Any]] = []
            for row in bucket:
                if self._apply_filters([row], filters):
                    removed.append(dict(row))
                else:
                    keep.append(row)
            self._data[table] = keep
            if removed:
                self._save()
        return removed


try:
    supabase_client: Client | _DummyClient = create_client(config.SUPABASE_URL, config.SUPABASE_KEY)
except Exception as exc:
    local_db_path = Path("data/localdb.json")
    print(f"Supabase fallback enabled; using local persistent DB at {local_db_path}: {exc}")
    supabase_client = _DummyClient(_LocalStore(local_db_path))


def get_db() -> Client:
    return supabase_client


async def ping_db() -> bool:
    try:
        supabase_client.table("sessions").select("count").limit(1).execute()
        return True
    except Exception:
        return False


print("Supabase client initialized")
