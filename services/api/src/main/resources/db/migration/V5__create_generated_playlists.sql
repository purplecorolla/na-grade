-- Playlists the app created in the user's music provider (#30), one per user, artist and show. The show is the
-- artist + date until the radar creates the show entity (Sprint 3: show_date becomes show_id in a migration)
CREATE TABLE generated_playlists (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    provider          VARCHAR(32) NOT NULL,
    artist_spotify_id VARCHAR(22) NOT NULL,
    show_date         DATE        NOT NULL,
    -- Null only inside the transaction that creates the playlist (the row is reserved first, see PlaylistService)
    playlist_id       VARCHAR(64),
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT generated_playlists_user_artist_show_key UNIQUE (user_id, artist_spotify_id, show_date)
);
