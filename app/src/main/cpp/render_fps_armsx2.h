#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>
#include <string_view>

namespace mimir::render_fps {

inline constexpr std::string_view kArmsX2PackageName = "com.armsx2";
inline constexpr std::string_view kArmsX2LibraryName4K = "/libemucore_4k.so";
inline constexpr std::string_view kArmsX2LibraryName16K = "/libemucore_16k.so";
inline constexpr std::string_view kArmsX2Arm64Member4K =
    "lib/arm64-v8a/libemucore_4k.so";
inline constexpr std::string_view kArmsX2Arm64Member16K =
    "lib/arm64-v8a/libemucore_16k.so";
inline constexpr std::string_view kArmsX2BuildId4K =
    "2b27607ee11f7a29047add62eef5aac8d1c0c857";
inline constexpr std::string_view kArmsX2BuildId16K =
    "33aa894b6dd1798ac8906d56b66c8f255dd68f58";
inline constexpr uint64_t kArmsX2TextAddr = 0x460000;
inline constexpr uint64_t kArmsX2TextOffset = 0x460000;
inline constexpr uint64_t kArmsX2TextSize4K = 0xfba4c0;
inline constexpr uint64_t kArmsX2TextSize16K = 0xfba188;
inline constexpr uint64_t kArmsX2ExecutableSize4K = 0xfc0f70;
inline constexpr uint64_t kArmsX2ExecutableSize16K = 0xfc0c40;
inline constexpr uint64_t kArmsX2UpdateVa = 0x11d3e58;
inline constexpr uint64_t kArmsX2UpdateRange = 0x570;
inline constexpr uint64_t kArmsX2DeltaVa = 0x11d3f8c;
inline constexpr uint64_t kArmsX2BlitVa = 0x11d4078;
inline constexpr uint64_t kArmsX2PrivilegedVa = 0x11d4088;
inline constexpr uint64_t kArmsX2ZeroVa = 0x11d4098;

enum class ArmsX2EventKind { kDelta, kBlit, kPrivileged, kZero, kLost };

struct ArmsX2Event {
  ArmsX2EventKind kind = ArmsX2EventKind::kLost;
  uint32_t pid = 0;
  uint32_t tid = 0;
  uint64_t timestamp_ns = 0;
  uint64_t value = 0;
};

enum class ArmsX2CounterTerminal { kNone, kLost, kWrongProcess, kInvalid };

// Pairs the raw CLOCK_MONOTONIC delta observed before ConvertValueToSeconds with
// exactly one source-selected counter branch from the same serialized Update call.
// This is the emulator's internal GS game-output estimate; it is not a panel or
// unique-present counter.
class ArmsX2Counter {
 public:
  static constexpr size_t kProbeCount = 4;
  static constexpr size_t kMaxRings = 512;
  static constexpr size_t kMaxThreads = kMaxRings / kProbeCount;
  static constexpr uint64_t kMinSourceWindowNs = 500'000'000ULL;
  static constexpr uint64_t kMaxSourceWindowNs = 5'000'000'000ULL;
  static constexpr uint64_t kMaxAggregateNs = 5'000'000'000ULL;
  static constexpr uint64_t kMaxCount = 1'000'000ULL;

  static_assert(kMaxThreads != 0 && kMaxThreads * kProbeCount == kMaxRings);

  ArmsX2CounterTerminal ConsumeAtCutoff(const ArmsX2Event& event,
                                        uint32_t expected_pid,
                                        uint64_t cutoff_ns, bool* carried) {
    if (carried == nullptr) return Fail(ArmsX2CounterTerminal::kInvalid);
    *carried = false;
    if (expected_pid == 0 || event.pid == 0) {
      return Fail(ArmsX2CounterTerminal::kInvalid);
    }
    if (event.pid != expected_pid) return Fail(ArmsX2CounterTerminal::kWrongProcess);
    if (event.tid == 0 || event.timestamp_ns == 0 || cutoff_ns == 0) {
      return Fail(ArmsX2CounterTerminal::kInvalid);
    }
    if (!ValidValue(event)) return Fail(ArmsX2CounterTerminal::kInvalid);
    Slot* slot = Find(event.tid, false);
    if (slot == nullptr && event.kind != ArmsX2EventKind::kDelta) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    if (slot != nullptr && event.timestamp_ns <= slot->last_timestamp_ns) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    // Validate the complete event before carrying it. A future selector must not
    // become an orphan merely because this drain stopped at the cutoff.
    if (event.timestamp_ns > cutoff_ns) {
      *carried = true;
      return ArmsX2CounterTerminal::kNone;
    }
    if (slot == nullptr) {
      slot = Find(event.tid, true);
      if (slot == nullptr) return Fail(ArmsX2CounterTerminal::kLost);
    }
    if (event.kind == ArmsX2EventKind::kDelta) return ConsumeDelta(slot, event);
    return ConsumeSelector(slot, event);
  }

  ArmsX2CounterTerminal ConsumeLoss() { return Fail(ArmsX2CounterTerminal::kLost); }

