#pragma once

#include <cstdint>
#include <string_view>

namespace mimir::render_fps {

// X1-BOX 1.2.5's own FPS counter is driven by the guest NV_PGRAPH_INCREMENT READ_3D
// command. Unknown X1-BOX builds never fall back to Surface::queueBuffer.
inline constexpr std::string_view kX1BoxPackageName = "com.izzy2lost.x1box";
inline constexpr std::string_view kX1BoxLibraryName = "/libxemu.so";
inline constexpr std::string_view kX1BoxArm64Member = "lib/arm64-v8a/libxemu.so";
inline constexpr std::string_view kX1BoxIncrementSymbol = "nv2a_profile_increment";
inline constexpr std::string_view kX1BoxBuildIdHex =
    "c85f8e268c2037793ef6912abc375dc479b3503d";
inline constexpr uint64_t kX1BoxIncrementValue = 0x5b738c;
inline constexpr uint64_t kX1BoxIncrementSize = 136;

inline bool IsX1BoxPackage(std::string_view package_name) {
  return package_name == kX1BoxPackageName;
}

}  // namespace mimir::render_fps
