#include "render_fps_protocol.h"

#include <charconv>
#include <limits>
#include <sstream>
#include <vector>

namespace mimir::render_fps {
namespace {

bool Decimal(std::string_view text, uint64_t* value) {
  if (text.empty()) return false;
  uint64_t parsed = 0;
  const char* first = text.data();
  const char* last = first + text.size();
  const auto result = std::from_chars(first, last, parsed, 10);
  if (result.ec != std::errc() || result.ptr != last) return false;
  *value = parsed;
  return true;
}

bool HexToken(std::string_view text) {
  if (text.size() < 8 || text.size() > 64) return false;
  for (char c : text) {
    if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) return false;
  }
  return true;
}

bool Package(std::string_view text) {
  if (text.empty() || text.size() > 128) return false;
  bool component = false;
  for (char c : text) {
    if (c == '.') {
      if (!component) return false;
      component = false;
    } else if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') ||
               (c >= '0' && c <= '9') || c == '_') {
      component = true;
    } else {
      return false;
    }
  }
  return component;
}

bool ParseLines(std::string_view text, std::string_view header,
                std::vector<std::pair<std::string_view, std::string_view>>* fields) {
  if (text.empty() || text.size() > kMaxProtocolBytes || text.back() != '\n') return false;
  size_t cursor = 0;
  const size_t first_end = text.find('\n');
  if (first_end == std::string_view::npos || text.substr(0, first_end) != header) return false;
  cursor = first_end + 1;
  while (cursor < text.size()) {
    const size_t end = text.find('\n', cursor);
    if (end == std::string_view::npos || end == cursor) return false;
    const std::string_view line = text.substr(cursor, end - cursor);
    const size_t equals = line.find('=');
    if (equals == std::string_view::npos || equals == 0) return false;
    fields->push_back({line.substr(0, equals), line.substr(equals + 1)});
    cursor = end + 1;
  }
  return true;
}

template <typename Target>
bool Required(const std::vector<std::pair<std::string_view, std::string_view>>& fields,
              std::string_view key, std::string* string_value, Target* numeric_value,
              bool (*validator)(std::string_view)) {
  size_t matches = 0;
  std::string_view value;
  for (const auto& field : fields) {
    if (field.first == key) {
      ++matches;
      value = field.second;
    }
  }
  if (matches != 1 || !validator(value)) return false;
  if (string_value) *string_value = std::string(value);
  if (numeric_value) {
    uint64_t parsed = 0;
    if (!Decimal(value, &parsed) || parsed > std::numeric_limits<Target>::max()) return false;
    *numeric_value = static_cast<Target>(parsed);
  }
  return true;
}

bool AnyStatus(std::string_view status) {
  return status == "ok" || status == "warming" || status == "unavailable" ||
         status == "ambiguous" || status == "lost" || status == "unsupported" || status == "stale";
}

bool ValidRecordSemantics(std::string_view status, uint64_t interval, uint64_t count) {
  if (status == "ok") return interval > 0 && interval <= 5000 && count <= 1000000;
  return interval <= 5000 && count == 0;
}

}  // namespace

bool ParseRequest(std::string_view text, Request* request) {
  std::vector<std::pair<std::string_view, std::string_view>> fields;
  if (!ParseLines(text, kRequestHeader, &fields) || fields.size() != 8) return false;
  for (const auto& field : fields) {
    if (field.first != "token" && field.first != "package" && field.first != "uid" &&
        field.first != "pid" && field.first != "start" && field.first != "generation" &&
        field.first != "lease_boottime_ms" && field.first != "interval_ms") return false;
  }
  uint64_t uid = 0, pid = 0, start = 0, generation = 0, lease = 0, interval = 0;
  std::string token, package_name;
  if (!Required(fields, "token", &token, static_cast<uint64_t*>(nullptr), HexToken) ||
      !Required(fields, "package", &package_name, static_cast<uint64_t*>(nullptr), Package) ||
      !Required(fields, "uid", nullptr, &uid, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n <= UINT32_MAX; }) ||
      !Required(fields, "pid", nullptr, &pid, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n > 0 && n <= INT32_MAX; }) ||
      !Required(fields, "start", nullptr, &start, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n); }) ||
      !Required(fields, "generation", nullptr, &generation, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n); }) ||
      !Required(fields, "lease_boottime_ms", nullptr, &lease, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n); }) ||
      !Required(fields, "interval_ms", nullptr, &interval, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n == 1000; })) {
    return false;
  }
  request->token = std::move(token);
  request->package_name = std::move(package_name);
  request->uid = static_cast<uint32_t>(uid);
  request->pid = static_cast<int32_t>(pid);
  request->start_ticks = start;
  request->generation = generation;
  request->lease_boottime_ms = lease;
  request->interval_ms = static_cast<uint32_t>(interval);
  return true;
}

