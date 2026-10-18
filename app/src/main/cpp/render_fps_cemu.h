#pragma once

#include <cstdint>
#include <string_view>

namespace mimir::render_fps {

// Cemu 0.5's game-frame producer is the exact GX2 scan-buffer command entry.
// Unknown Cemu builds never fall back to Surface::queueBuffer.
inline constexpr std::string_view kCemuPackageName = "info.cemu.cemu";
inline constexpr std::string_view kCemuLibraryName = "/libCemuAndroid.so";
inline constexpr std::string_view kCemuArm64Member = "lib/arm64-v8a/libCemuAndroid.so";
inline constexpr std::string_view kCemuSwapScanBufferSymbol =
    "_Z37LatteRenderTarget_itHLESwapScanBufferv";
inline constexpr std::string_view kCemuBuildIdHex =
    "de4a100bb18f11035adedb897515dc14e69f2e3c";
inline constexpr uint64_t kCemuSwapScanBufferSize = 320;

inline bool IsCemuPackage(std::string_view package_name) {
  return package_name == kCemuPackageName;
}

}  // namespace mimir::render_fps
