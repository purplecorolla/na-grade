-- Spotify artist -> setlist.fm artist (#28). Global: the same artist is the same for every user.
-- Only identical-name matches are saved; a miss is cached in Redis for a day, so the artist can still show up later
CREATE TABLE setlist_artists (
    spotify_artist_id VARCHAR(22) PRIMARY KEY,
    name              TEXT        NOT NULL,
    setlistfm_mbid    VARCHAR(36) NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
