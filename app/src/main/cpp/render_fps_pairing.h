#pragma once

#include <array>
#include <cstdint>
#include <limits>

namespace mimir::render_fps {

enum class PairEventKind { kEntry, kReturn, kLost };
struct PairEvent { PairEventKind kind; uint32_t pid; uint32_t tid; uint64_t timestamp_ns; uint64_t value; };
enum class PairTerminal { kNone, kLost, kPartial, kMismatched, kAmbiguous, kWrongProcess };

// PPSSPP's producer has no return value to pair. It still uses the same bounded PID/TID rings,
// loss terminal and interval reset as the queueBuffer path. Keeping this tiny state machine pure
// makes the entry-only and exactly-once contract testable without opening a perf event.
enum class ProducerEventKind { kEntry, kLost };
struct ProducerEvent { ProducerEventKind kind; uint32_t pid; uint32_t tid; uint64_t timestamp_ns; };
enum class ProducerTerminal { kNone, kLost, kWrongProcess, kInvalid };

class ProducerCounter {
 public:
  ProducerTerminal Consume(const ProducerEvent& event, uint32_t expected_pid) {
    if (event.kind == ProducerEventKind::kLost) {
      count_ = 0;
      terminal_ = ProducerTerminal::kLost;
      return terminal_;
    }
    if (event.pid != expected_pid) {
      count_ = 0;
      terminal_ = ProducerTerminal::kWrongProcess;
      return terminal_;
    }
    if (event.tid == 0 || event.timestamp_ns == 0) {
      count_ = 0;
      terminal_ = ProducerTerminal::kInvalid;
      return terminal_;
    }
    ++count_;
    return ProducerTerminal::kNone;
  }

  ProducerTerminal ConsumeAtCutoff(const ProducerEvent& event, uint32_t expected_pid,
                                   uint64_t cutoff_ns, bool* carried) {
    if (carried == nullptr) return ProducerTerminal::kInvalid;
    *carried = false;
    if (event.kind == ProducerEventKind::kEntry && event.timestamp_ns > cutoff_ns) {
      *carried = true;
      return ProducerTerminal::kNone;
    }
    return Consume(event, expected_pid);
  }

  // Flycast's return probe supplies the ABI return value in the event. Validate the
  // event identity and timestamp before filtering the valid false return; a malformed
  // value or ownership mismatch must not disappear as an ordinary failed present.
  ProducerTerminal ConsumeReturnAtCutoff(const ProducerEvent& event, uint64_t return_value,
                                         uint32_t expected_pid, uint64_t cutoff_ns,
                                         bool* carried) {
    if (carried == nullptr) return ProducerTerminal::kInvalid;
    *carried = false;
    if (event.kind != ProducerEventKind::kEntry) {
      count_ = 0;
      terminal_ = ProducerTerminal::kInvalid;
      return terminal_;
    }
    if (event.pid != expected_pid) {
      count_ = 0;
      terminal_ = ProducerTerminal::kWrongProcess;
      return terminal_;
    }
    if (event.tid == 0 || event.timestamp_ns == 0 || return_value > 1) {
      count_ = 0;
      terminal_ = ProducerTerminal::kInvalid;
      return terminal_;
    }
    if (return_value == 0) return ProducerTerminal::kNone;
    if (event.timestamp_ns > cutoff_ns) {
      *carried = true;
      return ProducerTerminal::kNone;
    }
    ++count_;
    return ProducerTerminal::kNone;
  }

  // Mupen64Plus GLideN64's completed swap is a void function observed at return. The
  // synthetic entry kind carries that completion through the ordinary cutoff/identity
  // checks; there is intentionally no ABI return-value argument to interpret.
  ProducerTerminal ConsumeVoidReturnAtCutoff(const ProducerEvent& event, uint32_t expected_pid,
                                             uint64_t cutoff_ns, bool* carried) {
    // A completion is already established by the return probe. Reuse validation before
    // cutoff carry with a constant success, never the void function's unspecified X0.
    return ConsumeReturnAtCutoff(event, 1, expected_pid, cutoff_ns, carried);
  }

