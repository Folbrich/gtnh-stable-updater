# GTNH Stable Updater

A tool for updating the GTNH modpack — with a **GUI** for the common case (stable updates,
migration, multiple instances) and a **CLI** for scripting stable updates or updating
nightly/experimental builds via the GTNH Maven.

---

## Requirements

- **Java:** Version 21 or later (not needed if you use the packaged `.exe`, it bundles its own runtime)
- **Git:** Optional (only required for nightly config updates)

---

## GUI (recommended)

The GUI covers **stable** updates: choosing a version (latest stable, latest beta/RC, or a pinned
version), migration vs. in-place replace, which user data to carry over, and updating several
client/server instances in one run. It remembers your last setup between launches and is available
in English and German (toggle in the header).

### Getting it

Grab the latest build from the [Releases page](https://github.com/Folbrich/gtnh-stable-updater/releases):

- **Windows, no Java required:** download and unzip `GTNH-Stable-Updater-<version>-win64.zip`, then
  run `GTNH Stable Updater.exe`. It's an "app-image" — a self-contained folder with its own bundled
  Java runtime, not an installer (no Start Menu entry / uninstaller).
- **Any OS with Java 21+:** download `gtnh-stable-updater-<version>.jar` and double-click (or
  `java -jar`) it. It's the same jar as the CLI — launching it with no arguments always opens the GUI.

### Update methods

- **Migration (default):** creates a new instance from the current pack and carries over saves and
  user data automatically; the existing instance is kept as a backup. Matches **Method 1** from the
  GTNH Wiki. Recommended for clients.
- **Replace in-place:** updates the existing instance directly without creating a new one; user data
  is preserved where applicable. Matches **Method 2** from the GTNH Wiki. Server instances are
  always updated this way (mods/config/scripts/libraries are replaced; world data, backups and the
  JourneyMapServer UUID are left untouched).

Both methods let you pick exactly which user data to keep (options/keybinds, saves, server list,
other mod data) and optionally preserve specific config files you've hand-edited yourself.

The version list (latest stable / latest beta / the full dropdown) is always fetched live from the
[GTNH version history page](https://www.gtnewhorizons.com/version-history/) on every launch — there
is no hardcoded or cached version list.

---

## CLI

The CLI scripts stable updates without the GUI, and also covers **nightly/experimental** updates
via the GTNH Maven. Running the jar with **no arguments** opens the GUI instead. Get the jar from
the [Releases page](https://github.com/Folbrich/gtnh-stable-updater/releases) (see
[Getting it](#getting-it) above).

### Command-Line Options

| Option | Description |
|------|-------------|
| `-M, --target-manifest` | **Required** Specify which release to update to the latest version of `DAILY`, `EXPERIMENTAL`, or `STABLE` |
| `--replace` | **Optional - Stable only** Perform an in-place update |
| `--beta` | **Optional - Stable only** Allow the latest release to be a beta/RC build, not just entries tagged "Stable release" |
| `--stable-version` | **Optional - Stable only** Pin to an exact version from the [version history page](https://www.gtnewhorizons.com/version-history/) (e.g. `2.9.0-beta-2`), overriding `--beta` |
| `--get-latest` | **Optional - Nightly only** Query the GTNH maven for the latest version of a mod before its in the next daily/experimental (**DANGER**) |
| `-c, --configs` | **Optional - Nightly only** Update configs in addition to mods (based off target manifest) |
| `-C, --only-configs` | **Optional - Nightly only** Only update configs (based off target manifest) |
| `--add` | **Required** Can be repeated. Adds an instance to updater using the below flags |
| `-m, --minecraft` | **Required** Path to the target Minecraft directory |
| `-s, --side` | **Required** Specify the side (`CLIENT` or `SERVER`) |
| `-S, --symlinks` | **Optional - Nightly Only** Use symlinks instead of copying mods (Mac/Linux only) |

### Example Commands

#### Stable – Migration (default)

```bash
java -jar gtnh-stable-updater.jar -M STABLE \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/" \
  --add -s SERVER -m "/opt/minecraft/gtnh-server/"
```

#### Stable – In-place Replace / Latest Beta / Pinned version

```bash
java -jar gtnh-stable-updater.jar -M STABLE --replace \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/"

java -jar gtnh-stable-updater.jar -M STABLE --replace --beta \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/"

java -jar gtnh-stable-updater.jar -M STABLE --replace --stable-version 2.9.0-beta-2 \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/"
```

#### Nightly / Experimental

```bash
java -jar gtnh-stable-updater.jar -M DAILY \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH_Nightly/.minecraft/" \
  --add -s SERVER -m "/mnt/docker/appdata/minecraft/gtnh/"
```

### Configs (Nightly only)

Config updates (`-c`/`-C`) diff the modpack's config repo against your instance using a local git
clone in `.minecraft/.updater_pack_configs`, backing up your existing `config` folder once on the
first run (`config_backup_updater`). Merge conflicts stop the process and must be resolved by hand.

---

## Caching

The cache directory can be found at:

- **Windows:** `%LOCALAPPDATA%\gtnh-nightly-updater\`
- **macOS:** `~/Library/Caches/gtnh-nightly-updater/`
- **Linux:** `$XDG_CACHE_HOME/gtnh-nightly-updater` or `~/.cache/gtnh-nightly-updater`

Cached mods can be found in the `mods` subdirectory.

For **stable** updates, downloaded zip packs and extracted templates are cached in the same cache root under a separate subdirectory. GUI settings (last-used form state, theme, language) are stored as `gui-settings.json` in the stable cache subdirectory.

---

## Local Assets and Exclusions (Nightly)

The following two files can be placed in the cache directory to include or exclude certain mods.

### Local Asset File

- **File name:** `local-assets.txt`
- **Format:** `MOD_NAME|SIDE`
- Adds mods in addition to the manifest’s mod list

Example:

```text
RTG|BOTH
Climate-Control|BOTH
JustEnoughCalculation|CLIENT
WDMla|BOTH
```

### Mod Exclusions

- **File name:** `mod-exclusions.txt`
- Mods to be excluded from the update process
- Uses names from the manifest’s mod list

Example:

```text
Realistic-World-Gen
DefaultWorldGenerator
DefaultServerList
BetterCrashes
JourneyMap
JourneyMapServer
Craft-Presence
oauth
```
