#pragma once

#include <cstdint>
#include <limits>
#include <string_view>

namespace mimir::render_fps {

inline constexpr std::string_view kDuckStationPackageName =
    "com.github.stenzek.duckstation";
inline constexpr std::string_view kDuckStationLibraryName = "/libduckstation.so";
inline constexpr std::string_view kDuckStationArm64Member =
    "lib/arm64-v8a/libduckstation.so";
inline constexpr std::string_view kDuckStationBuildIdHex =
    "13de34abd3225e099f208e991fd48ce817be2584";
inline constexpr uint64_t kDuckStationUpdateVa = 0x30a70c;
inline constexpr uint64_t kDuckStationUpdateRange = 0x338;
inline constexpr uint64_t kDuckStationHandlerVa = 0x29091c;
inline constexpr uint64_t kDuckStationHandlerRange = 0x108;
inline constexpr uint64_t kDuckStationTextAddr = 0x170000;
inline constexpr uint64_t kDuckStationTextOffset = 0x170000;
inline constexpr uint64_t kDuckStationTextSize = 0x3fded4;

enum class DuckCounterTerminal { kNone, kLost, kWrongProcess, kInvalid };

struct DuckCounterEvent {
  uint32_t pid = 0;
  uint32_t tid = 0;
  uint64_t timestamp_ns = 0;
  uint64_t value = 0;
};

// Converts DuckStation's monotonically increasing internal_frame_number (G) into
// interval deltas while preserving a seed across one-second publication windows.
class DuckCounter {
 public:
  static constexpr uint64_t kMaxDelta = 1'000'000;

  DuckCounterTerminal ConsumeAtCutoff(const DuckCounterEvent& event,
                                      uint32_t expected_pid, uint64_t cutoff_ns,
                                      bool* carried) {
    if (carried == nullptr) return Fail(DuckCounterTerminal::kInvalid);
    *carried = false;
    if (expected_pid == 0 || event.pid == 0) return Fail(DuckCounterTerminal::kInvalid);
    if (event.pid != expected_pid) return Fail(DuckCounterTerminal::kWrongProcess);
    if (event.tid == 0 || event.timestamp_ns == 0 || event.value > UINT32_MAX) {
      return Fail(DuckCounterTerminal::kInvalid);
    }
    if (seeded_ && event.tid != producer_tid_) return Fail(DuckCounterTerminal::kLost);
    if (seeded_ && event.timestamp_ns <= last_time_ns_) {
      return Fail(DuckCounterTerminal::kLost);
    }
    // Validate the event envelope before carrying it so no state mutation is
    // hidden behind the publication cutoff.
    if (event.timestamp_ns > cutoff_ns) {
      *carried = true;
      return DuckCounterTerminal::kNone;
    }
    return ConsumeValidated(event);
  }

  DuckCounterTerminal ConsumeLoss() { return Fail(DuckCounterTerminal::kLost); }

  void BeginInterval() {
    if (terminal_ != DuckCounterTerminal::kNone) {
      Reset();
      return;
    }
    interval_delta_ = 0;
    interval_has_event_ = false;
    seeded_this_interval_ = false;
  }

  void Reset() {
    seeded_ = false;
    last_internal_ = 0;
    producer_tid_ = 0;
    last_time_ns_ = 0;
    interval_delta_ = 0;
    interval_has_event_ = false;
    seeded_this_interval_ = false;
    terminal_ = DuckCounterTerminal::kNone;
  }

  uint64_t count() const { return interval_delta_; }
  bool seeded_this_interval() const { return seeded_this_interval_; }
  bool interval_has_event() const { return interval_has_event_; }
  DuckCounterTerminal terminal() const { return terminal_; }

 private:
  DuckCounterTerminal Fail(DuckCounterTerminal status) {
    seeded_ = false;
    last_internal_ = 0;
    producer_tid_ = 0;
    last_time_ns_ = 0;
    interval_delta_ = 0;
    interval_has_event_ = false;
    seeded_this_interval_ = false;
    terminal_ = status;
    return terminal_;
  }

  DuckCounterTerminal ConsumeValidated(const DuckCounterEvent& event) {
    const uint32_t value = static_cast<uint32_t>(event.value);
    if (terminal_ != DuckCounterTerminal::kNone) return terminal_;
    if (!seeded_) {
      seeded_ = true;
      last_internal_ = value;
      producer_tid_ = event.tid;
      last_time_ns_ = event.timestamp_ns;
      interval_has_event_ = true;
      seeded_this_interval_ = true;
      return DuckCounterTerminal::kNone;
    }
    if (value < last_internal_) return Fail(DuckCounterTerminal::kLost);
    const uint64_t step = static_cast<uint64_t>(value - last_internal_);
    if (step > kMaxDelta || interval_delta_ > kMaxDelta - step) {
      return Fail(DuckCounterTerminal::kLost);
    }
    interval_delta_ += step;
    last_internal_ = value;
    last_time_ns_ = event.timestamp_ns;
    interval_has_event_ = true;
    return DuckCounterTerminal::kNone;
  }

  bool seeded_ = false;
  uint32_t last_internal_ = 0;
  uint32_t producer_tid_ = 0;
  uint64_t last_time_ns_ = 0;
  uint64_t interval_delta_ = 0;
  bool interval_has_event_ = false;
  bool seeded_this_interval_ = false;
  DuckCounterTerminal terminal_ = DuckCounterTerminal::kNone;
};

}  // namespace mimir::render_fps
