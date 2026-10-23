-- One player per computer: accounts that upload with the same device ID (shared by every Minecraft
-- instance on that computer) count as one player. Only the account used most recently is shown in
-- Global Statistics; the others are "superseded" and their servers do not count towards the totals.
ALTER TABLE players ADD COLUMN device_id TEXT;
ALTER TABLE players ADD COLUMN superseded INTEGER NOT NULL DEFAULT 0;
CREATE INDEX players_device ON players (device_id);
