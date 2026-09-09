# Safe Teleport

Stored locations are revalidated for every teleport. PlexonHomes checks finite coordinates, world availability, world border, occupancy, support block and common hazards. If the exact stored position is unsafe, a deterministic bounded vertical/ring search may locate a nearby safe destination. Chunk loading is asynchronous when the target chunk is not already loaded.
