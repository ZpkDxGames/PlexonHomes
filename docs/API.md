# API

`PlexonHomesAPI` is registered with Bukkit's ServicesManager. Cached read methods return immutable `HomeView` values. Mutation methods return `CompletableFuture` results and commit Bukkit state on the primary thread.

Events: `PlexonHomeSetEvent`, `PlexonHomeDeletedEvent`, `PlexonHomeRenamedEvent`, cancellable `PlexonHomeTeleportStartEvent`, `PlexonHomeTeleportCancelledEvent`, and successful `PlexonHomeTeleportEvent`.
