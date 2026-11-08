# rejuv-dual

A dual-screen Android player for **Pokémon Rejuvenation** (and other Pokémon Essentials games), in the spirit of PokeDaisy.

The game runs on the top screen. The bottom screen shows a companion UI:

- **Party**: HP, status, types and moves for your whole team
- **Battle**: wild/opponent overlay (level, types, HP, caught/new), plus touch buttons for Fight / Bag / Pokémon / Run and your moves with PP and type effectiveness
- **Info**: a diagnostics probe showing which game hooks were found

On single-screen devices, the companion opens as a side panel from the button in the top-right corner.

## Install

1. Open the repo's **Actions** tab, open the latest successful **Build APK** run, and download **rejuv-dual-apk** from *Artifacts*.
2. Unzip it and install the `.apk` on your device (allow installing unknown apps if asked).
3. Copy your Rejuvenation game folder's *contents* to `/storage/emulated/0/mkxp-z/` (so `Game.exe`, `Data/`, `Graphics/` etc. sit directly in that folder).
4. Open **Rejuv Dual** and grant **All files access** when asked.

## Iterating on the mod without rebuilding

Copy `app/src/main/assets/companion/companion.rb` to `/storage/emulated/0/mkxp-z/rejuv-dual/companion.rb`. If that file exists, the app loads it instead of the bundled copy. The probe also writes `rejuv-dual/probe.json` next to the game.

## How it works

- **Runtime**: [mkxp-z](https://github.com/mkxp-z/mkxp-z) via [mkxp-z-android-reworked](https://github.com/BookerRues9/mkxp-z-android-reworked).
- **Native bridge**: a `Companion` Ruby module (`app/jni/mkxp-z/binding/android-binding.cpp`) passes JSON between the game and Java.
- **Ruby mod** (`app/src/main/assets/companion/companion.rb`): runs before the game's scripts, hooks `Graphics.update`, then wraps the battle scene's menus once the Essentials classes exist. Touch picks set the menu cursor and inject one confirm press, so the game's own menu logic still runs.
- **Second screen** (`app/src/main/java/com/hatkid/mkxpz/companion/`): an Android `Presentation` with a WebView on the secondary display, which never takes key focus so controller input stays with the game.
- **UI**: `app/src/main/assets/companion/index.html`.

## Roadmap

- Widescreen (16:9) map rendering
- Touch bag and party screens in battle
- Map/location panel

## License

mkxp-z is GPL-2.0-or-later; this project is a derivative work under the same terms (see `app/jni/mkxp-z/COPYING`).
