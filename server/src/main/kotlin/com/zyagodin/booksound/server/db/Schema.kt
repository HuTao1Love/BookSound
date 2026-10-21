package com.zyagodin.booksound.server.db

/** Database migrations, applied in order; never edit one that has shipped, add a new one. */
object Schema {
    val migrations = listOf(
        """
        CREATE TABLE change_seq (value INTEGER NOT NULL);
        INSERT INTO change_seq (value) VALUES (0);

        CREATE TABLE users (
            id TEXT PRIMARY KEY,
            name TEXT NOT NULL UNIQUE COLLATE NOCASE,
            password_hash TEXT NOT NULL,
            is_admin INTEGER NOT NULL DEFAULT 0,
            created_at INTEGER NOT NULL,
            failed_logins INTEGER NOT NULL DEFAULT 0,
            locked_until INTEGER
        );

        CREATE TABLE devices (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            name TEXT NOT NULL,
            kind TEXT NOT NULL,
            sync_id TEXT,
            created_at INTEGER NOT NULL,
            last_seen_at INTEGER,
            revoked_at INTEGER,
            -- Hash of the refresh token replaced by the current one: presenting it again means it was stolen.
            previous_refresh_hash TEXT
        );

        CREATE TABLE tokens (
            hash TEXT PRIMARY KEY,
            device_id TEXT NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
            kind TEXT NOT NULL,
            expires_at INTEGER NOT NULL
        );
        CREATE INDEX tokens_device ON tokens(device_id);


        CREATE TABLE books (
            id TEXT PRIMARY KEY,
            title TEXT NOT NULL,
            author TEXT,
            narrator TEXT,
            series TEXT,
            series_index TEXT,
            year TEXT,
            genre TEXT,
            description TEXT,
            language TEXT,
            duration_ms INTEGER NOT NULL,
            relative_path TEXT NOT NULL,
            file_size INTEGER NOT NULL,
            file_modified INTEGER NOT NULL DEFAULT 0,
            file_sha256 TEXT,
            file_revision INTEGER NOT NULL DEFAULT 1,
            cover_sha256 TEXT,
            added_at INTEGER NOT NULL,
            added_by TEXT,
            missing_since INTEGER,
            revision INTEGER NOT NULL DEFAULT 1,
            updated_at INTEGER NOT NULL,
            updated_by TEXT NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0,
            seq INTEGER NOT NULL
        );
        CREATE INDEX books_seq ON books(seq);
        CREATE INDEX books_path ON books(relative_path);

        CREATE TABLE chapters (
            book_id TEXT NOT NULL REFERENCES books(id) ON DELETE CASCADE,
            idx INTEGER NOT NULL,
            title TEXT NOT NULL,
            start_ms INTEGER NOT NULL,
            end_ms INTEGER NOT NULL,
            PRIMARY KEY (book_id, idx)
        );

        CREATE TABLE playback (
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            book_id TEXT NOT NULL,
            position_ms INTEGER NOT NULL,
            speed REAL NOT NULL,
            finished INTEGER NOT NULL,
            last_played_at INTEGER,
            revision INTEGER NOT NULL,
            updated_at INTEGER NOT NULL,
            updated_by TEXT NOT NULL,
            deleted INTEGER NOT NULL DEFAULT 0,
            seq INTEGER NOT NULL,
            PRIMARY KEY (user_id, book_id)
        );
        CREATE INDEX playback_seq ON playback(user_id, seq);

        CREATE TABLE preferences (
            user_id TEXT PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
            body TEXT NOT NULL,
            seq INTEGER NOT NULL
        );

        CREATE TABLE settings (
            key TEXT PRIMARY KEY,
            value TEXT NOT NULL
        );

        CREATE TABLE watch_profiles (
            id TEXT PRIMARY KEY,
            body TEXT NOT NULL
        );

        CREATE TABLE import_jobs (
            id TEXT PRIMARY KEY,
            body TEXT NOT NULL,
            stage TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE uploads (
            id TEXT PRIMARY KEY,
            user_id TEXT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
            file_name TEXT NOT NULL,
            size_bytes INTEGER NOT NULL,
            kind TEXT NOT NULL,
            sha256 TEXT,
            relative_dir TEXT NOT NULL,
            created_at INTEGER NOT NULL
        );

        CREATE TABLE torrents (
            id TEXT PRIMARY KEY,
            body TEXT NOT NULL
        );
        """,
    )
}
