#pragma once

#include <array>
#include <cstdint>
#include <limits>
#include <string_view>

namespace mimir::render_fps {

inline constexpr std::string_view kRetroArchPackageName = "com.retroarch.aarch64";
inline constexpr std::string_view kRetroArchLibraryName = "/libretroarch-activity.so";
inline constexpr std::string_view kRetroArchArm64Member =
    "lib/arm64-v8a/libretroarch-activity.so";
inline constexpr std::string_view kRetroArchBuildIdHex =
    "177a70d08f24cd5a4ce7ec9338c0dbe062cd9723";
inline constexpr std::string_view kRetroArchFrameSymbol = "video_driver_frame";
inline constexpr uint64_t kRetroArchFrameVa = 0xbd0bec;
inline constexpr uint64_t kRetroArchFrameSize = 0x11c0;
inline constexpr uint64_t kRetroArchPostVa = 0xbd1c98;

// A caller LR is admitted only if its file offset falls in one of these exact
// executable PT_LOAD ranges from the currently mapped core image.
struct RetroArchExecutableRange {
  uint64_t file_offset = 0;
  uint64_t size = 0;
};
inline constexpr size_t kRetroArchMaxExecutableRanges = 16;

bool ResolveRetroArchFrameOffsets(std::string_view image, uint64_t* frame_offset,
                                  uint64_t* post_offset);
bool ReadRetroArchExecutableRanges(
    std::string_view image,
    std::array<RetroArchExecutableRange, kRetroArchMaxExecutableRanges>* ranges,
    size_t* count);

enum class RetroArchEventKind { kEntry, kPost, kReturn, kLost };
struct RetroArchEvent {
  RetroArchEventKind kind = RetroArchEventKind::kLost;
  uint32_t pid = 0;
  uint32_t tid = 0;
  uint64_t timestamp_ns = 0;
  uint64_t value = 0;
  uint64_t caller_pc = 0;
  bool caller_is_core = false;
};
enum class RetroArchCounterTerminal { kNone, kLost, kWrongProcess, kInvalid };

// Counts fresh core video callbacks that return successfully from the active
// driver path. Entry and post probes are paired with a bounded per-TID stack so
// cached/NULL callbacks still close cleanly without contributing to game FPS.
class RetroArchCounter {
 public:
  static constexpr size_t kMaxThreads = 256;
  static constexpr size_t kMaxDepth = 8;
  static constexpr uint64_t kMaxCount = 1'000'000;

  RetroArchCounterTerminal ConsumeAtCutoff(const RetroArchEvent& event,
                                           uint32_t expected_pid,
                                           uint64_t cutoff_ns, bool* carried) {
    if (carried == nullptr) return Fail(RetroArchCounterTerminal::kInvalid);
    *carried = false;
    if (expected_pid == 0 || event.pid == 0) return Fail(RetroArchCounterTerminal::kInvalid);
    if (event.pid != expected_pid) return Fail(RetroArchCounterTerminal::kWrongProcess);
    if (event.tid == 0 || event.timestamp_ns == 0) {
      return Fail(RetroArchCounterTerminal::kInvalid);
    }
    if (event.kind == RetroArchEventKind::kLost) return Fail(RetroArchCounterTerminal::kLost);
    if (event.kind == RetroArchEventKind::kPost && event.value > 1) {
      return Fail(RetroArchCounterTerminal::kInvalid);
    }
    // Validate the event envelope before carrying it across a publication boundary.
    if (event.timestamp_ns > cutoff_ns) {
      *carried = true;
      return RetroArchCounterTerminal::kNone;
    }
    return Consume(event);
  }

  RetroArchCounterTerminal ConsumeLoss() { return Fail(RetroArchCounterTerminal::kLost); }

  void BeginInterval() {
    interval_count_ = 0;
    terminal_ = RetroArchCounterTerminal::kNone;
  }

  void Reset() {
    for (Slot& slot : slots_) slot.depth = 0;
    interval_count_ = 0;
    terminal_ = RetroArchCounterTerminal::kNone;
  }

  uint64_t count() const { return interval_count_; }
  RetroArchCounterTerminal terminal() const { return terminal_; }
  uint64_t OldestPendingTimestampNs() const {
    uint64_t oldest = std::numeric_limits<uint64_t>::max();
    for (const Slot& slot : slots_) {
      if (slot.depth != 0 && slot.times[0] < oldest) oldest = slot.times[0];
    }
    return oldest == std::numeric_limits<uint64_t>::max() ? 0 : oldest;
  }

 private:
  struct Slot {
    uint32_t tid = 0;
    size_t depth = 0;
    std::array<uint64_t, kMaxDepth> times{};
    std::array<bool, kMaxDepth> eligible{};
    std::array<bool, kMaxDepth> post_seen{};
  };

  RetroArchCounterTerminal Fail(RetroArchCounterTerminal status) {
    Reset();
    terminal_ = status;
    return terminal_;
  }

  Slot* Find(uint32_t tid, bool create) {
    Slot* free_slot = nullptr;
    for (Slot& slot : slots_) {
      if (slot.depth != 0 && slot.tid == tid) return &slot;
      if (slot.depth == 0 && free_slot == nullptr) free_slot = &slot;
    }
    if (!create || free_slot == nullptr) return nullptr;
    free_slot->tid = tid;
    return free_slot;
  }

  RetroArchCounterTerminal Consume(const RetroArchEvent& event) {
    if (terminal_ != RetroArchCounterTerminal::kNone) return terminal_;
    Slot* slot = Find(event.tid, event.kind == RetroArchEventKind::kEntry);
    if (event.kind == RetroArchEventKind::kEntry) {
      if (slot == nullptr || slot->depth == kMaxDepth ||
          (slot->depth != 0 && event.timestamp_ns < slot->times[slot->depth - 1])) {
        return Fail(RetroArchCounterTerminal::kLost);
      }
      const size_t index = slot->depth++;
      slot->times[index] = event.timestamp_ns;
      slot->eligible[index] = event.value != 0 && event.caller_is_core;
      slot->post_seen[index] = false;
      return RetroArchCounterTerminal::kNone;
    }
    if (slot == nullptr || slot->depth == 0 ||
        event.timestamp_ns < slot->times[slot->depth - 1]) {
      return Fail(RetroArchCounterTerminal::kLost);
    }
    const size_t index = slot->depth - 1;
    if (event.kind == RetroArchEventKind::kPost) {
      if (slot->post_seen[index]) return Fail(RetroArchCounterTerminal::kLost);
      slot->post_seen[index] = true;
      if (slot->eligible[index] && event.value == 1) {
        if (interval_count_ >= kMaxCount) return Fail(RetroArchCounterTerminal::kLost);
        ++interval_count_;
      }
      return RetroArchCounterTerminal::kNone;
    }
    if (event.kind == RetroArchEventKind::kReturn) {
      --slot->depth;
      return RetroArchCounterTerminal::kNone;
    }
    return Fail(RetroArchCounterTerminal::kInvalid);
  }

  std::array<Slot, kMaxThreads> slots_{};
  uint64_t interval_count_ = 0;
  RetroArchCounterTerminal terminal_ = RetroArchCounterTerminal::kNone;
};

}  // namespace mimir::render_fps
