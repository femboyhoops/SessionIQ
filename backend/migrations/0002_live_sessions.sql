-- Players with an open live connection right now (one row per player, not per connection).
-- Written by the LiveHub Durable Object on connect and when the player's last connection closes.
CREATE TABLE live_sessions (
	uuid TEXT PRIMARY KEY,
	connected_at INTEGER NOT NULL
);
