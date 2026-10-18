#pragma once

#include <cstdint>
#include <string_view>

namespace mimir::render_fps {

inline constexpr std::string_view kMupen64PlusPackageAlpha = "org.mupen64plusae.v3.alpha";
inline constexpr std::string_view kMupen64PlusPackageFzurita = "org.mupen64plusae.v3.fzurita";
inline constexpr std::string_view kMupen64PlusPackageFzuritaPro = "org.mupen64plusae.v3.fzurita.pro";
inline constexpr std::string_view kMupen64PlusLibraryName =
    "/libmupen64plus-video-GLideN64.so";
inline constexpr std::string_view kMupen64PlusArm64Member =
    "lib/arm64-v8a/libmupen64plus-video-GLideN64.so";
inline constexpr std::string_view kMupen64PlusSwapSymbol =
    "_ZN24DisplayWindowMupen64plus12_swapBuffersEv";
inline constexpr uint64_t kMupen64PlusSwapSize = 336;
inline constexpr std::string_view kMupen64PlusThorBuildIdHex =
    "0cede709186795d71a3bdcc9a9f7358cee26e736";
inline constexpr std::string_view kMupenBridgeThorBuildIdHex =
    "0e23deb4256eaae298d8971f702b4f1777a5f1b8";
inline constexpr std::string_view kMupenBridgeSwapSymbol = "VidExtFuncGLSwapBuf";
inline constexpr uint64_t kMupenBridgeSwapSize = 700;

inline bool IsMupen64PlusPackage(std::string_view package_name) {
  return package_name == kMupen64PlusPackageAlpha ||
      package_name == kMupen64PlusPackageFzurita ||
      package_name == kMupen64PlusPackageFzuritaPro;
}

// This is a void completed-swap producer. Its uprobe must be installed as a return probe,
// but the ABI return register is deliberately ignored: _swapBuffers() has no status value.
bool ResolveMupen64PlusSwapOffset(std::string_view image, uint64_t* offset);

// The inspected bridge has one game-only eglSwapBuffers import. Its local PLT
// return carries EGLBoolean: count only EGL_TRUE, never the outer void/status shim.
bool ResolveMupenBridgeSwapOffset(std::string_view image, uint64_t* offset);

}  // namespace mimir::render_fps
