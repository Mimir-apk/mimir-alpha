#pragma once

#include <array>
#include <cstdint>
#include <string>
#include <string_view>

#include "render_fps_armsx2.h"
#include "render_fps_retroarch.h"

namespace mimir::render_fps {

inline constexpr std::string_view kQueueBufferSymbol =
    "_ZN7android7Surface11queueBufferEP19ANativeWindowBufferi";
inline constexpr std::string_view kPpssppActualFlipSymbol = "_Z21DisplayFireActualFlipv";
inline constexpr std::string_view kPpssppBuildIdHex =
    "2a5625e0eccd1687c85f8b6a0a182248e7994b7d";
inline constexpr std::string_view kPpssppOdinBuildIdHex =
    "a29a98b79936dbb69c38ee3316b8bf919e33fc23";
inline constexpr uint64_t kPpssppActualFlipSize = 20;

// Resolve one fixed dynamic symbol from a mapped ELF image. The parser accepts only a bounded,
// little-endian AArch64 image and returns the file offset inside an executable PT_LOAD segment.
bool ResolveQueueBufferOffset(std::string_view image, uint64_t* offset);

// The PPSSPP adapter is deliberately narrower than the generic resolver. It requires one of the
// approved GNU Build IDs, the exact dynamic producer name, a 20-byte AArch64 function, and an
// executable PT_LOAD range. The returned value is a file offset suitable for a uprobe.
bool ResolvePpssppActualFlipOffset(std::string_view image, uint64_t* offset);

// How an Eden-family libyuzu image was admitted.
enum class EdenAdmission : uint8_t {
  kRejected = 0,
  kPinned,  // one of the approved GNU Build IDs (the fast path)
  kShape,   // unknown Build ID, accepted by the hook function's own bytes
};

// Why Eden resolution refused an image. The shape reasons are reported only for an image that no
// pinned Build ID admitted; kIdentity is the helper's own check on the library file it opened.
enum class EdenShapeReject : uint8_t {
  kNone = 0,
  kImage,          // not a bounded little-endian AArch64 ELF  -> shape_image
  kMissing,        // no defined symbol of that name           -> shape_missing
  kAmbiguous,      // several definitions that disagree        -> shape_ambiguous
  kNotFunc,        // symbol is not STT_FUNC                   -> shape_not_func
  kSize,           // st_size != 28                            -> shape_size
  kNotExecutable,  // outside an executable PT_LOAD/SHF_EXECINSTR section -> shape_not_exec
  kBytes,          // the seven instruction words differ       -> shape_bytes
  kTarget,         // a BL/B target is outside an executable PT_LOAD or SHF_EXECINSTR section
                   //                                          -> shape_target
  kIdentity,       // the file opened at the mapped path is not the mapped file. Set by the
                   // helper, not by the ELF parser                -> identity_changed
};

struct EdenResolution {
  EdenAdmission admission = EdenAdmission::kRejected;
  EdenShapeReject reject = EdenShapeReject::kNone;
  // Lowercase GNU Build ID of the image, empty when it carries none.
  std::string build_id_hex;
};

// Resolve Eden's game-frame notifier from one image. A pinned GNU Build ID is checked first. An
// unknown Build ID is then admitted by shape (policy 2026-10-14, relaxing the 09-08 spec's
// fail-closed rule for unknown builds): the exact dynamic or static symbol, STT_FUNC, 28 bytes,
// inside an executable PT_LOAD and SHF_EXECINSTR section, with the seven wrapper instructions of
// kEdenShapePattern under kEdenShapeMask, whose BL and tail B land in an executable PT_LOAD and an
// SHF_ALLOC|SHF_EXECINSTR section. Anything else fails closed, never to queueBuffer.
// `resolution` is optional and is filled on both outcomes. Callers must be Eden-family packages.
bool ResolveEdenRendererFrameEnd(std::string_view image, uint64_t* offset,
                                 EdenResolution* resolution);

// Same decision without the detail; kept so existing callers and tests are unchanged.
bool ResolveEdenRendererFrameEndOffset(std::string_view image, uint64_t* offset);

// One log line for a resolution: "eden_target=pinned|shape build_id=<hex>" on acceptance, or
// "eden_target=rejected reason=<shape_*> build_id=<hex>" otherwise. build_id is "none" if absent.
std::string FormatEdenTargetLog(const EdenResolution& resolution);

// Resolve one of the two approved Azahar/Lime3DS SetBufferSwap producer pins.
// Each Build ID has its own exact symbol-size check; unknown 3DS builds fail closed.
bool ResolveAzaharSetBufferSwapOffset(std::string_view image, uint64_t* offset);

// Resolve Cemu 0.5's exact GX2 scan-buffer command entry. Unknown Build IDs,
// symbol sizes, and incomplete executable ranges fail closed.
bool ResolveCemuSwapScanBufferOffset(std::string_view image, uint64_t* offset);

// Resolve DuckStation's exact PerformanceCounters::Update entry from the approved
// Build ID, .text layout, bounded AArch64 words, and handler BL target.
bool ResolveDuckStationUpdateOffset(std::string_view image, uint64_t* offset);

// Resolve ARMSX2's exact source-selected internal FPS probes from one of the
// approved 4K/16K 2.6.8 images. Unknown images fail closed.
bool ResolveArmsX2Offsets(
    std::string_view image,
    std::array<uint64_t, ArmsX2Counter::kProbeCount>* offsets);

// Resolve X1-BOX 1.2.5's exact local/hidden guest READ_3D counter entry from the
// approved GNU Build ID and .symtab. Unknown identities and incomplete executable
// ranges fail closed; this resolver intentionally does not broaden .dynsym routes.
bool ResolveX1BoxIncrementOffset(std::string_view image, uint64_t* offset);

// Resolve Flycast's exact successful-present return function from one approved build.
// Unknown identities and incorrect 132-byte executable ranges fail closed.
bool ResolveFlycastPresentOffset(std::string_view image, uint64_t* offset);

// Resolve the completed void swap used by the approved Mupen64Plus GLideN64 build.
// The caller installs a return probe and intentionally ignores the ABI return register.
bool ResolveMupen64PlusSwapOffset(std::string_view image, uint64_t* offset);

// Resolve all three approved melonDS producer symbols from one supported image. The output
// offsets are file offsets inside executable PT_LOAD segments and are valid for up to the
// canonical and Thor Beta Build IDs only; an unknown melonDS build fails closed.
bool ResolveMelonDsOffsets(std::string_view image, uint64_t* run_frame_offset,
                           uint64_t* discard_offset, uint64_t* push_offset);

struct StoredZipMember {
  uint64_t data_offset = 0;
  uint64_t size = 0;
};

// Resolve one uncompressed ZIP member with bounded central/local records. ZIP64, encrypted,
// descriptor-backed, duplicate, overlapping, and malformed members are rejected.
bool ResolveStoredZipMember(std::string_view archive, std::string_view member_name,
                            StoredZipMember* member);

}  // namespace mimir::render_fps
