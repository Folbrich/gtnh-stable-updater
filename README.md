
## GTNH Nightly / Stable Updater
A tool for updating the GTNH modpack to the latest experimental/daily version via the GTNH maven, or to the latest **stable** release via the official GTNH zip packs.

### Requirements
Java: Version 21 or later.  
Git (optional for config updates)

### Configs
#### WARNING
**The first time you run the configs update, you will be prompted saying that the instance's configs will be replaced with the latest copy for the nightly.**  

The first time the configs are updated the following things occur:
* Modpack config repo is cloned to `.minecraft/.updater_pack_configs`
* A backup of the `.minecraft/config` folder will be copied to `.minecraft/config_backup_updater` (this only happens the first time)
* `.minecraft/config` will be deleted
* `.minecraft/.updater_pack_configs/configs` will copied to `.minecraft/config`

After that the update process will be:
* Delete `.minecraft/.updater_pack_configs/configs`
* Copy `.minecraft/config` to `.minecraft/.updater_pack_configs/configs`
* `git add .`
* `git commit -m "<auto_message>"`
* `git fetch`
* `git merge -x theirs origin/<nightly_config>`
* `.minecraft/config` will be deleted
* `.minecraft/.updater_pack_configs/configs` will copied to `.minecraft/config`

**If there are any merge conflicts, it will stop and notify the user. Those will have to done by hand**


### Usage
#### Command-Line Options
|Option| Description                                                               |  
|---|---------------------------------------------------------------------------|
|-M, --target-manifest| Required. Specify which release to update to the latest version of. (`DAILY` or `EXPERIMENTAL` use the DreamAssemblerXXL manifests; `STABLE` uses the official GTNH zip packs from `downloads.gtnewhorizons.com`) |
|--get-latest| Optional. Query the GTNH maven for the latest version of a mod before its in the next daily/experimental. DANGER |
|-c, --configs| Optional. Update configs in addition to mods (version pulled is based off the target manifest) |
|-C, --only-configs| Optional. Only update configs (version pulled is based off the target manifest) |
|--add| Required. Can be repeated. Adds an instance to updater using the below flags                    |
|-m, --minecraft| Required. Path to the target Minecraft directory.                         
| -s, --side| Required. Specify the side (CLIENT or SERVER).                            |
|-S, --symlinks| Optional. Use symlinks instead of copying mods. Mac/Linux only            |  

#### Example Command

Nightly / experimental example:  
`java -jar gtnh-nightly-updater.jar -M daily --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH_Nightly/.minecraft/" --add -s SERVER -m "/mnt/docker/appdata/minecraft/gtnh/"`

Stable example (Prism client + Linux server, Java 17-21):  
`java -jar gtnh-nightly-updater.jar -M stable --add -s CLIENT -m "/mnt/games/Minecraft/Instances/GTNH/.minecraft/" --add -s SERVER -m "/opt/minecraft/gtnh-server/"`

### Caching
The cache directory can be found at:  
Windows: `%LOCALAPPDATA%\gtnh-nightly-updater\`  
macOS: `~/Library/Caches/gtnh-nightly-updater/`  
Linux: `$XDG_CACHE_HOME/gtnh-nightly-updater` or `~/.cache/gtnh-nightly-updater`  

Cached mods can be found in the `mods` subdirectory.

For **stable** updates the zip packs and extracted templates are cached separately in:

- Windows: `%LOCALAPPDATA%\gtnh-stable-updater\`  
- macOS: `~/Library/Caches/gtnh-stable-updater/`  
- Linux: `$XDG_CACHE_HOME/gtnh-stable-updater` or `~/.cache/gtnh-stable-updater`  

<<<<<<< Current (Your changes)
=======
#### Stable updates (Prism Launcher) – if the game crashes with Pack200 / ClassNotFoundException

The updater replaces `libraries/`, `patches/` and `mmc-pack.json` in the instance root and tries to clear Prism’s pack cache. If the game still crashes with `java.util.jar.Pack200` or `ClassNotFoundException`:

1. In Prism Launcher: **Edit Instance → Version** and use **Reload** (or similar) so the launcher re-reads `mmc-pack.json` and re-resolves components.
2. If that doesn’t help: create a **new** instance by importing the latest GTNH zip from [gtnewhorizons.com/downloads](https://www.gtnewhorizons.com/downloads/), then copy your saves and other user data (e.g. `saves/`, `journeymap/`, `resourcepacks/`, `options.txt`, `servers.dat`) from the old instance into the new one (see GTNH wiki “Installing and Migrating”).
3. Ensure the instance uses **Java 17–25** (recommended: Java 25 for GTNH 2.8+).

>>>>>>> Incoming (Background Agent changes)
### Local Assets and Exclusions
The following 2 files can be placed in the cache directory to include or exclude certain mods
Local Asset File:
- File Name: local-assets.txt
- Format: MOD_NAME|SIDE
- List of mods to be included in addition to the [manifest's mods list](https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/master/releases/manifests/daily.json). Use mod name from the GTNH maven. (RTG|BOTH, etc)
  
Example:
```
RTG|BOTH
Climate-Control|BOTH
JustEnoughCalculation|CLIENT
WDMla|BOTH
```

Exclusions:  
- File Name: mod-exclusions.txt
- Mods to be excluded from the update process (JourneyMap, etc)
- Uses names from the [manifest's mods list](https://github.com/GTNewHorizons/DreamAssemblerXXL/blob/master/releases/manifests/daily.json)
  
Example:     
```
Realistic-World-Gen
DefaultWorldGenerator
DefaultServerList
BetterCrashes
JourneyMap
JourneyMapServer
Craft-Presence
oauth
```
