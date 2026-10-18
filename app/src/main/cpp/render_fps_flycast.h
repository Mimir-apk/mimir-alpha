#pragma once

#include <cstdint>
#include <string_view>

namespace mimir::render_fps {

inline constexpr std::string_view kFlycastPackageName = "com.flycast.emulator";
inline constexpr std::string_view kFlycastLibraryName = "/libflycast.so";
inline constexpr std::string_view kFlycastArm64Member = "lib/arm64-v8a/libflycast.so";
inline constexpr std::string_view kFlycastPresentSymbol = "_Z17rend_single_frameRKb";
inline constexpr uint64_t kFlycastPresentSize = 132;
inline constexpr std::string_view kFlycastThorBuildIdHex =
    "76233bfda49c57c5106fd30aa92806806ef6a277";
inline constexpr std::string_view kFlycastOdinBuildIdHex =
    "73574f8b84adddf1220503c905f9f6e61c7ee6a4";

// Resolve Flycast's successful-present return seam. Unknown builds and incomplete
// executable symbol ranges fail closed; the caller must install a return probe.
bool ResolveFlycastPresentOffset(std::string_view image, uint64_t* offset);

}  // namespace mimir::render_fps