  void BeginInterval() { count_ = 0; terminal_ = ProducerTerminal::kNone; }
  void Reset() { BeginInterval(); }
  uint64_t count() const { return count_; }
  ProducerTerminal terminal() const { return terminal_; }

 private:
  uint64_t count_ = 0;
  ProducerTerminal terminal_ = ProducerTerminal::kNone;
};

class Pairing {
 public:
  static constexpr size_t kMaxThreads = 256;
  static constexpr size_t kMaxDepth = 8;

  PairTerminal Consume(const PairEvent& event, uint32_t expected_pid) {
    if (event.kind == PairEventKind::kLost) { Reset(); terminal_ = PairTerminal::kLost; return terminal_; }
    if (event.pid != expected_pid) { Reset(); terminal_ = PairTerminal::kWrongProcess; return terminal_; }
    Slot* slot = Find(event.tid, event.kind == PairEventKind::kEntry);
    if (event.kind == PairEventKind::kEntry) {
      if (event.timestamp_ns == 0 || event.value == 0 || slot == nullptr || slot->depth == kMaxDepth ||
          (slot->depth != 0 && event.timestamp_ns < slot->times[slot->depth - 1])) {
        Reset(); terminal_ = PairTerminal::kPartial; return terminal_;
      }
      slot->surfaces[slot->depth] = event.value;
      slot->times[slot->depth] = event.timestamp_ns;
      ++slot->depth;
      return PairTerminal::kNone;
    }
    if (event.timestamp_ns == 0 || slot == nullptr || slot->depth == 0 || event.timestamp_ns < slot->times[slot->depth - 1]) {
      Reset(); terminal_ = PairTerminal::kMismatched; return terminal_;
    }
    const uint64_t surface = slot->surfaces[--slot->depth];
    if (event.value != 0) return PairTerminal::kNone;
    if (selected_surface_ == 0) selected_surface_ = surface;
    if (selected_surface_ != surface) { Reset(); terminal_ = PairTerminal::kAmbiguous; return terminal_; }
    ++count_;
    return PairTerminal::kNone;
  }

  PairTerminal FinishWindow() {
    for (const Slot& slot : slots_) if (slot.depth != 0) { Reset(); terminal_ = PairTerminal::kPartial; return terminal_; }
    return terminal_;
  }

  void BeginInterval() { count_ = 0; terminal_ = PairTerminal::kNone; }
  void Reset() {
    for (Slot& slot : slots_) slot.depth = 0;
    count_ = 0;
    selected_surface_ = 0;
    terminal_ = PairTerminal::kNone;
  }
  PairTerminal terminal() const { return terminal_; }
  bool HasActiveEntry(uint32_t tid) const {
    for (const Slot& slot : slots_) if (slot.depth != 0 && slot.tid == tid) return true;
    return false;
  }
  uint64_t OldestActiveTimestampNs() const {
    uint64_t oldest = std::numeric_limits<uint64_t>::max();
    for (const Slot& slot : slots_) {
      if (slot.depth != 0 && slot.times[0] < oldest) oldest = slot.times[0];
    }
    return oldest == std::numeric_limits<uint64_t>::max() ? 0 : oldest;
  }
  uint64_t count() const { return count_; }
  uint64_t selectedSurface() const { return selected_surface_; }

 private:
  struct Slot { uint32_t tid = 0; size_t depth = 0; std::array<uint64_t, kMaxDepth> surfaces{}; std::array<uint64_t, kMaxDepth> times{}; };
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
  std::array<Slot, kMaxThreads> slots_{};
  uint64_t selected_surface_ = 0;
  uint64_t count_ = 0;
  PairTerminal terminal_ = PairTerminal::kNone;
};

}  // namespace mimir::render_fps