bool ParseRecord(std::string_view text, Record* record) {
  std::vector<std::pair<std::string_view, std::string_view>> fields;
  if (!ParseLines(text, kRecordHeader, &fields) || fields.size() != 10) return false;
  for (const auto& field : fields) {
    if (field.first != "token" && field.first != "package" && field.first != "uid" &&
        field.first != "pid" && field.first != "start" && field.first != "generation" &&
        field.first != "boot" && field.first != "interval" && field.first != "count" &&
        field.first != "status") return false;
  }
  uint64_t uid = 0, pid = 0, start = 0, generation = 0, boot = 0, interval = 0, count = 0;
  std::string token, package_name, status;
  if (!Required(fields, "token", &token, static_cast<uint64_t*>(nullptr), HexToken) ||
      !Required(fields, "package", &package_name, static_cast<uint64_t*>(nullptr), Package) ||
      !Required(fields, "uid", nullptr, &uid, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n <= UINT32_MAX; }) ||
      !Required(fields, "pid", nullptr, &pid, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n > 0 && n <= INT32_MAX; }) ||
      !Required(fields, "start", nullptr, &start, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n); }) ||
      !Required(fields, "generation", nullptr, &generation, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n); }) ||
      !Required(fields, "boot", nullptr, &boot, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n); }) ||
      !Required(fields, "interval", nullptr, &interval, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n <= 5000; }) ||
      !Required(fields, "count", nullptr, &count, [](std::string_view v) { uint64_t n = 0; return Decimal(v, &n) && n <= 1000000; }) ||
      !Required(fields, "status", &status, static_cast<uint64_t*>(nullptr), AnyStatus)) {
    return false;
  }
  if (!ValidRecordSemantics(status, interval, count)) return false;
  record->token = std::move(token);
  record->package_name = std::move(package_name);
  record->uid = static_cast<uint32_t>(uid);
  record->pid = static_cast<int32_t>(pid);
  record->start_ticks = start;
  record->generation = generation;
  record->sample_boottime_ms = boot;
  record->interval_ms = interval;
  record->count = count;
  record->status = std::move(status);
  return true;
}

std::string FormatRecord(const Record& record) {
  if (!HexToken(record.token) || !Package(record.package_name) || record.pid <= 0 ||
      !AnyStatus(record.status) || !ValidRecordSemantics(record.status, record.interval_ms, record.count)) {
    return {};
  }
  std::ostringstream output;
  output << kRecordHeader << '\n'
         << "token=" << record.token << '\n'
         << "package=" << record.package_name << '\n'
         << "uid=" << record.uid << '\n'
         << "pid=" << record.pid << '\n'
         << "start=" << record.start_ticks << '\n'
         << "generation=" << record.generation << '\n'
         << "boot=" << record.sample_boottime_ms << '\n'
         << "interval=" << record.interval_ms << '\n'
         << "count=" << record.count << '\n'
         << "status=" << record.status << '\n';
  const std::string result = output.str();
  return result.size() <= kMaxRecordBytes ? result : std::string();
}

bool RequestIsLiveAt(const Request& request, uint64_t now_boottime_ms) {
  return request.lease_boottime_ms > now_boottime_ms &&
      request.lease_boottime_ms - now_boottime_ms <= kMaxLeaseMs;
}

bool RequestChanged(const Request& previous, const Request& next) {
  return previous.token != next.token || previous.package_name != next.package_name ||
      previous.uid != next.uid || previous.pid != next.pid || previous.start_ticks != next.start_ticks ||
      previous.generation != next.generation || previous.interval_ms != next.interval_ms;
}

bool RequestCanRenew(const Request& current, const Request& candidate, uint64_t now_boottime_ms) {
  return !RequestChanged(current, candidate) &&
      candidate.lease_boottime_ms >= current.lease_boottime_ms &&
      RequestIsLiveAt(candidate, now_boottime_ms);
}

}  // namespace mimir::render_fps
