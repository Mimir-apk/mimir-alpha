#pragma once

#include <array>
#include <cstddef>
#include <cstdint>

namespace mimir::render_fps {

// Eden's game-frame producer is resolved from the validated mapped libyuzu image. The helper
// deliberately carries no address constants. Two routes admit a build:
//   1. pinned: one of the exact GNU Build IDs below, plus the .dynsym symbol and function range;
//   2. shape (policy 2026-10-14, relaxing the 09-08 spec's "unknown builds fail closed"):
//      the exact symbol name in .dynsym or .symtab, STT_FUNC, 28 bytes, inside an executable
//      PT_LOAD and SHF_EXECINSTR section, whose seven AArch64 words match the wrapper below and
//      whose BL and tail B land in executable code.
inline constexpr char kEdenLibraryName[] = "libyuzu-android.so";
inline constexpr char kEdenPackageName[] = "dev.eden.eden_emulator";
inline constexpr char kEdenRendererFrameEndNotifySymbol[] =
    "_ZN5Tegra3GPU22RendererFrameEndNotifyEv";
inline constexpr char kEdenBuildIdHex[] =
    "36a4f66ee1900dc44f0653fc465d379337a2275e";
inline constexpr char kEdenOptimizedBuildIdHex[] =
    "bb5c259a39dfded1c049fa8c2ee6ed7d4c422b55";
inline constexpr uint64_t kEdenRendererFrameEndNotifySize = 28;

// The wrapper body is `EndGameFrame(system.GetPerfStats())` with `system` reached through the
// GPU's pimpl:
//   stp x29, x30, [sp, #-16]!      a9bf7bfd
//   mov x29, sp                    910003fd
//   ldr x8, [x0]                   f9400008   pimpl pointer, offset 0 in every known build
//   ldr x0, [x8, #imm]             f94001?0   member offset moves between builds (#8 / #0)
//   bl  <GetPerfStats>             94......   direct or through a PLT stub
//   ldp x29, x30, [sp], #16        a8c17bfd
//   b   <EndGameFrame>             14......   tail call
// A word matches when (word & mask) == pattern. Only the architectural fields that a link or a
// struct re-layout changes are masked: the LDR's unsigned imm12 (bits 10..21) and the BL/B imm26
// (bits 0..25). Everything else, including both registers of the pimpl load, stays exact.
// Derived from the real bytes of canonical Eden 1f6734c (Build ID 36a4f66e..., #8, PLT targets)
// and of the Genshin-spoof fork f8ef0f1 (Build ID d4e32c08..., #0, direct targets).
inline constexpr size_t kEdenShapeWordCount = 7;
inline constexpr std::array<uint32_t, kEdenShapeWordCount> kEdenShapePattern = {
    0xa9bf7bfdU, 0x910003fdU, 0xf9400008U, 0xf9400100U, 0x94000000U, 0xa8c17bfdU, 0x14000000U};
inline constexpr std::array<uint32_t, kEdenShapeWordCount> kEdenShapeMask = {
    0xffffffffU, 0xffffffffU, 0xffffffffU, 0xffc003ffU, 0xfc000000U, 0xffffffffU, 0xfc000000U};

}  // namespace mimir::render_fps
