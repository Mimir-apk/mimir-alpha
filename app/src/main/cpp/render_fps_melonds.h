#pragma once

#include <array>
#include <cstddef>
#include <cstdint>
#include <limits>

namespace mimir::render_fps {

// These are resolved from the validated mapped libmelonDS-lib.so. The addresses are
// deliberately absent here: only the ELF symbol and Build ID identify a supported build.
inline constexpr char kMelonDsLibraryName[] = "libmelonDS-lib.so";
inline constexpr char kMelonDsPackageName[] = "me.magnum.melonds";
inline constexpr char kMelonDsRunFrameSymbol[] =
    "_ZN14MelonDSAndroid13MelonInstance8runFrameEv";
inline constexpr char kMelonDsDiscardRenderedFrameSymbol[] =
    "_ZN10FrameQueue20discardRenderedFrameEP5Frame";
inline constexpr char kMelonDsPushRenderedFrameSymbol[] =
    "_ZN10FrameQueue17pushRenderedFrameEP5Frame";
inline constexpr char kMelonDsCanonicalBuildId[] =
    "0bc62854872890a331f3a2f5e84f31c32e395733";
inline constexpr char kMelonDsThorBetaBuildId[] =
    "f660857c086b923f906a2a9051299036e196909e";

enum class MelonProducerEventKind {
  kRunFrameEntry,
  kRunFrameReturn,
  kDiscardEntry,
  kPushReturn,
  kLost,
};

struct MelonProducerEvent {
  MelonProducerEventKind kind = MelonProducerEventKind::kLost;
  uint32_t pid = 0;
  uint32_t tid = 0;
  uint64_t timestamp_ns = 0;
};

// kWarmup is only used for the first partial observation. It suppresses a partial
// publication while the next complete runFrame invocation establishes the stream.
// Every later malformed sequence is terminal for its interval.
enum class MelonProducerTerminal {
  kNone,
  kWarmup,
  kLost,
  kWrongProcess,
  kOrphan,
  kNested,
  kDuplicate,
  kPartial,
  kTimeout,
};

// Pure event state for the four uprobe streams. A push is counted only when its
// enclosing runFrame returns successfully and no discard was observed. This is
// necessary for Thor Beta, whose sleep path performs discard then push, and also
// assigns a call crossing a publication boundary to its return interval.
class MelonProducerCounter {
 public:
  static constexpr size_t kProbeCount = 4;
  static constexpr size_t kMaxRings = 512;
  static constexpr size_t kRingPages = 8;
  static constexpr size_t kMaxThreads = kMaxRings / kProbeCount;

  static_assert(kMaxThreads != 0 && kMaxThreads * kProbeCount == kMaxRings);

  MelonProducerTerminal Consume(const MelonProducerEvent& event, uint32_t expected_pid) {
    if (event.kind == MelonProducerEventKind::kLost) {
      return Invalidate(MelonProducerTerminal::kLost);
    }
    if (event.pid != expected_pid) {
      return Invalidate(MelonProducerTerminal::kWrongProcess);
    }
    if (event.tid == 0 || event.timestamp_ns == 0) {
      return PartialFault(MelonProducerTerminal::kPartial);
    }

    switch (event.kind) {
      case MelonProducerEventKind::kRunFrameEntry:
        return OnRunFrameEntry(event);
      case MelonProducerEventKind::kRunFrameReturn:
        return OnRunFrameReturn(event);
      case MelonProducerEventKind::kDiscardEntry:
        return OnDiscard(event);
      case MelonProducerEventKind::kPushReturn:
        return OnPushReturn(event);
      case MelonProducerEventKind::kLost:
        break;
    }
    return Invalidate(MelonProducerTerminal::kPartial);
  }

  // Starts an ordinary publication interval. Active calls deliberately survive;
  // their push is not counted until the matching runFrame return arrives.
  void BeginInterval() {
    count_ = 0;
    terminal_ = MelonProducerTerminal::kNone;
  }

  // Invalidates a pending invocation whose return did not arrive within the
  // bounded capture deadline. A zero timeout is not meaningful to this seam.
  MelonProducerTerminal ExpirePending(uint64_t now_ns, uint64_t timeout_ns) {
    if (!HasAnyActiveInvocation()) return terminal_;
    if (now_ns == 0 || timeout_ns == 0) {
      return Invalidate(MelonProducerTerminal::kTimeout);
    }
    const uint64_t oldest = OldestActiveTimestampNs();
    if (oldest != 0 && now_ns >= oldest && now_ns - oldest > timeout_ns) {
      return Invalidate(MelonProducerTerminal::kTimeout);
    }
    return terminal_;
  }

  void Reset() {
    for (Slot& slot : slots_) slot = Slot{};
    count_ = 0;
    terminal_ = MelonProducerTerminal::kNone;
    saw_complete_invocation_ = false;
    saw_initial_partial_ = false;
  }

  uint64_t count() const { return count_; }
  MelonProducerTerminal terminal() const { return terminal_; }

  bool HasActiveInvocation(uint32_t tid) const {
    for (const Slot& slot : slots_) {
      if (slot.active && slot.tid == tid) return true;
    }
    return false;
  }

  bool HasAnyActiveInvocation() const {
    for (const Slot& slot : slots_) {
      if (slot.active) return true;
    }
    return false;
  }

  uint64_t OldestActiveTimestampNs() const {
    uint64_t oldest = std::numeric_limits<uint64_t>::max();
    for (const Slot& slot : slots_) {
      if (slot.active && slot.entry_timestamp_ns < oldest) oldest = slot.entry_timestamp_ns;
    }
    return oldest == std::numeric_limits<uint64_t>::max() ? 0 : oldest;
  }

