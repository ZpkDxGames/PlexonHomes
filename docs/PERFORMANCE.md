# Performance

PlexonHomes registers no independent `PlayerMoveEvent` or damage listener. During a warmup it installs one Core watch handle; otherwise the Homes movement path is absent. PlexonCore's shared player-watch gateway performs a UUID lookup and early return for players without active watches. SQLite and migration parsing run off-thread; Bukkit world/block state remains on the primary thread.
