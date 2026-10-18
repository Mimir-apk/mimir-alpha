#pragma once

#include <cstdint>
#include <string_view>

namespace mimir::render_fps {

// Azahar/Lime3DS' top-screen game producer is resolved from the validated mapped
// libcitra image. The exported EndGameFrame is inlined at this call site, so the
// fixed SetBufferSwap entry is the only approved producer for these builds.
inline constexpr char kAzaharLibraryName[] = "libcitra-android.so";
inline constexpr char kAzaharSetBufferSwapSymbol[] =
    "_ZN9VideoCore3GPU13SetBufferSwapEjRKN7Service3GSP15FrameBufferInfoE";
inline constexpr char kAzaharThorBuildIdHex[] =
    "6c6500d01d58907bfc2453ca357385b64cab6f87";
inline constexpr char kAzaharOfficialBuildIdHex[] =
    "ee2baeaef74de49a8aa5bf3fc6e690a8dbba23dd";
inline constexpr uint64_t kAzaharThorSetBufferSwapSize = 336;
inline constexpr uint64_t kAzaharOfficialSetBufferSwapSize = 892;

// Only the catalog's Citra/Lime/Azahar entries use this producer. Other approved
// emulator families, including the Switch/Yuzu-derived Citron package, stay on
// their existing route until separately proved.
inline bool IsAzaharPackage(std::string_view package_name) {
  return package_name == "io.github.lime3ds.android" || package_name == "org.azahar_emu.azahar" ||
      package_name == "org.citra.citra_emu";
}

// perf_event's user-register sample captures the full X1 value. SetBufferSwap's
// screen_id is W1, so the high 32 bits are outside the ABI field and ignored.
inline bool IsAzaharTopScreen(uint64_t x1) {
  return static_cast<uint32_t>(x1) == 0;
}

}  // namespace mimir::render_fps
