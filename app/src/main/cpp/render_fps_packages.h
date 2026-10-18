#pragma once

#include "render_fps_mupen.h"

#include <algorithm>
#include <string_view>

namespace mimir::render_fps {

// Kept in the same order/set as EmulatorSeedRegistry.allDispatchPackages(). Recognition-only seeds
// are intentionally absent; the Kotlin frontend must pass this same exact registry-derived set.
inline constexpr std::string_view kApprovedPackages[] = {
    "app.gamenative", "com.retroarch", "com.retroarch.aarch64", "com.retroarch.ra32",
    "org.dolphinemu.dolphinemu", "info.cemu.cemu", "com.github.stenzek.duckstation",
    "org.ppsspp.ppsspp", "org.ppsspp.ppssppgold", "me.magnum.melonds",
    "me.magnum.melonds.nightly", "me.magnum.melondualds", "xyz.aethersx2.android",
    "xyz.aethersx2.cturnip", "com.armsx2", "come.nanodata.armsx2", "com.izzy2lost.psx2",
    "com.flycast.emulator", "org.mupen64plusae.v3.fzurita", "org.mupen64plusae.v3.fzurita.pro",
    "org.mupen64plusae.v3.alpha", "com.explusalpha.Snes9xPlus", "dev.eden.eden_emulator",
    "dev.eden.eden_emulator.nightly", "com.miHoYo.Yuanshen.nightly", "dev.legacy.eden_emulator",
    "dev.legacy.eden_emulator.nightly", "org.citron.citron_emu", "aenu.aps3e", "net.rpcsx",
    "com.armsx3", "emu.x360.mobile", "emu.x360mobile.com", "xendroid.compose",
    "com.izzy2lost.x1box", "org.vita3k.emulator", "io.github.lime3ds.android",
    "org.azahar_emu.azahar", "org.citra.citra_emu",
};
inline constexpr size_t kApprovedPackageCount = sizeof(kApprovedPackages) / sizeof(kApprovedPackages[0]);

inline bool IsApprovedPackage(std::string_view package_name) {
  return std::find(std::begin(kApprovedPackages), std::end(kApprovedPackages), package_name) !=
      std::end(kApprovedPackages);
}

// Surface queueBuffer is a generic presentation signal, so it is enabled only for packages
// whose game-frame semantics have been grounded by the source/device lane. The dispatch catalog
// remains broader for identity and future specialized producers; catalog membership alone never
// authorizes this fallback.
inline bool IsGenericSurfacePackage(std::string_view package_name) {
  return package_name == "app.gamenative" || package_name == "org.dolphinemu.dolphinemu";
}

// A global Mupen renderer name is admitted only after the resolver binds its UID to the
// installed package. Reuse this predicate at attachment and every identity renewal.
inline bool IsExpectedProcessName(std::string_view package_name, std::string_view process_name) {
  if (!IsApprovedPackage(package_name)) return false;
  if (IsMupen64PlusPackage(package_name)) {
    return process_name == "paulscode.android.mupen64plusae.GameActivity";
  }
  return process_name == package_name ||
      (process_name.size() > package_name.size() + 1 &&
       process_name.substr(0, package_name.size()) == package_name &&
       process_name[package_name.size()] == ':');
}

}  // namespace mimir::render_fps
