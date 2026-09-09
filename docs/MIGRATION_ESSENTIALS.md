# Essentials Home Migration

Use `/homesadmin migrate scan`, then `plan`, review diagnostics, and finally `execute`. The importer reads `plugins/Essentials/userdata/*.yml`; it does not write to or delete source files. UUID-named files are treated as player authority. Duplicate canonical names are deterministically suffixed during the scan plan. Unresolved worlds are reported and skipped rather than deleting source data.

Retain the Essentials userdata backup until standard home commands have been verified in production. Disable only overlapping Essentials home commands after PlexonHomes is accepted as PRIMARY.