  // A publication clears only the aggregate. The source timestamp and an
  // unmatched delta survive so a call crossing the helper boundary is consumed
  // exactly once in the next interval.
  void BeginInterval() {
    aggregate_count_ = 0;
    aggregate_duration_ns_ = 0;
    interval_has_publication_ = false;
    terminal_ = ArmsX2CounterTerminal::kNone;
  }

  void Reset() {
    for (Slot& slot : slots_) slot = Slot{};
    aggregate_count_ = 0;
    aggregate_duration_ns_ = 0;
    interval_has_publication_ = false;
    terminal_ = ArmsX2CounterTerminal::kNone;
  }

  uint64_t count() const { return aggregate_count_; }
  uint64_t duration_ns() const { return aggregate_duration_ns_; }
  bool interval_has_publication() const { return interval_has_publication_; }
  ArmsX2CounterTerminal terminal() const { return terminal_; }

  uint64_t OldestPendingTimestampNs() const {
    uint64_t oldest = std::numeric_limits<uint64_t>::max();
    for (const Slot& slot : slots_) {
      if (slot.pending && slot.last_timestamp_ns < oldest) oldest = slot.last_timestamp_ns;
    }
    return oldest == std::numeric_limits<uint64_t>::max() ? 0 : oldest;
  }

 private:
  struct Slot {
    uint32_t tid = 0;
    uint64_t last_timestamp_ns = 0;
    uint64_t pending_delta_ns = 0;
    bool occupied = false;
    bool pending = false;
  };

  static bool IsSelector(ArmsX2EventKind kind) {
    return kind == ArmsX2EventKind::kBlit || kind == ArmsX2EventKind::kPrivileged ||
        kind == ArmsX2EventKind::kZero;
  }

  static bool ValidValue(const ArmsX2Event& event) {
    if (event.kind == ArmsX2EventKind::kLost) return false;
    if (event.kind == ArmsX2EventKind::kDelta) {
      return event.value != 0 && event.value <= kMaxSourceWindowNs;
    }
    if (event.kind == ArmsX2EventKind::kZero) return event.value == 0;
    return IsSelector(event.kind) && event.value != 0 && event.value <= kMaxCount;
  }

  ArmsX2CounterTerminal Fail(ArmsX2CounterTerminal reason) {
    for (Slot& slot : slots_) slot = Slot{};
    aggregate_count_ = 0;
    aggregate_duration_ns_ = 0;
    interval_has_publication_ = false;
    terminal_ = reason;
    return terminal_;
  }

  Slot* Find(uint32_t tid, bool create) {
    Slot* free_slot = nullptr;
    for (Slot& slot : slots_) {
      if (slot.occupied && slot.tid == tid) return &slot;
      if (!slot.occupied && free_slot == nullptr) free_slot = &slot;
    }
    if (!create || free_slot == nullptr) return nullptr;
    free_slot->tid = tid;
    free_slot->occupied = true;
    return free_slot;
  }

  ArmsX2CounterTerminal ConsumeDelta(Slot* slot, const ArmsX2Event& event) {
    if (terminal_ != ArmsX2CounterTerminal::kNone) return terminal_;
    if (slot->pending && slot->pending_delta_ns >= kMinSourceWindowNs) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    slot->last_timestamp_ns = event.timestamp_ns;
    slot->pending_delta_ns = event.value;
    slot->pending = true;
    return ArmsX2CounterTerminal::kNone;
  }

  ArmsX2CounterTerminal ConsumeSelector(Slot* slot, const ArmsX2Event& event) {
    if (terminal_ != ArmsX2CounterTerminal::kNone) return terminal_;
    if (!slot->pending || event.timestamp_ns <= slot->last_timestamp_ns) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    if (event.timestamp_ns - slot->last_timestamp_ns > kMaxSourceWindowNs) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    const uint64_t delta = slot->pending_delta_ns;
    if (delta < kMinSourceWindowNs || delta > kMaxSourceWindowNs) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    if (aggregate_duration_ns_ > kMaxAggregateNs - delta) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    if (event.kind != ArmsX2EventKind::kZero &&
        aggregate_count_ > kMaxCount - event.value) {
      return Fail(ArmsX2CounterTerminal::kLost);
    }
    aggregate_duration_ns_ += delta;
    if (event.kind != ArmsX2EventKind::kZero) aggregate_count_ += event.value;
    interval_has_publication_ = true;
    slot->last_timestamp_ns = event.timestamp_ns;
    slot->pending_delta_ns = 0;
    slot->pending = false;
    return ArmsX2CounterTerminal::kNone;
  }

  std::array<Slot, kMaxThreads> slots_{};
  uint64_t aggregate_count_ = 0;
  uint64_t aggregate_duration_ns_ = 0;
  bool interval_has_publication_ = false;
  ArmsX2CounterTerminal terminal_ = ArmsX2CounterTerminal::kNone;
};

}  // namespace mimir::render_fps