 private:
  struct Slot {
    uint32_t tid = 0;
    uint64_t entry_timestamp_ns = 0;
    bool active = false;
    bool initial_partial = false;
    bool discarded = false;
    bool push_seen = false;
  };

  Slot* Find(uint32_t tid, bool create) {
    Slot* free_slot = nullptr;
    for (Slot& slot : slots_) {
      if (slot.active && slot.tid == tid) return &slot;
      if (!slot.active && free_slot == nullptr) free_slot = &slot;
    }
    if (!create || free_slot == nullptr) return nullptr;
    free_slot->tid = tid;
    free_slot->active = true;
    return free_slot;
  }

  void ClearActive() {
    for (Slot& slot : slots_) {
      if (slot.active) slot = Slot{};
    }
  }

  MelonProducerTerminal Invalidate(MelonProducerTerminal reason) {
    ClearActive();
    count_ = 0;
    terminal_ = reason;
    return terminal_;
  }

  MelonProducerTerminal PartialFault(MelonProducerTerminal reason) {
    // A capture can begin in the middle of one native call. Warm up once rather
    // than publishing that partial interval; later partials fail closed.
    if (!saw_complete_invocation_ && !saw_initial_partial_ &&
        (reason == MelonProducerTerminal::kPartial || reason == MelonProducerTerminal::kOrphan)) {
      saw_initial_partial_ = true;
      ClearActive();
      count_ = 0;
      terminal_ = MelonProducerTerminal::kWarmup;
      return terminal_;
    }
    return Invalidate(reason);
  }

  MelonProducerTerminal StartInitialPartial(const MelonProducerEvent& event) {
    if (saw_complete_invocation_ || saw_initial_partial_) {
      return Invalidate(MelonProducerTerminal::kOrphan);
    }
    Slot* slot = Find(event.tid, true);
    if (slot == nullptr) return Invalidate(MelonProducerTerminal::kPartial);
    slot->entry_timestamp_ns = event.timestamp_ns;
    slot->initial_partial = true;
    if (event.kind == MelonProducerEventKind::kDiscardEntry) slot->discarded = true;
    if (event.kind == MelonProducerEventKind::kPushReturn) slot->push_seen = true;
    terminal_ = MelonProducerTerminal::kWarmup;
    return terminal_;
  }

  MelonProducerTerminal OnRunFrameEntry(const MelonProducerEvent& event) {
    if (Find(event.tid, false) != nullptr) {
      return Invalidate(MelonProducerTerminal::kNested);
    }
    Slot* slot = Find(event.tid, true);
    if (slot == nullptr) return Invalidate(MelonProducerTerminal::kPartial);
    slot->entry_timestamp_ns = event.timestamp_ns;
    return terminal_;
  }

  MelonProducerTerminal OnRunFrameReturn(const MelonProducerEvent& event) {
    Slot* slot = Find(event.tid, false);
    if (slot == nullptr) {
      const MelonProducerTerminal warmup = StartInitialPartial(event);
      if (warmup != MelonProducerTerminal::kWarmup) return warmup;
      slot = Find(event.tid, false);
      // The return closes the unknown invocation that was already in flight when
      // capture began. Its partial output is never published.
      if (slot == nullptr || !slot->initial_partial) {
        return Invalidate(MelonProducerTerminal::kPartial);
      }
      *slot = Slot{};
      saw_initial_partial_ = true;
      return terminal_;
    }
    if (slot->initial_partial) {
      *slot = Slot{};
      saw_initial_partial_ = true;
      return terminal_;
    }
    if (!slot->discarded && !slot->push_seen) {
      return PartialFault(MelonProducerTerminal::kPartial);
    }
    if (!slot->discarded && slot->push_seen) ++count_;
    slot->active = false;
    slot->tid = 0;
    slot->entry_timestamp_ns = 0;
    slot->discarded = false;
    slot->push_seen = false;
    saw_complete_invocation_ = true;
    return terminal_;
  }

  MelonProducerTerminal OnDiscard(const MelonProducerEvent& event) {
    Slot* slot = Find(event.tid, false);
    if (slot == nullptr) {
      return StartInitialPartial(event);
    }
    if (slot->initial_partial) {
      if (slot->discarded || slot->push_seen) {
        return Invalidate(MelonProducerTerminal::kDuplicate);
      }
      slot->discarded = true;
      return terminal_;
    }
    if (slot->discarded || slot->push_seen) {
      return Invalidate(MelonProducerTerminal::kDuplicate);
    }
    slot->discarded = true;
    return terminal_;
  }

  MelonProducerTerminal OnPushReturn(const MelonProducerEvent& event) {
    Slot* slot = Find(event.tid, false);
    if (slot == nullptr) {
      return StartInitialPartial(event);
    }
    if (slot->push_seen) return Invalidate(MelonProducerTerminal::kDuplicate);
    // Thor's legacy discard→push sequence is intentionally remembered but not
    // counted. Count is deferred until runFrame return to support interval carry.
    slot->push_seen = true;
    return terminal_;
  }

  std::array<Slot, kMaxThreads> slots_{};
  uint64_t count_ = 0;
  MelonProducerTerminal terminal_ = MelonProducerTerminal::kNone;
  bool saw_complete_invocation_ = false;
  bool saw_initial_partial_ = false;
};

}  // namespace mimir::render_fps
