#pragma once

#include <cstdint>
#include <cstddef>
#include <string>
#include <string_view>

namespace mimir::render_fps {

inline constexpr std::string_view kRequestHeader = "MIMIR_RENDER_FPS_REQUEST_V1";
inline constexpr std::string_view kRecordHeader = "MIMIR_RENDER_FPS_RECORD_V1";
inline constexpr size_t kMaxProtocolBytes = 4096;
inline constexpr size_t kMaxRecordBytes = 512;
inline constexpr uint64_t kMaxLeaseMs = 10000;

struct Request {
  std::string token;
  std::string package_name;
  uint32_t uid = 0;
  int32_t pid = -1;
  uint64_t start_ticks = 0;
  uint64_t generation = 0;
  uint64_t lease_boottime_ms = 0;
  uint32_t interval_ms = 1000;
};

struct Record {
  std::string token;
  std::string package_name;
  uint32_t uid = 0;
  int32_t pid = -1;
  uint64_t start_ticks = 0;
  uint64_t generation = 0;
  uint64_t sample_boottime_ms = 0;
  uint64_t interval_ms = 0;
  uint64_t count = 0;
  std::string status;
};

bool ParseRequest(std::string_view text, Request* request);
bool ParseRecord(std::string_view text, Record* record);
std::string FormatRecord(const Record& record);

// These are the daemon's bounded lifecycle predicates, kept pure so their exact lease and
// generation edges can be tested without a device or a live perf event.
bool RequestIsLiveAt(const Request& request, uint64_t now_boottime_ms);
bool RequestChanged(const Request& previous, const Request& next);
bool RequestCanRenew(const Request& current, const Request& candidate, uint64_t now_boottime_ms);

}  // namespace mimir::render_fps
