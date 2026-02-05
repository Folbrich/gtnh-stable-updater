# GTNH Nightly / Stable Updater

A tool for updating the GTNH modpack to the latest **experimental/daily** version via the GTNH Maven  
or to the latest **stable** release via the official GTNH zip packs.

---

## Requirements

- **Java:** Version 21 or later  
- **Git:** Optional (only required for nightly config updates)

---

## Configs (Nightly only)

### WARNING

**The first time you run the configs update, you will be prompted saying that the instance's configs will be replaced with the latest copy for the nightly.**

The first time the configs are updated the following things occur:

- Modpack config repo is cloned to `.minecraft/.updater_pack_configs`
- A backup of the `.minecraft/config` folder will be copied to `.minecraft/config_backup_updater`  
  (this only happens the first time)
- `.minecraft/config` will be deleted
- `.minecraft/.updater_pack_configs/configs` will be copied to `.minecraft/config`

After that the update process will be:

- Delete `.minecraft/.updater_pack_configs/configs`
- Copy `.minecraft/config` to `.minecraft/.updater_pack_configs/configs`
- `git add .`
- `git commit -m "<auto_message>"`
- `git fetch`
- `git merge -x theirs origin/<nightly_config>`
- `.minecraft/config` will be deleted
- `.minecraft/.updater_pack_configs/configs` will be copied to `.minecraft/config`

**If there are any merge conflicts, it will stop and notify the user. Those will have to be resolved by hand.**

---

## Stable Updates (Important)

Stable updates use the **official GTNH zip packs** from  
`https://downloads.gtnewhorizons.com` and follow the GTNH Wiki  
**“Installing and Migrating”** guidelines.

Two modes are supported:

### Method 1 – Migration (default)

This is the **default behavior** for stable updates and matches **Method 1** from the GTNH Wiki.

- A new instance directory is created from the official GTNH zip
- User data (e.g. `saves/`, `journeymap/`, `resourcepacks/`, `options.txt`) is migrated automatically
- Safest and recommended way to update between stable versions

### Method 2 – In-place Replace (`--replace`)

This matches **Method 2** from the GTNH Wiki.

- Updates the existing instance **in place**
- Core pack directories are replaced
- User data is preserved where applicable
- No new instance directory is created



---

## Usage

### Command-Line Options

| Option | Description |
|------|-------------|
| `-M, --target-manifest` | **Required** Specify which release to update to the latest version of `DAILY`, `EXPERIMENTAL`, or `STABLE` |
| `--replace` | **Optional - Stable only** Perform an in-place update |
| `--get-latest` | **Optional - Nightly only** Query the GTNH maven for the latest version of a mod before its in the next daily/experimental (**DANGER**) |
| `-c, --configs` | **Optional - Nightly only** Update configs in addition to mods (based off target manifest) |
| `-C, --only-configs` | **Optional - Nightly only** Only update configs (based off target manifest) |
| `--add` | **Required** Can be repeated. Adds an instance to updater using the below flags |
| `-m, --minecraft` | **Required** Path to the target Minecraft directory |
| `-s, --side` | **Required** Specify the side (`CLIENT` or `SERVER`) |
| `-S, --symlinks` | **Optional - Nightly Only** Use symlinks instead of copying mods (Mac/Linux only) |

---

## Example Commands

### Nightly / Experimental

```bash
java -jar gtnh-nightly-updater.jar -M DAILY \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH_Nightly/.minecraft/" \
  --add -s SERVER -m "/mnt/docker/appdata/minecraft/gtnh/"
```

### Stable – Migration (default)

```bash
java -jar gtnh-nightly-updater.jar -M STABLE \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/" \
  --add -s SERVER -m "/opt/minecraft/gtnh-server/"
```

### Stable – In-place Replace

```bash
java -jar gtnh-nightly-updater.jar -M STABLE --replace \
  --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/"
```

---

## Caching

The cache directory can be found at:

- **Windows:** `%LOCALAPPDATA%\gtnh-nightly-updater\`
- **macOS:** `~/Library/Caches/gtnh-nightly-updater/`
- **Linux:** `$XDG_CACHE_HOME/gtnh-nightly-updater` or `~/.cache/gtnh-nightly-updater`

Cached mods can be found in the `mods` subdirectory.

For **stable** updates, downloaded zip packs and extracted templates are cached in the same cache root under a separate subdirectory.

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
