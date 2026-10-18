#include "render_fps_protocol.h"
#include "render_fps_pairing.h"
#include "render_fps_azahar.h"
#include "render_fps_armsx2.h"
#include "render_fps_cemu.h"
#include "render_fps_duckstation.h"
#include "render_fps_eden.h"
#include "render_fps_flycast.h"
#include "render_fps_elf.h"
#include "render_fps_melonds.h"
#include "render_fps_mupen.h"
#include "render_fps_packages.h"
#include "render_fps_retroarch.h"
#include "render_fps_x1box.h"

#include <algorithm>
#include <android/log.h>
#include <array>
#include <atomic>
#include <cerrno>
#include <charconv>
#include <cinttypes>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <dirent.h>
#include <fcntl.h>
#include <limits>
#include <map>
#include <memory>
#include <poll.h>
#include <string>
#include <string_view>
#include <sys/ioctl.h>
#include <sys/file.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <sys/sysmacros.h>
#include <time.h>
#include <unistd.h>
#include <utility>
#include <vector>

#include <linux/perf_event.h>

#ifndef __NR_perf_event_open
#if defined(__aarch64__)
#define __NR_perf_event_open 241
#elif defined(__x86_64__)
#define __NR_perf_event_open 298
#endif
#endif

namespace {
using mimir::render_fps::Record;
using mimir::render_fps::Request;

constexpr char kLibraryPath[] = "/system/lib64/libgui.so";
constexpr char kPpssppPackage[] = "org.ppsspp.ppsspp";
constexpr char kPpssppGoldPackage[] = "org.ppsspp.ppssppgold";
constexpr char kPpssppLibraryName[] = "/libppsspp_jni.so";
constexpr char kPpssppArm64Member[] = "lib/arm64-v8a/libppsspp_jni.so";
constexpr char kMelonDsArm64Member[] = "lib/arm64-v8a/libmelonDS-lib.so";
constexpr char kEdenArm64Member[] = "lib/arm64-v8a/libyuzu-android.so";
constexpr char kAzaharArm64Member[] = "lib/arm64-v8a/libcitra-android.so";
constexpr char kCemuArm64Member[] = "lib/arm64-v8a/libCemuAndroid.so";
constexpr char kFlycastPackage[] = "com.flycast.emulator";
constexpr char kFlycastLibraryName[] = "/libflycast.so";
constexpr char kFlycastArm64Member[] = "lib/arm64-v8a/libflycast.so";
constexpr char kMupen64PlusLibraryName[] = "/libmupen64plus-video-GLideN64.so";
constexpr char kMupen64PlusArm64Member[] =
    "lib/arm64-v8a/libmupen64plus-video-GLideN64.so";
constexpr char kMupenBridgeLibraryName[] = "/libae-bridge.so";
constexpr char kMupenBridgeArm64Member[] = "lib/arm64-v8a/libae-bridge.so";
constexpr size_t kRingPages = 8;
constexpr size_t kMaxTids = 256;
constexpr size_t kMaxRings = kMaxTids * 3;
constexpr size_t kMaxEvents = 4096;
constexpr uint64_t kPublishMs = 1000;
constexpr uint64_t kIdentityCheckMs = 1000;
constexpr uint64_t kIdleExitMs = 2000;
constexpr uint64_t kSuspendGapMs = 1000;
constexpr uint64_t kPendingTimeoutNs = 5'000'000'000ULL;
constexpr char kLockFileName[] = "render_fps_helper.lock";

uint64_t BootMs() {
  timespec ts{};
  if (clock_gettime(CLOCK_BOOTTIME, &ts) != 0) return 0;
  return static_cast<uint64_t>(ts.tv_sec) * 1000ULL + static_cast<uint64_t>(ts.tv_nsec) / 1000000ULL;
}

uint64_t MonoNs() {
  timespec ts{};
  if (clock_gettime(CLOCK_MONOTONIC, &ts) != 0) return 0;
  return static_cast<uint64_t>(ts.tv_sec) * 1000000000ULL + static_cast<uint64_t>(ts.tv_nsec);
}

uint64_t MonoMs() { return MonoNs() / 1000000ULL; }

bool ReadFile(const std::string& path, std::string* out, size_t max_bytes) {
  int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
  if (fd < 0) return false;
  out->clear();
  std::array<char, 512> buffer{};
  while (out->size() <= max_bytes) {
    const ssize_t n = read(fd, buffer.data(), buffer.size());
    if (n < 0) {
      if (errno == EINTR) continue;
      close(fd);
      return false;
    }
    if (n == 0) break;
    out->append(buffer.data(), static_cast<size_t>(n));
    if (out->size() > max_bytes) {
      close(fd);
      return false;
    }
  }
  close(fd);
  return true;
}

bool Decimal(std::string_view text, uint64_t* value) {
  if (text.empty()) return false;
  const auto parsed = std::from_chars(text.data(), text.data() + text.size(), *value, 10);
  return parsed.ec == std::errc() && parsed.ptr == text.data() + text.size();
}

bool ApprovedPackage(std::string_view package_name) {
  return mimir::render_fps::IsApprovedPackage(package_name);
}

bool IsPpssppPackage(std::string_view package_name) {
  return package_name == kPpssppPackage;
}

bool IsMelonDsPackage(std::string_view package_name) {
  return package_name == "me.magnum.melonds" || package_name == "me.magnum.melonds.nightly" ||
      package_name == "me.magnum.melondualds";
}

bool IsEdenPackage(std::string_view package_name) {
  return package_name == "dev.eden.eden_emulator" || package_name == "dev.eden.eden_emulator.nightly" ||
      package_name == "dev.legacy.eden_emulator" || package_name == "dev.legacy.eden_emulator.nightly" ||
      package_name == "com.miHoYo.Yuanshen.nightly";
}

bool ReadStartTicks(int pid, uint64_t* result) {
  std::string text;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/stat", &text, 4096)) return false;
  const size_t close = text.rfind(')');
  if (close == std::string::npos || close + 2 >= text.size()) return false;
  std::string_view rest(text.data() + close + 2, text.size() - close - 2);
  size_t cursor = 0;
  std::string_view field;
  for (int index = 0; index <= 19; ++index) {
    while (cursor < rest.size() && rest[cursor] == ' ') ++cursor;
    const size_t end = rest.find(' ', cursor);
    field = rest.substr(cursor, end == std::string_view::npos ? rest.size() - cursor : end - cursor);
    if (field.empty() || (end == std::string_view::npos && index != 19)) return false;
    cursor = end == std::string_view::npos ? rest.size() : end;
  }
  return Decimal(field, result);
}

bool ReadUid(int pid, uint32_t* result) {
  std::string text;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/status", &text, 16384)) return false;
  size_t start = text.find("Uid:");
  if (start == std::string::npos) return false;
  start += 4;
  while (start < text.size() && text[start] == '\t') ++start;
  const size_t end = text.find_first_of(" \t\n", start);
  uint64_t uid = 0;
  if (!Decimal(std::string_view(text).substr(start, end - start), &uid) || uid > UINT32_MAX) return false;
  *result = static_cast<uint32_t>(uid);
  return true;
}

bool ReadProcessName(int pid, std::string* result) {
  std::string text;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/cmdline", &text, 512)) return false;
  const size_t end = text.find('\0');
  result->assign(text.data(), end == std::string::npos ? text.size() : end);
  return !result->empty();
}

std::vector<int> CurrentThreads(int pid, bool include_render_threads = false) {
  std::vector<int> tids;
  DIR* directory = opendir(("/proc/" + std::to_string(pid) + "/task").c_str());
  if (!directory) return tids;
  tids.reserve(kMaxTids);
  size_t scanned = 0;
  errno = 0;
  while (dirent* entry = readdir(directory)) {
    if (entry->d_name[0] < '0' || entry->d_name[0] > '9') continue;
    uint64_t tid = 0;
    if (!Decimal(entry->d_name, &tid) || tid == 0 || tid > INT32_MAX) continue;
    if (scanned++ >= kMaxTids) {
      closedir(directory);
      return {};
    }
    std::string comm;
    if (!ReadFile("/proc/" + std::to_string(pid) + "/task/" + std::to_string(tid) + "/comm", &comm, 128)) {
      closedir(directory);
      return {};
    }
    while (!comm.empty() && (comm.back() == '\n' || comm.back() == '\r')) comm.pop_back();
    if (!include_render_threads && (comm == "RenderThread" || comm.find("RenderThread") != std::string::npos)) continue;
    if (tids.size() >= kMaxTids) {
      closedir(directory);
      return {};
    }
    tids.push_back(static_cast<int>(tid));
  }
  const bool read_error = errno != 0;
  closedir(directory);
  if (read_error) return {};
  std::sort(tids.begin(), tids.end());
  tids.erase(std::unique(tids.begin(), tids.end()), tids.end());
  return tids;
}

bool MappedLibrary(int pid, const char* path) {
  char canonical[PATH_MAX]{};
  if (!realpath(path, canonical)) return false;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    const std::string_view line(maps.data() + cursor, end - cursor);
    const size_t slash = line.find('/');
    if (slash != std::string_view::npos) {
      size_t path_end = line.find_first_of(" \t", slash);
      const std::string_view mapped = line.substr(slash, path_end == std::string_view::npos ? line.size() - slash : path_end - slash);
      const size_t perms_start = line.find(' ');
      if (mapped == canonical && perms_start != std::string_view::npos && line.substr(perms_start).find("r-x") != std::string_view::npos) return true;
    }
    cursor = end + 1;
  }
  return false;
}

struct FileIdentity {
  dev_t device = 0;
  ino_t inode = 0;
  off_t size = 0;
};

bool ReadFileIdentity(const char* path, FileIdentity* identity) {
  struct stat stat_result{};
  if (stat(path, &stat_result) != 0 || !S_ISREG(stat_result.st_mode)) return false;
  identity->device = stat_result.st_dev;
  identity->inode = stat_result.st_ino;
  identity->size = stat_result.st_size;
  return true;
}

bool SameFileIdentity(const FileIdentity& left, const FileIdentity& right) {
  return left.device == right.device && left.inode == right.inode && left.size == right.size;
}

struct MappedExecutable {
  std::string path;
  uint64_t start = 0;
  uint64_t end = 0;
  uint64_t file_offset = 0;
  dev_t device = 0;
  ino_t inode = 0;
};

struct RetroCoreMapping {
  MappedExecutable mapping;
  FileIdentity identity{};
  std::array<mimir::render_fps::RetroArchExecutableRange,
             mimir::render_fps::kRetroArchMaxExecutableRanges> executable_ranges{};
  size_t executable_range_count = 0;
};

struct ProbeTarget {
  std::string path;
  uint64_t offset = 0;
  uint64_t container_offset = 0;
  uint64_t post_offset = 0;
  std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount> offsets{};
  FileIdentity identity{};
  bool ppsspp = false;
  bool melon_ds = false;
  bool eden = false;
  bool azahar = false;
  bool cemu = false;
  bool duckstation = false;
  bool flycast = false;
  bool mupen64plus = false;
  bool mupen_bridge = false;
  bool x1box = false;
  bool armsx2 = false;
  bool retroarch = false;
  std::vector<RetroCoreMapping> retro_core_maps;
};

class LibraryResolver {
 public:
  bool Resolve(const std::string& path, bool ppsspp, uint64_t* offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0) return false;
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 || st.st_size > 64 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) { base_ = nullptr; return false; }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ppsspp ? mimir::render_fps::ResolvePpssppActualFlipOffset(image, offset)
                           : mimir::render_fps::ResolveQueueBufferOffset(image, offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveMelonDs(const std::string& path,
                      std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount>* offsets) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offsets == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 64 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveMelonDsImage(image, offsets);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  // `mapped` is the identity the caller already matched against /proc/<pid>/maps. The path is
  // opened again here, so the opened file must be that same file (device, inode, size) before any
  // of its bytes are trusted; a replaced or raced library fails closed as identity_changed.
  bool ResolveEden(const std::string& path, uint64_t* offset,
                   mimir::render_fps::EdenResolution* resolution, const FileIdentity& mapped) {
    // An unreadable or oversized file is reported as an unusable image; a parsed one overwrites this.
    if (resolution != nullptr) {
      *resolution = mimir::render_fps::EdenResolution{};
      resolution->reject = mimir::render_fps::EdenShapeReject::kImage;
    }
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    FileIdentity opened{};
    opened.device = st.st_dev;
    opened.inode = st.st_ino;
    opened.size = st.st_size;
    if (!SameFileIdentity(opened, mapped)) {
      close(fd);
      if (resolution != nullptr) resolution->reject = mimir::render_fps::EdenShapeReject::kIdentity;
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveEdenImage(image, offset, resolution);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveImage(std::string_view image, bool ppsspp, uint64_t* offset) const {
    return ppsspp ? mimir::render_fps::ResolvePpssppActualFlipOffset(image, offset)
                  : mimir::render_fps::ResolveQueueBufferOffset(image, offset);
  }

  bool ResolveMelonDsImage(std::string_view image,
                           std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount>* offsets) const {
    if (offsets == nullptr) return false;
    if (!mimir::render_fps::ResolveMelonDsOffsets(image, &(*offsets)[0], &(*offsets)[2], &(*offsets)[3])) {
      return false;
    }
    (*offsets)[1] = (*offsets)[0];
    return true;
  }

  bool ResolveEdenImage(std::string_view image, uint64_t* offset,
                        mimir::render_fps::EdenResolution* resolution) const {
    return mimir::render_fps::ResolveEdenRendererFrameEnd(image, offset, resolution);
  }

  bool ResolveAzahar(const std::string& path, uint64_t* offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveAzaharImage(image, offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveAzaharImage(std::string_view image, uint64_t* offset) const {
    return mimir::render_fps::ResolveAzaharSetBufferSwapOffset(image, offset);
  }

  bool ResolveCemu(const std::string& path, uint64_t* offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveCemuImage(image, offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveCemuImage(std::string_view image, uint64_t* offset) const {
    return mimir::render_fps::ResolveCemuSwapScanBufferOffset(image, offset);
  }

  bool ResolveRetroArch(const std::string& path, uint64_t* offset, uint64_t* post_offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr || post_offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveRetroArchImage(image, offset, post_offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveRetroArchImage(std::string_view image, uint64_t* offset,
                             uint64_t* post_offset) const {
    return mimir::render_fps::ResolveRetroArchFrameOffsets(image, offset, post_offset);
  }

  bool ResolveDuckStation(const std::string& path, uint64_t* offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveDuckStationImage(image, offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveDuckStationImage(std::string_view image, uint64_t* offset) const {
    return mimir::render_fps::ResolveDuckStationUpdateOffset(image, offset);
  }

  bool ResolveArmsX2(const std::string& path,
                     std::array<uint64_t, mimir::render_fps::ArmsX2Counter::kProbeCount>* offsets) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offsets == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveArmsX2Image(image, offsets);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveArmsX2Image(
      std::string_view image,
      std::array<uint64_t, mimir::render_fps::ArmsX2Counter::kProbeCount>* offsets) const {
    return mimir::render_fps::ResolveArmsX2Offsets(image, offsets);
  }

  bool ResolveX1Box(const std::string& path, uint64_t* offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveX1BoxImage(image, offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveX1BoxImage(std::string_view image, uint64_t* offset) const {
    return mimir::render_fps::ResolveX1BoxIncrementOffset(image, offset);
  }

  bool ResolveFlycast(const std::string& path, uint64_t* offset) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveFlycastImage(image, offset);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveFlycastImage(std::string_view image, uint64_t* offset) const {
    return mimir::render_fps::ResolveFlycastPresentOffset(image, offset);
  }

  bool ResolveMupen64Plus(const std::string& path, uint64_t* offset, bool bridge) {
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd < 0 || offset == nullptr) {
      if (fd >= 0) close(fd);
      return false;
    }
    struct stat st{};
    if (fstat(fd, &st) != 0 || !S_ISREG(st.st_mode) || st.st_size <= 0 ||
        st.st_size > 128 * 1024 * 1024) {
      close(fd);
      return false;
    }
    size_ = static_cast<size_t>(st.st_size);
    base_ = static_cast<uint8_t*>(mmap(nullptr, size_, PROT_READ, MAP_PRIVATE, fd, 0));
    close(fd);
    if (base_ == MAP_FAILED) {
      base_ = nullptr;
      return false;
    }
    const std::string_view image(reinterpret_cast<const char*>(base_), size_);
    const bool ok = ResolveMupen64PlusImage(image, offset, bridge);
    munmap(base_, size_);
    base_ = nullptr;
    return ok;
  }

  bool ResolveMupen64PlusImage(std::string_view image, uint64_t* offset, bool bridge) const {
    return bridge ? mimir::render_fps::ResolveMupenBridgeSwapOffset(image, offset)
                  : mimir::render_fps::ResolveMupen64PlusSwapOffset(image, offset);
  }

 private:
  uint8_t* base_ = nullptr;
  size_t size_ = 0;
};

bool ParseHex(std::string_view text, uint64_t* value) {
  if (value == nullptr || text.empty()) return false;
  const auto parsed = std::from_chars(text.data(), text.data() + text.size(), *value, 16);
  return parsed.ec == std::errc() && parsed.ptr == text.data() + text.size();
}

bool ParseMappedExecutableLine(std::string_view line, MappedExecutable* mapping) {
  if (mapping == nullptr) return false;
  const size_t first_space = line.find(' ');
  if (first_space == std::string_view::npos) return false;
  const size_t range_dash = line.find('-');
  if (range_dash == std::string_view::npos || range_dash > first_space) return false;
  if (!ParseHex(line.substr(0, range_dash), &mapping->start) ||
      !ParseHex(line.substr(range_dash + 1, first_space - range_dash - 1), &mapping->end) ||
      mapping->start >= mapping->end) return false;
  const size_t perms_begin = line.find_first_not_of(' ', first_space);
  if (perms_begin == std::string_view::npos || perms_begin + 4 > line.size() ||
      line.substr(perms_begin, 4) != "r-xp") return false;
  const size_t cursor = line.find_first_not_of(' ', perms_begin + 4);
  if (cursor == std::string_view::npos) return false;
  const size_t offset_end = line.find(' ', cursor);
  if (offset_end == std::string_view::npos ||
      !ParseHex(line.substr(cursor, offset_end - cursor), &mapping->file_offset)) return false;
  const size_t device_begin = line.find_first_not_of(' ', offset_end);
  if (device_begin == std::string_view::npos) return false;
  const size_t device_end = line.find(' ', device_begin);
  if (device_end == std::string_view::npos) return false;
  const std::string_view device = line.substr(device_begin, device_end - device_begin);
  const size_t colon = device.find(':');
  uint64_t device_major = 0;
  uint64_t device_minor = 0;
  if (colon == std::string_view::npos || !ParseHex(device.substr(0, colon), &device_major) ||
      !ParseHex(device.substr(colon + 1), &device_minor) || device_major > UINT_MAX || device_minor > UINT_MAX) return false;
  mapping->device = makedev(static_cast<unsigned int>(device_major), static_cast<unsigned int>(device_minor));
  const size_t inode_begin = line.find_first_not_of(' ', device_end);
  if (inode_begin == std::string_view::npos) return false;
  const size_t inode_end = line.find(' ', inode_begin);
  uint64_t inode_value = 0;
  if (inode_end == std::string_view::npos || line.substr(inode_begin, inode_end - inode_begin).empty()) return false;
  const auto parsed_inode = std::from_chars(line.data() + inode_begin, line.data() + inode_end, inode_value, 10);
  if (parsed_inode.ec != std::errc() || parsed_inode.ptr != line.data() + inode_end ||
      inode_value > static_cast<uint64_t>(std::numeric_limits<ino_t>::max())) return false;
  mapping->inode = static_cast<ino_t>(inode_value);
  const size_t slash = line.find('/', inode_end);
  if (slash == std::string_view::npos) return false;
  size_t path_end = line.find_first_of(" \t", slash);
  if (path_end == std::string_view::npos) path_end = line.size();
  if (line.find(" (deleted)", path_end) != std::string_view::npos) return false;
  mapping->path = std::string(line.substr(slash, path_end - slash));
  return !mapping->path.empty();
}

bool MappedIdentityMatches(const MappedExecutable& mapping, const FileIdentity& identity) {
  return mapping.device == identity.device && mapping.inode == identity.inode;
}

bool MappedRangeCovers(const MappedExecutable& mapping, uint64_t file_offset) {
  const uint64_t mapped_bytes = mapping.end - mapping.start;
  return mapping.file_offset <= file_offset && file_offset - mapping.file_offset < mapped_bytes;
}

bool MappedRangeCovers(const MappedExecutable& mapping,
                       const std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount>& offsets) {
  for (const uint64_t offset : offsets) {
    if (!MappedRangeCovers(mapping, offset)) return false;
  }
  return true;
}

bool ResolvePpssppTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->ppsspp = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset || target->container_offset != container_offset ||
        !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= strlen(kPpssppLibraryName) &&
          mapping.path.compare(mapping.path.size() - strlen(kPpssppLibraryName), strlen(kPpssppLibraryName), kPpssppLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        if (resolver.Resolve(mapping.path, true, &offset) && MappedRangeCovers(mapping, offset)) {
          accept(mapping.path, offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 && mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, kPpssppArm64Member, &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            if (resolver.ResolveImage(member, true, &symbol_offset) &&
                zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
              accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset, identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool ResolveMelonDsTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path,
                          const std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount>& offsets,
                          uint64_t container_offset, const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offsets = offsets;
      target->offset = offsets[0];
      target->container_offset = container_offset;
      target->identity = identity;
      target->melon_ds = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offsets != offsets ||
        target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= strlen(mimir::render_fps::kMelonDsLibraryName) &&
          mapping.path.compare(mapping.path.size() - strlen(mimir::render_fps::kMelonDsLibraryName),
                               strlen(mimir::render_fps::kMelonDsLibraryName),
                               mimir::render_fps::kMelonDsLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount> offsets{};
        if (resolver.ResolveMelonDs(mapping.path, &offsets) && MappedRangeCovers(mapping, offsets)) {
          accept(mapping.path, offsets, 0, identity);
        }
      } else if (mapping.path.size() >= 4 && mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, kMelonDsArm64Member, &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount> symbol_offsets{};
            if (resolver.ResolveMelonDsImage(member, &symbol_offsets)) {
              std::array<uint64_t, mimir::render_fps::MelonProducerCounter::kProbeCount> offsets{};
              bool valid = true;
              for (size_t index = 0; index < offsets.size(); ++index) {
                if (zip_member.data_offset > UINT64_MAX - symbol_offsets[index]) {
                  valid = false;
                  break;
                }
                offsets[index] = zip_member.data_offset + symbol_offsets[index];
              }
              if (valid && MappedRangeCovers(mapping, offsets)) {
                accept(mapping.path, offsets, zip_member.data_offset, identity);
              }
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

// The only diagnostic channel the helper has: its launcher sends stdout/stderr to /dev/null, so the
// Eden admission goes to logcat (tag mimir-render-fps). The frontend retries a refused target, so a
// repeat of the same line for the same process is dropped.
void LogEdenTarget(int pid, const std::string& line) {
  static int last_pid = 0;
  static std::string last_line;
  if (pid == last_pid && line == last_line) return;
  last_pid = pid;
  last_line = line;
  __android_log_print(ANDROID_LOG_INFO, "mimir-render-fps", "%s pid=%d", line.c_str(), pid);
}

bool ResolveEdenTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  mimir::render_fps::EdenResolution accepted_resolution;
  mimir::render_fps::EdenResolution refused_resolution;
  bool refused = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity,
                          const mimir::render_fps::EdenResolution& resolution) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->eden = true;
      accepted_resolution = resolution;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset || target->container_offset != container_offset ||
        !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= strlen(mimir::render_fps::kEdenLibraryName) &&
          mapping.path.compare(mapping.path.size() - strlen(mimir::render_fps::kEdenLibraryName),
                               strlen(mimir::render_fps::kEdenLibraryName), mimir::render_fps::kEdenLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        mimir::render_fps::EdenResolution resolution;
        if (resolver.ResolveEden(mapping.path, &offset, &resolution, identity)) {
          if (MappedRangeCovers(mapping, offset)) accept(mapping.path, offset, 0, identity, resolution);
        } else {
          refused = true;
          refused_resolution = resolution;
        }
      } else if (mapping.path.size() >= 4 && mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, kEdenArm64Member, &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            mimir::render_fps::EdenResolution resolution;
            if (resolver.ResolveEdenImage(member, &symbol_offset, &resolution)) {
              if (zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                  MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
                accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset,
                       identity, resolution);
              }
            } else {
              refused = true;
              refused_resolution = resolution;
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  const bool resolved = have_target && !ambiguous_target;
  if (resolved) {
    LogEdenTarget(pid, mimir::render_fps::FormatEdenTargetLog(accepted_resolution));
  } else if (ambiguous_target) {
    LogEdenTarget(pid, "eden_target=rejected reason=ambiguous_mapping build_id=" +
                           (accepted_resolution.build_id_hex.empty() ? std::string("none")
                                                                     : accepted_resolution.build_id_hex));
  } else if (refused) {
    LogEdenTarget(pid, mimir::render_fps::FormatEdenTargetLog(refused_resolution));
  } else {
    LogEdenTarget(pid, "eden_target=rejected reason=unmapped build_id=none");
  }
  return resolved;
}

bool ResolveAzaharTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->azahar = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset || target->container_offset != container_offset ||
        !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= strlen(mimir::render_fps::kAzaharLibraryName) &&
          mapping.path.compare(mapping.path.size() - strlen(mimir::render_fps::kAzaharLibraryName),
                               strlen(mimir::render_fps::kAzaharLibraryName),
                               mimir::render_fps::kAzaharLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        if (resolver.ResolveAzahar(mapping.path, &offset) && MappedRangeCovers(mapping, offset)) {
          accept(mapping.path, offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 && mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, kAzaharArm64Member, &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            if (resolver.ResolveAzaharImage(member, &symbol_offset) &&
                zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
              accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset, identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool ResolveCemuTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->cemu = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset ||
        target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= mimir::render_fps::kCemuLibraryName.size() &&
          mapping.path.compare(mapping.path.size() - mimir::render_fps::kCemuLibraryName.size(),
                               mimir::render_fps::kCemuLibraryName.size(),
                               mimir::render_fps::kCemuLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        if (resolver.ResolveCemu(mapping.path, &offset) && MappedRangeCovers(mapping, offset)) {
          accept(mapping.path, offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 &&
                 mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, kCemuArm64Member, &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            if (resolver.ResolveCemuImage(member, &symbol_offset) &&
                zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
              accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset,
                     identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool RetroArchCorePath(const std::string& path) {
  return path.find("/cores/") != std::string::npos &&
      path.size() > 3 && path.compare(path.size() - 3, 3, ".so") == 0;
}

bool RetroArchRangeContains(const std::array<mimir::render_fps::RetroArchExecutableRange,
                                                mimir::render_fps::kRetroArchMaxExecutableRanges>& ranges,
                            size_t count, uint64_t file_offset) {
  for (size_t index = 0; index < count; ++index) {
    const auto& range = ranges[index];
    if (file_offset >= range.file_offset && file_offset - range.file_offset < range.size) {
      return true;
    }
  }
  return false;
}

bool RetroArchMappingOverlapsExecutable(
    const MappedExecutable& mapping,
    const std::array<mimir::render_fps::RetroArchExecutableRange,
                     mimir::render_fps::kRetroArchMaxExecutableRanges>& ranges,
    size_t count) {
  const uint64_t mapping_size = mapping.end - mapping.start;
  if (mapping.file_offset > UINT64_MAX - mapping_size) return false;
  const uint64_t mapping_end = mapping.file_offset + mapping_size;
  for (size_t index = 0; index < count; ++index) {
    const auto& range = ranges[index];
    if (range.file_offset > UINT64_MAX - range.size) return false;
    const uint64_t range_end = range.file_offset + range.size;
    if (mapping.file_offset < range_end && range.file_offset < mapping_end) return true;
  }
  return false;
}

bool ResolveRetroArchCoreMaps(int pid, std::vector<RetroCoreMapping>* result) {
  if (result == nullptr) return false;
  result->clear();
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  std::vector<MappedExecutable> candidates;
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping) &&
        RetroArchCorePath(mapping.path)) {
      FileIdentity identity{};
      if (ReadFileIdentity(mapping.path.c_str(), &identity) &&
          MappedIdentityMatches(mapping, identity)) {
        candidates.push_back(std::move(mapping));
      }
    }
    cursor = end + 1;
  }
  if (candidates.empty()) return false;
  const std::string& path = candidates.front().path;
  const FileIdentity identity = [&]() {
    FileIdentity value{};
    (void)ReadFileIdentity(path.c_str(), &value);
    return value;
  }();
  if (identity.device == 0 || identity.inode == 0) return false;
  for (const auto& mapping : candidates) {
    if (mapping.path != path || mapping.device != identity.device || mapping.inode != identity.inode) {
      return false;
    }
  }
  std::string image;
  if (!ReadFile(path, &image, 128 * 1024 * 1024)) return false;
  std::array<mimir::render_fps::RetroArchExecutableRange,
             mimir::render_fps::kRetroArchMaxExecutableRanges> ranges{};
  size_t range_count = 0;
  if (!mimir::render_fps::ReadRetroArchExecutableRanges(image, &ranges, &range_count)) return false;
  for (const auto& mapping : candidates) {
    if (!RetroArchMappingOverlapsExecutable(mapping, ranges, range_count)) return false;
    RetroCoreMapping core{};
    core.mapping = mapping;
    core.identity = identity;
    core.executable_ranges = ranges;
    core.executable_range_count = range_count;
    result->push_back(std::move(core));
  }
  return !result->empty();
}

bool SameRetroArchCoreMaps(int pid, const std::vector<RetroCoreMapping>& expected) {
  if (expected.empty()) return false;
  FileIdentity current_identity{};
  if (!ReadFileIdentity(expected.front().mapping.path.c_str(), &current_identity) ||
      !SameFileIdentity(current_identity, expected.front().identity)) {
    return false;
  }
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  std::vector<MappedExecutable> actual;
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping) &&
        RetroArchCorePath(mapping.path)) {
      actual.push_back(std::move(mapping));
    }
    cursor = end + 1;
  }
  if (actual.size() != expected.size()) return false;
  std::sort(actual.begin(), actual.end(), [](const MappedExecutable& left, const MappedExecutable& right) {
    if (left.start != right.start) return left.start < right.start;
    return left.file_offset < right.file_offset;
  });
  std::vector<MappedExecutable> wanted;
  wanted.reserve(expected.size());
  for (const auto& item : expected) wanted.push_back(item.mapping);
  std::sort(wanted.begin(), wanted.end(), [](const MappedExecutable& left, const MappedExecutable& right) {
    if (left.start != right.start) return left.start < right.start;
    return left.file_offset < right.file_offset;
  });
  for (size_t index = 0; index < actual.size(); ++index) {
    if (actual[index].path != wanted[index].path || actual[index].start != wanted[index].start ||
        actual[index].end != wanted[index].end || actual[index].file_offset != wanted[index].file_offset ||
        actual[index].device != wanted[index].device || actual[index].inode != wanted[index].inode) {
      return false;
    }
  }
  return true;
}

bool IsRetroArchCoreCaller(const std::vector<RetroCoreMapping>& maps, uint64_t caller_pc) {
  for (const auto& core : maps) {
    const MappedExecutable& mapping = core.mapping;
    if (caller_pc < mapping.start || caller_pc >= mapping.end) continue;
    const uint64_t delta = caller_pc - mapping.start;
    if (delta > UINT64_MAX - mapping.file_offset) return false;
    const uint64_t file_offset = mapping.file_offset + delta;
    return RetroArchRangeContains(core.executable_ranges, core.executable_range_count, file_offset);
  }
  return false;
}

bool ResolveRetroArchTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t post_offset,
                          uint64_t container_offset, const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->post_offset = post_offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->retroarch = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset || target->post_offset != post_offset ||
        target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= mimir::render_fps::kRetroArchLibraryName.size() &&
          mapping.path.compare(mapping.path.size() - mimir::render_fps::kRetroArchLibraryName.size(),
                               mimir::render_fps::kRetroArchLibraryName.size(),
                               mimir::render_fps::kRetroArchLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        uint64_t post_offset = 0;
        if (resolver.ResolveRetroArch(mapping.path, &offset, &post_offset) &&
            MappedRangeCovers(mapping, offset) && MappedRangeCovers(mapping, post_offset)) {
          accept(mapping.path, offset, post_offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 && mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive,
                                                        mimir::render_fps::kRetroArchArm64Member,
                                                        &member)) {
            const std::string_view image(archive.data() + static_cast<size_t>(member.data_offset),
                                         static_cast<size_t>(member.size));
            uint64_t frame = 0;
            uint64_t post = 0;
            if (resolver.ResolveRetroArchImage(image, &frame, &post) &&
                member.data_offset <= UINT64_MAX - frame && member.data_offset <= UINT64_MAX - post &&
                MappedRangeCovers(mapping, member.data_offset + frame) &&
                MappedRangeCovers(mapping, member.data_offset + post)) {
              accept(mapping.path, member.data_offset + frame, member.data_offset + post,
                     member.data_offset, identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  if (!have_target || ambiguous_target) return false;
  return ResolveRetroArchCoreMaps(pid, &target->retro_core_maps);
}

bool ResolveDuckStationTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->duckstation = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset ||
        target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= mimir::render_fps::kDuckStationLibraryName.size() &&
          mapping.path.compare(mapping.path.size() - mimir::render_fps::kDuckStationLibraryName.size(),
                               mimir::render_fps::kDuckStationLibraryName.size(),
                               mimir::render_fps::kDuckStationLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        if (resolver.ResolveDuckStation(mapping.path, &offset) && MappedRangeCovers(mapping, offset)) {
          accept(mapping.path, offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 &&
                 mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive,
                                                        mimir::render_fps::kDuckStationArm64Member,
                                                        &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            if (resolver.ResolveDuckStationImage(member, &symbol_offset) &&
                zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
              accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset,
                     identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool ResolveArmsX2Target(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path,
                          const std::array<uint64_t, mimir::render_fps::ArmsX2Counter::kProbeCount>& offsets,
                          uint64_t container_offset, const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offsets = offsets;
      target->offset = offsets[0];
      target->container_offset = container_offset;
      target->identity = identity;
      target->armsx2 = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offsets != offsets ||
        target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  const std::array<std::string_view, 2> suffixes = {
      mimir::render_fps::kArmsX2LibraryName4K,
      mimir::render_fps::kArmsX2LibraryName16K};
  const std::array<std::string_view, 2> members = {
      mimir::render_fps::kArmsX2Arm64Member4K,
      mimir::render_fps::kArmsX2Arm64Member16K};
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      bool matched_suffix = false;
      for (const std::string_view suffix : suffixes) {
        if (mapping.path.size() >= suffix.size() &&
            mapping.path.compare(mapping.path.size() - suffix.size(), suffix.size(), suffix) == 0) {
          matched_suffix = true;
          break;
        }
      }
      if (matched_suffix && ReadFileIdentity(mapping.path.c_str(), &identity) &&
          MappedIdentityMatches(mapping, identity)) {
        std::array<uint64_t, mimir::render_fps::ArmsX2Counter::kProbeCount> offsets{};
        if (resolver.ResolveArmsX2(mapping.path, &offsets) && MappedRangeCovers(mapping, offsets)) {
          accept(mapping.path, offsets, 0, identity);
        }
      } else if (mapping.path.size() >= 4 &&
                 mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) &&
                 MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          for (const std::string_view member_name : members) {
            mimir::render_fps::StoredZipMember zip_member{};
            if (!mimir::render_fps::ResolveStoredZipMember(archive, member_name, &zip_member)) continue;
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            std::array<uint64_t, mimir::render_fps::ArmsX2Counter::kProbeCount> symbol_offsets{};
            if (!resolver.ResolveArmsX2Image(member, &symbol_offsets)) continue;
            std::array<uint64_t, mimir::render_fps::ArmsX2Counter::kProbeCount> offsets{};
            bool valid = true;
            for (size_t index = 0; index < offsets.size(); ++index) {
              if (zip_member.data_offset > UINT64_MAX - symbol_offsets[index]) {
                valid = false;
                break;
              }
              offsets[index] = zip_member.data_offset + symbol_offsets[index];
            }
            if (valid && MappedRangeCovers(mapping, offsets)) {
              accept(mapping.path, offsets, zip_member.data_offset, identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool ResolveX1BoxTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->x1box = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset ||
        target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= mimir::render_fps::kX1BoxLibraryName.size() &&
          mapping.path.compare(mapping.path.size() - mimir::render_fps::kX1BoxLibraryName.size(),
                               mimir::render_fps::kX1BoxLibraryName.size(),
                               mimir::render_fps::kX1BoxLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        if (resolver.ResolveX1Box(mapping.path, &offset) && MappedRangeCovers(mapping, offset)) {
          accept(mapping.path, offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 &&
                 mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, mimir::render_fps::kX1BoxArm64Member,
                                                        &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            if (resolver.ResolveX1BoxImage(member, &symbol_offset) &&
                zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
              accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset,
                     identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool ResolveFlycastTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  bool have_target = false;
  bool ambiguous_target = false;
  const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                          const FileIdentity& identity) {
    if (!have_target) {
      target->path = path;
      target->offset = offset;
      target->container_offset = container_offset;
      target->identity = identity;
      target->flycast = true;
      have_target = true;
      return;
    }
    if (target->path != path || target->offset != offset || target->container_offset != container_offset ||
        !SameFileIdentity(target->identity, identity)) {
      ambiguous_target = true;
    }
  };
  size_t cursor = 0;
  while (cursor < maps.size()) {
    size_t end = maps.find('\n', cursor);
    if (end == std::string::npos) end = maps.size();
    MappedExecutable mapping{};
    if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
      FileIdentity identity{};
      if (mapping.path.size() >= strlen(kFlycastLibraryName) &&
          mapping.path.compare(mapping.path.size() - strlen(kFlycastLibraryName),
                               strlen(kFlycastLibraryName), kFlycastLibraryName) == 0 &&
          ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        uint64_t offset = 0;
        if (resolver.ResolveFlycast(mapping.path, &offset) && MappedRangeCovers(mapping, offset)) {
          accept(mapping.path, offset, 0, identity);
        }
      } else if (mapping.path.size() >= 4 && mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                 ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
        std::string archive;
        if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
          mimir::render_fps::StoredZipMember zip_member{};
          if (mimir::render_fps::ResolveStoredZipMember(archive, kFlycastArm64Member, &zip_member)) {
            const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                          static_cast<size_t>(zip_member.size));
            uint64_t symbol_offset = 0;
            if (resolver.ResolveFlycastImage(member, &symbol_offset) &&
                zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
              accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset, identity);
            }
          }
        }
      }
    }
    cursor = end + 1;
  }
  return have_target && !ambiguous_target;
}

bool ResolveMupen64PlusTarget(int pid, ProbeTarget* target) {
  if (target == nullptr) return false;
  LibraryResolver resolver;
  std::string maps;
  if (!ReadFile("/proc/" + std::to_string(pid) + "/maps", &maps, 2 * 1024 * 1024)) return false;
  // Admit one producer, preferring the game-only Android bridge. An ambiguous
  // admitted bridge must not fall through to the legacy plugin producer.
  for (bool bridge : {true, false}) {
    const char* library_name = bridge ? kMupenBridgeLibraryName : kMupen64PlusLibraryName;
    const char* member_name = bridge ? kMupenBridgeArm64Member : kMupen64PlusArm64Member;
    bool have_target = false;
    bool ambiguous_target = false;
    const auto accept = [&](const std::string& path, uint64_t offset, uint64_t container_offset,
                            const FileIdentity& identity) {
      if (!have_target) {
        target->path = path;
        target->offset = offset;
        target->container_offset = container_offset;
        target->identity = identity;
        target->mupen64plus = true;
        target->mupen_bridge = bridge;
        have_target = true;
        return;
      }
      if (target->path != path || target->offset != offset ||
          target->container_offset != container_offset || !SameFileIdentity(target->identity, identity)) {
        ambiguous_target = true;
      }
    };
    size_t cursor = 0;
    while (cursor < maps.size()) {
      size_t end = maps.find('\n', cursor);
      if (end == std::string::npos) end = maps.size();
      MappedExecutable mapping{};
      if (ParseMappedExecutableLine(std::string_view(maps.data() + cursor, end - cursor), &mapping)) {
        FileIdentity identity{};
        if (mapping.path.size() >= strlen(library_name) &&
            mapping.path.compare(mapping.path.size() - strlen(library_name),
                                 strlen(library_name), library_name) == 0 &&
            ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
          uint64_t offset = 0;
          if (resolver.ResolveMupen64Plus(mapping.path, &offset, bridge) && MappedRangeCovers(mapping, offset)) {
            accept(mapping.path, offset, 0, identity);
          }
        } else if (mapping.path.size() >= 4 &&
                   mapping.path.compare(mapping.path.size() - 4, 4, ".apk") == 0 &&
                   ReadFileIdentity(mapping.path.c_str(), &identity) && MappedIdentityMatches(mapping, identity)) {
          std::string archive;
          if (ReadFile(mapping.path, &archive, 128 * 1024 * 1024)) {
            mimir::render_fps::StoredZipMember zip_member{};
            if (mimir::render_fps::ResolveStoredZipMember(archive, member_name, &zip_member)) {
              const std::string_view member(archive.data() + static_cast<size_t>(zip_member.data_offset),
                                            static_cast<size_t>(zip_member.size));
              uint64_t symbol_offset = 0;
              if (resolver.ResolveMupen64PlusImage(member, &symbol_offset, bridge) &&
                  zip_member.data_offset <= UINT64_MAX - symbol_offset &&
                  MappedRangeCovers(mapping, zip_member.data_offset + symbol_offset)) {
                accept(mapping.path, zip_member.data_offset + symbol_offset, zip_member.data_offset, identity);
              }
            }
          }
        }
      }
      cursor = end + 1;
    }
    if (have_target) return !ambiguous_target;
  }
  return false;
}

struct PmuInfo { uint32_t type = 0; uint32_t retprobe_bit = 0; };

bool ReadSmallUnsigned(const char* path, uint64_t* result) {
  std::string text;
  if (!ReadFile(path, &text, 64)) return false;
  while (!text.empty() && (text.back() == '\n' || text.back() == '\r' || text.back() == ' ' || text.back() == '\t')) text.pop_back();
  return Decimal(text, result);
}

bool ReadPmu(PmuInfo* result) {
  uint64_t type = 0, bit = 0;
  if (!ReadSmallUnsigned("/sys/bus/event_source/devices/uprobe/type", &type)) return false;
  std::string format;
  if (!ReadFile("/sys/bus/event_source/devices/uprobe/format/retprobe", &format, 64)) return false;
  const size_t prefix = format.find("config:");
  if (prefix == std::string::npos || !Decimal(std::string_view(format).substr(prefix + 7, format.find_first_of("\r\n", prefix) - prefix - 7), &bit) || bit >= 64 || type > UINT32_MAX) return false;
  result->type = static_cast<uint32_t>(type);
  result->retprobe_bit = static_cast<uint32_t>(bit);
  return true;
}

int PerfOpen(perf_event_attr* attr, int tid) {
#ifdef __NR_perf_event_open
  return static_cast<int>(syscall(__NR_perf_event_open, attr, tid, -1, -1, PERF_FLAG_FD_CLOEXEC));
#else
  errno = ENOSYS;
  return -1;
#endif
}

enum class EventKind { kEntry, kReturn, kLost };
enum class ProbeRole {
  kQueueBufferEntry,
  kQueueBufferReturn,
  kPpssppFlipEntry,
  kEdenFrameEndEntry,
  kAzaharSetBufferSwapEntry,
  kCemuSwapScanBufferEntry,
  kDuckStationUpdateEntry,
  kFlycastPresentReturn,
  kMupen64PlusSwapReturn,
  kMelonRunFrameEntry,
  kMelonRunFrameReturn,
  kMelonDiscardEntry,
  kMelonPushReturn,
  kX1BoxIncrementEntry,
  kRetroArchFrameEntry,
  kRetroArchFramePost,
  kRetroArchFrameReturn,
  kArmsX2DeltaEntry,
  kArmsX2BlitEntry,
  kArmsX2PrivilegedEntry,
  kArmsX2ZeroEntry,
};
struct Event {
  EventKind kind = EventKind::kLost;
  ProbeRole role = ProbeRole::kQueueBufferEntry;
  uint32_t pid = 0;
  uint32_t tid = 0;
  uint64_t time_ns = 0;
  uint64_t value = 0;
  uint32_t ring_tid = 0;
  uint64_t caller_pc = 0;
};

class PerfRing {
 public:
  ~PerfRing() { Close(); }
  bool Open(uint32_t pmu_type, uint32_t retprobe_bit, EventKind kind, ProbeRole role, int tid,
            const std::string& probe_path, uint64_t offset) {
    perf_event_attr attr{};
    attr.type = pmu_type;
    attr.size = PERF_ATTR_SIZE_VER3;
    attr.disabled = 1;
    attr.inherit = 0;
    attr.exclude_kernel = 1;
    attr.exclude_hv = 1;
    attr.sample_period = 1;
    attr.sample_type = PERF_SAMPLE_TID | PERF_SAMPLE_TIME | PERF_SAMPLE_REGS_USER;
    // SetBufferSwap receives screen_id in W1. DuckStation samples X2, while the
    // RetroArch entry role samples X0 and its caller LR X30 for core-origin gating.
    // ARMSX2's delta is X0 and its three selected-counter probes are X8.
    sample_regs_user_ = role == ProbeRole::kRetroArchFrameEntry ? ((1ULL << 0) | (1ULL << 30))
                       : (role == ProbeRole::kAzaharSetBufferSwapEntry ? (1ULL << 1)
                       : (role == ProbeRole::kDuckStationUpdateEntry ? (1ULL << 2)
                       : (role == ProbeRole::kArmsX2DeltaEntry ? (1ULL << 0)
                       : ((role == ProbeRole::kArmsX2BlitEntry ||
                           role == ProbeRole::kArmsX2PrivilegedEntry ||
                           role == ProbeRole::kArmsX2ZeroEntry) ? (1ULL << 8) : 1ULL))));
    attr.sample_regs_user = sample_regs_user_;
    attr.use_clockid = 1;
    attr.clockid = CLOCK_MONOTONIC;
    attr.watermark = 1;
    attr.wakeup_watermark = 4096;
    attr.config = kind == EventKind::kReturn ? 1ULL << retprobe_bit : 0;
    attr.uprobe_path = reinterpret_cast<uint64_t>(probe_path.c_str());
    attr.probe_offset = offset;
    fd_ = PerfOpen(&attr, tid);
    if (fd_ < 0) return false;
    page_size_ = static_cast<size_t>(sysconf(_SC_PAGESIZE));
    map_size_ = page_size_ * (1 + kRingPages);
    mapping_ = mmap(nullptr, map_size_, PROT_READ | PROT_WRITE, MAP_SHARED, fd_, 0);
    if (mapping_ == MAP_FAILED) { mapping_ = nullptr; Close(); return false; }
    meta_ = static_cast<perf_event_mmap_page*>(mapping_);
    if (meta_->data_offset > map_size_ || meta_->data_size == 0 || meta_->data_size > map_size_ - meta_->data_offset || (meta_->data_size & (meta_->data_size - 1)) != 0) { Close(); return false; }
    data_ = static_cast<uint8_t*>(mapping_) + meta_->data_offset;
    data_size_ = meta_->data_size;
    tid_ = static_cast<uint32_t>(tid);
    kind_ = kind;
    role_ = role;
    return true;
  }
  bool Enable() { return ioctl(fd_, PERF_EVENT_IOC_RESET, 0) == 0 && ioctl(fd_, PERF_EVENT_IOC_ENABLE, 0) == 0; }
  void Disable() { if (fd_ >= 0) (void)ioctl(fd_, PERF_EVENT_IOC_DISABLE, 0); }
  int fd() const { return fd_; }
  uint32_t tid() const { return tid_; }
  bool overflow() const { return overflow_; }
  void Drain(std::vector<Event>* events) {
    if (!meta_) return;
    const uint64_t head = __atomic_load_n(&meta_->data_head, __ATOMIC_ACQUIRE);
    uint64_t tail = tail_;
    if (head - tail > data_size_) {
      Push(events, {EventKind::kLost, role_, 0, 0, MonoNs(), 1, tid_});
      tail = head - data_size_;
    }
    while (tail < head) {
      perf_event_header header{};
      const uint64_t available = head - tail;
      if (available < sizeof(header)) { overflow_ = true; tail = head; break; }
      Copy(tail, &header, sizeof(header));
      if (header.size < sizeof(header) || header.size > available || header.size > 256) {
        overflow_ = true;
        tail = head;
        break;
      }
      std::array<uint8_t, 256> record{};
      Copy(tail, record.data(), header.size);
      if (header.type == PERF_RECORD_SAMPLE) {
        const size_t base = sizeof(perf_event_header);
        const size_t register_count = static_cast<size_t>(__builtin_popcountll(sample_regs_user_));
        if (header.size == base + 8 + 8 + 8 + register_count * 8) {
          Event event{};
          size_t cursor = base;
          memcpy(&event.pid, record.data() + cursor, 4); cursor += 4;
          memcpy(&event.tid, record.data() + cursor, 4); cursor += 4;
          memcpy(&event.time_ns, record.data() + cursor, 8); cursor += 8;
          uint64_t abi = 0; memcpy(&abi, record.data() + cursor, 8); cursor += 8;
          if (abi != PERF_SAMPLE_REGS_ABI_64 || event.tid != tid_ || event.time_ns == 0) {
            overflow_ = true;
          } else {
            memcpy(&event.value, record.data() + cursor, 8);
            cursor += 8;
            if (register_count == 2) memcpy(&event.caller_pc, record.data() + cursor, 8);
            event.kind = kind_;
            event.role = role_;
            event.ring_tid = tid_;
            Push(events, event);
          }
        } else {
          overflow_ = true;
        }
      } else if (header.type == PERF_RECORD_LOST) {
        if (header.size != sizeof(perf_event_header) + 16) {
          overflow_ = true;
          tail += header.size;
          continue;
        }
        uint64_t count = 1;
        memcpy(&count, record.data() + sizeof(perf_event_header) + 8, 8);
        Push(events, {EventKind::kLost, role_, 0, 0, MonoNs(), count == 0 ? 1 : count, tid_});
      } else {
        overflow_ = true;
      }
      tail += header.size;
    }
    tail_ = tail;
    __atomic_store_n(&meta_->data_tail, tail_, __ATOMIC_RELEASE);
  }
  void Close() { if (mapping_) munmap(mapping_, map_size_); mapping_ = nullptr; meta_ = nullptr; data_ = nullptr; if (fd_ >= 0) close(fd_); fd_ = -1; }
 private:
  void Push(std::vector<Event>* events, const Event& event) {
    if (events->size() >= kMaxEvents) { overflow_ = true; return; }
    events->push_back(event);
  }

  void Copy(uint64_t position, void* destination, size_t length) const {
    const size_t start = static_cast<size_t>(position % data_size_);
    const size_t first = std::min(length, data_size_ - start);
    memcpy(destination, data_ + start, first);
    if (first < length) memcpy(static_cast<uint8_t*>(destination) + first, data_, length - first);
  }
  int fd_ = -1; void* mapping_ = nullptr; size_t map_size_ = 0; size_t page_size_ = 0; perf_event_mmap_page* meta_ = nullptr; uint8_t* data_ = nullptr; uint64_t data_size_ = 0; uint64_t tail_ = 0; uint32_t tid_ = 0; EventKind kind_ = EventKind::kLost; ProbeRole role_ = ProbeRole::kQueueBufferEntry; uint64_t sample_regs_user_ = 1; bool overflow_ = false;
};

enum class CaptureMode {
  kSurface, kPpsspp, kMelonDs, kEden, kAzahar, kCemu, kDuckStation, kFlycast, kMupen64Plus,
  kX1Box, kRetroArch, kArmsX2
};

class Collector {
 public:
  bool Start(const Request& request) {
    Stop();
    uint64_t start = 0; uint32_t uid = 0; std::string name;
    if (!ReadStartTicks(request.pid, &start) || start != request.start_ticks || !ReadUid(request.pid, &uid) || uid != request.uid || !ReadProcessName(request.pid, &name) || !mimir::render_fps::IsExpectedProcessName(request.package_name, name)) return false;
    if (request.package_name == kPpssppGoldPackage) return false;
    ppsspp_ = IsPpssppPackage(request.package_name);
    melon_ds_ = IsMelonDsPackage(request.package_name);
    eden_ = IsEdenPackage(request.package_name);
    azahar_ = mimir::render_fps::IsAzaharPackage(request.package_name);
    cemu_ = mimir::render_fps::IsCemuPackage(request.package_name);
    duckstation_ = request.package_name == mimir::render_fps::kDuckStationPackageName;
    x1box_ = mimir::render_fps::IsX1BoxPackage(request.package_name);
    armsx2_ = request.package_name == mimir::render_fps::kArmsX2PackageName;
    retroarch_ = request.package_name == mimir::render_fps::kRetroArchPackageName;
    flycast_ = request.package_name == kFlycastPackage;
    mupen64plus_ = mimir::render_fps::IsMupen64PlusPackage(request.package_name);
    // The generic queueBuffer signal is source/device-grounded only for these two families.
    // Keep this gate before any target mapping, symbol resolution, or perf-ring opens so catalog
    // identity cannot accidentally publish compositor/UI telemetry as game FPS.
    if (!ppsspp_ && !melon_ds_ && !eden_ && !azahar_ && !cemu_ && !duckstation_ && !x1box_ && !armsx2_ && !retroarch_ && !flycast_ && !mupen64plus_ &&
        !mimir::render_fps::IsGenericSurfacePackage(request.package_name)) return false;
    mode_ = azahar_ ? CaptureMode::kAzahar
                    : (eden_ ? CaptureMode::kEden
                              : (cemu_ ? CaptureMode::kCemu
                              : (duckstation_ ? CaptureMode::kDuckStation
                              : (x1box_ ? CaptureMode::kX1Box
                              : (armsx2_ ? CaptureMode::kArmsX2
                              : (retroarch_ ? CaptureMode::kRetroArch
                              : (melon_ds_ ? CaptureMode::kMelonDs
                                            : (ppsspp_ ? CaptureMode::kPpsspp
                                                       : (flycast_ ? CaptureMode::kFlycast
                                                                    : (mupen64plus_ ? CaptureMode::kMupen64Plus
                                                                                   : CaptureMode::kSurface))))))))));
    ProbeTarget target{};
    if (eden_) {
      if (!ResolveEdenTarget(request.pid, &target)) return false;
    } else if (azahar_) {
      if (!ResolveAzaharTarget(request.pid, &target)) return false;
    } else if (cemu_) {
      if (!ResolveCemuTarget(request.pid, &target)) return false;
    } else if (duckstation_) {
      if (!ResolveDuckStationTarget(request.pid, &target)) return false;
    } else if (x1box_) {
      if (!ResolveX1BoxTarget(request.pid, &target)) return false;
    } else if (armsx2_) {
      if (!ResolveArmsX2Target(request.pid, &target)) return false;
    } else if (retroarch_) {
      if (!ResolveRetroArchTarget(request.pid, &target)) return false;
    } else if (melon_ds_) {
      if (!ResolveMelonDsTarget(request.pid, &target)) return false;
    } else if (ppsspp_) {
      if (!ResolvePpssppTarget(request.pid, &target)) return false;
    } else if (flycast_) {
      if (!ResolveFlycastTarget(request.pid, &target)) return false;
    } else if (mupen64plus_) {
      if (!ResolveMupen64PlusTarget(request.pid, &target)) return false;
    } else {
      if (!MappedLibrary(request.pid, kLibraryPath) || !ReadFileIdentity(kLibraryPath, &target.identity)) return false;
      target.path = kLibraryPath;
      target.ppsspp = false;
    }
    mupen_bridge_ = target.mupen_bridge;
    retro_core_maps_ = std::move(target.retro_core_maps);
    library_path_ = target.path;
    library_identity_ = target.identity;
    uint64_t offset = 0;
    if (melon_ds_) {
      // The four probes share the same mapped object but each carries its own fixed symbol offset.
      target.offsets[1] = target.offsets[0];
    } else if (ppsspp_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || armsx2_ || retroarch_ || flycast_ || mupen64plus_) {
      offset = target.offset;
    } else if (!resolver_.Resolve(library_path_, false, &offset)) {
      return false;
    }
    PmuInfo pmu{};
    if (!ReadPmu(&pmu)) return false;
    tids_ = CurrentThreads(request.pid, ppsspp_ || melon_ds_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || armsx2_ || retroarch_ || flycast_ || mupen64plus_);
    const size_t max_threads = melon_ds_ ? mimir::render_fps::MelonProducerCounter::kMaxThreads
                                         : (armsx2_ ? mimir::render_fps::ArmsX2Counter::kMaxThreads : kMaxTids);
    if (tids_.empty() || tids_.size() > max_threads) return false;
    rings_.reserve(kMaxRings);
    for (int tid : tids_) {
      const std::array<EventKind, 2> surface_kinds = {EventKind::kEntry, EventKind::kReturn};
      const std::array<EventKind, 1> producer_kinds = {EventKind::kEntry};
      if (melon_ds_) {
        struct RingSpec { EventKind kind; ProbeRole role; size_t offset_index; };
        const std::array<RingSpec, mimir::render_fps::MelonProducerCounter::kProbeCount> melon_specs = {{
            {EventKind::kEntry, ProbeRole::kMelonRunFrameEntry, 0},
            {EventKind::kReturn, ProbeRole::kMelonRunFrameReturn, 1},
            {EventKind::kEntry, ProbeRole::kMelonDiscardEntry, 2},
            {EventKind::kReturn, ProbeRole::kMelonPushReturn, 3},
        }};
        for (const RingSpec& spec : melon_specs) {
          auto ring = std::make_unique<PerfRing>();
          if (!ring->Open(pmu.type, pmu.retprobe_bit, spec.kind, spec.role, tid, library_path_,
                          target.offsets[spec.offset_index])) {
            Stop();
            return false;
          }
          rings_.push_back(std::move(ring));
        }
      } else if (armsx2_) {
        struct RingSpec { ProbeRole role; size_t offset_index; };
        const std::array<RingSpec, mimir::render_fps::ArmsX2Counter::kProbeCount> arms_specs = {{
            {ProbeRole::kArmsX2DeltaEntry, 0},
            {ProbeRole::kArmsX2BlitEntry, 1},
            {ProbeRole::kArmsX2PrivilegedEntry, 2},
            {ProbeRole::kArmsX2ZeroEntry, 3},
        }};
        for (const RingSpec& spec : arms_specs) {
          auto ring = std::make_unique<PerfRing>();
          if (!ring->Open(pmu.type, pmu.retprobe_bit, EventKind::kEntry, spec.role, tid,
                          library_path_, target.offsets[spec.offset_index])) {
            Stop();
            return false;
          }
          rings_.push_back(std::move(ring));
        }
      } else if (retroarch_) {
        struct RingSpec { EventKind kind; ProbeRole role; uint64_t offset; };
        const std::array<RingSpec, 3> retro_specs = {{
            {EventKind::kEntry, ProbeRole::kRetroArchFrameEntry, target.offset},
            {EventKind::kEntry, ProbeRole::kRetroArchFramePost, target.post_offset},
            {EventKind::kReturn, ProbeRole::kRetroArchFrameReturn, target.offset},
        }};
        for (const RingSpec& spec : retro_specs) {
          auto ring = std::make_unique<PerfRing>();
          if (!ring->Open(pmu.type, pmu.retprobe_bit, spec.kind, spec.role, tid,
                          library_path_, spec.offset)) {
            Stop();
            return false;
          }
          rings_.push_back(std::move(ring));
        }
      } else if (ppsspp_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || flycast_ || mupen64plus_) {
        const std::array<EventKind, 1> return_kinds = {EventKind::kReturn};
        const auto& kinds = (flycast_ || mupen64plus_) ? return_kinds : producer_kinds;
        for (EventKind kind : kinds) {
          auto ring = std::make_unique<PerfRing>();
          const ProbeRole role = mupen64plus_ ? ProbeRole::kMupen64PlusSwapReturn
                                              : (flycast_ ? ProbeRole::kFlycastPresentReturn
                                              : (azahar_ ? ProbeRole::kAzaharSetBufferSwapEntry
                                              : (cemu_ ? ProbeRole::kCemuSwapScanBufferEntry
                                              : (duckstation_ ? ProbeRole::kDuckStationUpdateEntry
                                              : (x1box_ ? ProbeRole::kX1BoxIncrementEntry
                                              : (eden_ ? ProbeRole::kEdenFrameEndEntry
                                                        : ProbeRole::kPpssppFlipEntry))))));
          if (!ring->Open(pmu.type, pmu.retprobe_bit, kind, role, tid,
                          library_path_, offset)) { Stop(); return false; }
          rings_.push_back(std::move(ring));
        }
      } else {
        for (EventKind kind : surface_kinds) {
          auto ring = std::make_unique<PerfRing>();
          const ProbeRole role = kind == EventKind::kEntry ? ProbeRole::kQueueBufferEntry
                                                            : ProbeRole::kQueueBufferReturn;
          if (!ring->Open(pmu.type, pmu.retprobe_bit, kind, role, tid, library_path_, offset)) {
            Stop();
            return false;
          }
          rings_.push_back(std::move(ring));
        }
      }
    }
    for (auto& ring : rings_) if (!ring->Enable()) { Stop(); return false; }
    pollfds_.clear(); pollfds_.reserve(rings_.size());
    for (const auto& ring : rings_) pollfds_.push_back({ring->fd(), POLLIN, 0});
    const uint64_t start_boot_ms = BootMs();
    const uint64_t start_mono_ms = MonoMs();
    request_ = request;
    interval_start_mono_ms_ = start_mono_ms;
    next_publish_mono_ms_ = start_mono_ms + kPublishMs;
    next_identity_check_ms_ = start_boot_ms;
    last_boot_ms_ = start_boot_ms;
    last_mono_ms_ = start_mono_ms;
    pairing_.Reset(); producer_.Reset(); melon_producer_.Reset(); duck_counter_.Reset(); retro_counter_.Reset(); armsx2_counter_.Reset(); events_.clear(); carry_events_.clear(); events_.reserve(kMaxEvents); carry_events_.reserve(kMaxEvents); return true;
  }

  void Stop() { for (auto& ring : rings_) ring->Disable(); rings_.clear(); pollfds_.clear(); pairing_.Reset(); producer_.Reset(); melon_producer_.Reset(); duck_counter_.Reset(); retro_counter_.Reset(); armsx2_counter_.Reset(); events_.clear(); carry_events_.clear(); retro_core_maps_.clear(); library_path_.clear(); ppsspp_ = false; melon_ds_ = false; eden_ = false; azahar_ = false; cemu_ = false; duckstation_ = false; x1box_ = false; armsx2_ = false; retroarch_ = false; flycast_ = false; mupen64plus_ = false; mupen_bridge_ = false; mode_ = CaptureMode::kSurface; }

  void RenewLease(uint64_t lease_boottime_ms) {
    if (lease_boottime_ms >= request_.lease_boottime_ms) request_.lease_boottime_ms = lease_boottime_ms;
  }

  bool Active() const { return !rings_.empty(); }

  bool Tick(uint64_t now_boottime_ms, Record* publication) {
    if (!Active()) return false;
    if (now_boottime_ms >= next_identity_check_ms_) {
      uint64_t start = 0; uint32_t uid = 0; std::string name;
      FileIdentity identity{};
      const bool mapped = (ppsspp_ || melon_ds_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || armsx2_ || retroarch_ || flycast_ || mupen64plus_) ? ReadFileIdentity(library_path_.c_str(), &identity)
                                                 : ReadFileIdentity(kLibraryPath, &identity);
      const bool still_mapped = (ppsspp_ || melon_ds_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || armsx2_ || retroarch_ || flycast_ || mupen64plus_) ? MappedLibrary(request_.pid, library_path_.c_str())
                                                        : MappedLibrary(request_.pid, kLibraryPath);
      const bool core_mapped = !retroarch_ || SameRetroArchCoreMaps(request_.pid, retro_core_maps_);
      if (!ReadStartTicks(request_.pid, &start) || start != request_.start_ticks || !ReadUid(request_.pid, &uid) || uid != request_.uid || !ReadProcessName(request_.pid, &name) || !mimir::render_fps::IsExpectedProcessName(request_.package_name, name) || !mapped || !still_mapped || !core_mapped || identity.device != library_identity_.device || identity.inode != library_identity_.inode || identity.size != library_identity_.size || CurrentThreads(request_.pid, ppsspp_ || melon_ds_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || armsx2_ || retroarch_ || flycast_ || mupen64plus_) != tids_) {
        FillPublication(now_boottime_ms, MonoMs(), "stale", publication);
        Stop();
        return true;
      }
      next_identity_check_ms_ = now_boottime_ms + kIdentityCheckMs;
    }
    const uint64_t now_mono_before_poll = MonoMs();
    const uint64_t until_publish = next_publish_mono_ms_ > now_mono_before_poll ? next_publish_mono_ms_ - now_mono_before_poll : 0;
    const int wait_ms = static_cast<int>(std::min<uint64_t>(100, until_publish == 0 ? 1 : until_publish));
    (void)poll(&pollfds_[0], pollfds_.size(), wait_ms);
    const uint64_t now_boot_ms = BootMs();
    const uint64_t now_mono_ms = MonoMs();
    if (now_boot_ms >= request_.lease_boottime_ms) {
      FillPublication(now_boot_ms, now_mono_ms, "stale", publication);
      Stop();
      return true;
    }
    const uint64_t boot_delta = now_boot_ms >= last_boot_ms_ ? now_boot_ms - last_boot_ms_ : 0;
    const uint64_t mono_delta = now_mono_ms >= last_mono_ms_ ? now_mono_ms - last_mono_ms_ : 0;
    last_boot_ms_ = now_boot_ms;
    last_mono_ms_ = now_mono_ms;
    const uint64_t cutoff_ns = MonoNs();
    const uint64_t cutoff_mono_ms = cutoff_ns / 1'000'000ULL;
    if (boot_delta > mono_delta + kSuspendGapMs) {
      pairing_.Reset();
      producer_.Reset();
      melon_producer_.Reset();
      duck_counter_.Reset();
      retro_counter_.Reset();
      armsx2_counter_.Reset();
      events_.clear();
      carry_events_.clear();
      // Discard events that were queued before the suspend gap. They belong to the
      // invalidated measurement window and must not be paired into the fresh one.
      for (auto& ring : rings_) ring->Drain(&events_);
      events_.clear();
      interval_start_mono_ms_ = now_mono_ms;
      next_publish_mono_ms_ = now_mono_ms + kPublishMs;
      FillPublication(now_boot_ms, now_mono_ms, "warming", publication);
      return true;
    }
    events_.clear();
    for (auto& ring : rings_) ring->Drain(&events_);
    for (const auto& ring : rings_) {
      if (ring->overflow()) {
        FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
        Stop();
        return true;
      }
    }
    // A queueBuffer call may straddle this publication boundary. Merge the previous carry with
    // this drain, but retain events newer than the cutoff for the next pass. This gives entry and
    // return rings one coherent ordered window without treating a normal cross-ring call as loss.
    if (events_.size() > kMaxEvents - carry_events_.size()) {
      FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
      Stop();
      return true;
    }
    events_.insert(events_.end(), carry_events_.begin(), carry_events_.end());
    carry_events_.clear();
    std::stable_sort(events_.begin(), events_.end(), [](const Event& a, const Event& b) {
      if (a.time_ns != b.time_ns) return a.time_ns < b.time_ns;
      const auto rank = [](const Event& event) {
        switch (event.role) {
          case ProbeRole::kMelonRunFrameEntry: return 0;
          case ProbeRole::kMelonDiscardEntry: return 1;
          case ProbeRole::kMelonPushReturn: return 2;
          case ProbeRole::kMelonRunFrameReturn: return 3;
          case ProbeRole::kQueueBufferEntry:
          case ProbeRole::kPpssppFlipEntry:
          case ProbeRole::kEdenFrameEndEntry:
          case ProbeRole::kAzaharSetBufferSwapEntry:
          case ProbeRole::kCemuSwapScanBufferEntry:
          case ProbeRole::kDuckStationUpdateEntry:
          case ProbeRole::kX1BoxIncrementEntry:
          case ProbeRole::kRetroArchFrameEntry: return 0;
          case ProbeRole::kArmsX2DeltaEntry: return 0;
          case ProbeRole::kArmsX2BlitEntry:
          case ProbeRole::kArmsX2PrivilegedEntry:
          case ProbeRole::kArmsX2ZeroEntry: return 1;
          case ProbeRole::kRetroArchFramePost: return 1;
          case ProbeRole::kRetroArchFrameReturn: return 2;
          case ProbeRole::kFlycastPresentReturn: return 1;
          case ProbeRole::kMupen64PlusSwapReturn: return 1;
          case ProbeRole::kQueueBufferReturn: return 1;
        }
        return 4;
      };
      if (rank(a) != rank(b)) return rank(a) < rank(b);
      return a.ring_tid < b.ring_tid;
    });
    for (const Event& event : events_) {
      if (event.kind == EventKind::kLost || event.pid != static_cast<uint32_t>(request_.pid)) {
        FillPublication(now_boot_ms, cutoff_mono_ms, event.kind == EventKind::kLost ? "lost" : "stale", publication);
        Stop();
        return true;
      }
      if (retroarch_) {
        const bool valid_role =
            (event.role == ProbeRole::kRetroArchFrameEntry && event.kind == EventKind::kEntry) ||
            (event.role == ProbeRole::kRetroArchFramePost && event.kind == EventKind::kEntry) ||
            (event.role == ProbeRole::kRetroArchFrameReturn && event.kind == EventKind::kReturn);
        if (!valid_role) {
          FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
          Stop();
          return true;
        }
        mimir::render_fps::RetroArchEventKind kind =
            event.role == ProbeRole::kRetroArchFrameEntry
                ? mimir::render_fps::RetroArchEventKind::kEntry
                : (event.role == ProbeRole::kRetroArchFramePost
                       ? mimir::render_fps::RetroArchEventKind::kPost
                       : mimir::render_fps::RetroArchEventKind::kReturn);
        bool carried = false;
        const auto outcome = retro_counter_.ConsumeAtCutoff(
            {kind, event.pid, event.tid, event.time_ns, event.value, event.caller_pc,
             event.role == ProbeRole::kRetroArchFrameEntry &&
                 IsRetroArchCoreCaller(retro_core_maps_, event.caller_pc)},
            static_cast<uint32_t>(request_.pid), cutoff_ns, &carried);
        if (carried) {
          if (carry_events_.size() >= kMaxEvents) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          carry_events_.push_back(event);
          continue;
        }
        if (outcome != mimir::render_fps::RetroArchCounterTerminal::kNone) {
          FillPublication(now_boot_ms, cutoff_mono_ms,
                          outcome == mimir::render_fps::RetroArchCounterTerminal::kWrongProcess
                              ? "stale"
                              : "lost",
                          publication);
          Stop();
          return true;
        }
        continue;
      }
      if (armsx2_) {
        if (event.kind != EventKind::kEntry) {
          FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
          Stop();
          return true;
        }
        mimir::render_fps::ArmsX2EventKind kind = mimir::render_fps::ArmsX2EventKind::kLost;
        switch (event.role) {
          case ProbeRole::kArmsX2DeltaEntry:
            kind = mimir::render_fps::ArmsX2EventKind::kDelta;
            break;
          case ProbeRole::kArmsX2BlitEntry:
            kind = mimir::render_fps::ArmsX2EventKind::kBlit;
            break;
          case ProbeRole::kArmsX2PrivilegedEntry:
            kind = mimir::render_fps::ArmsX2EventKind::kPrivileged;
            break;
          case ProbeRole::kArmsX2ZeroEntry:
            kind = mimir::render_fps::ArmsX2EventKind::kZero;
            break;
          default:
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
        }
        bool carried = false;
        const auto outcome = armsx2_counter_.ConsumeAtCutoff(
            {kind, event.pid, event.tid, event.time_ns, event.value},
            static_cast<uint32_t>(request_.pid), cutoff_ns, &carried);
        if (carried) {
          if (carry_events_.size() >= kMaxEvents) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          carry_events_.push_back(event);
          continue;
        }
        if (outcome != mimir::render_fps::ArmsX2CounterTerminal::kNone) {
          FillPublication(now_boot_ms, cutoff_mono_ms,
                          outcome == mimir::render_fps::ArmsX2CounterTerminal::kWrongProcess
                              ? "stale"
                              : "lost",
                          publication);
          Stop();
          return true;
        }
        continue;
      }
      if (ppsspp_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || flycast_ || mupen64plus_) {
        if (duckstation_) {
          if (event.kind != EventKind::kEntry || event.role != ProbeRole::kDuckStationUpdateEntry) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          bool carried = false;
          const auto outcome = duck_counter_.ConsumeAtCutoff(
              {event.pid, event.tid, event.time_ns, event.value},
              static_cast<uint32_t>(request_.pid), cutoff_ns, &carried);
          if (carried) {
            if (carry_events_.size() >= kMaxEvents) {
              FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
              Stop();
              return true;
            }
            carry_events_.push_back(event);
            continue;
          }
          if (outcome != mimir::render_fps::DuckCounterTerminal::kNone) {
            FillPublication(now_boot_ms, cutoff_mono_ms,
                            outcome == mimir::render_fps::DuckCounterTerminal::kWrongProcess
                                ? "stale"
                                : "lost",
                            publication);
            Stop();
            return true;
          }
          continue;
        }
        if (azahar_ && !mimir::render_fps::IsAzaharTopScreen(event.value)) continue;
        bool carried = false;
        mimir::render_fps::ProducerTerminal outcome = mimir::render_fps::ProducerTerminal::kInvalid;
        if (flycast_ || mupen_bridge_) {
          if (event.kind != EventKind::kReturn) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          outcome = producer_.ConsumeReturnAtCutoff(
              {mimir::render_fps::ProducerEventKind::kEntry, event.pid, event.tid, event.time_ns},
              event.value, static_cast<uint32_t>(request_.pid), cutoff_ns, &carried);
        } else if (mupen64plus_) {
          if (event.kind != EventKind::kReturn) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          // _swapBuffers() is void. Do not inspect the register captured by PerfRing;
          // only the completed return and its bounded identity/timestamp are meaningful.
          outcome = producer_.ConsumeVoidReturnAtCutoff(
              {mimir::render_fps::ProducerEventKind::kEntry, event.pid, event.tid, event.time_ns},
              static_cast<uint32_t>(request_.pid), cutoff_ns, &carried);
        } else {
          outcome = producer_.ConsumeAtCutoff(
              {event.kind == EventKind::kEntry ? mimir::render_fps::ProducerEventKind::kEntry
                                                : mimir::render_fps::ProducerEventKind::kLost,
               event.pid, event.tid, event.time_ns},
              static_cast<uint32_t>(request_.pid), cutoff_ns, &carried);
        }
        if (carried) {
          if (carry_events_.size() >= kMaxEvents) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          carry_events_.push_back(event);
          continue;
        }
        if (outcome != mimir::render_fps::ProducerTerminal::kNone) {
          FillPublication(now_boot_ms, cutoff_mono_ms,
                          outcome == mimir::render_fps::ProducerTerminal::kWrongProcess ? "stale" : "lost",
                          publication);
          Stop();
          return true;
        }
        continue;
      }
      if (melon_ds_) {
        if (event.time_ns > cutoff_ns) {
          if (carry_events_.size() >= kMaxEvents) {
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
          }
          carry_events_.push_back(event);
          continue;
        }
        mimir::render_fps::MelonProducerEventKind kind =
            mimir::render_fps::MelonProducerEventKind::kLost;
        switch (event.role) {
          case ProbeRole::kMelonRunFrameEntry:
            kind = mimir::render_fps::MelonProducerEventKind::kRunFrameEntry;
            break;
          case ProbeRole::kMelonRunFrameReturn:
            kind = mimir::render_fps::MelonProducerEventKind::kRunFrameReturn;
            break;
          case ProbeRole::kMelonDiscardEntry:
            kind = mimir::render_fps::MelonProducerEventKind::kDiscardEntry;
            break;
          case ProbeRole::kMelonPushReturn:
            kind = mimir::render_fps::MelonProducerEventKind::kPushReturn;
            break;
          default:
            FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
            Stop();
            return true;
        }
        const auto outcome = melon_producer_.Consume(
            {kind, event.pid, event.tid, event.time_ns}, static_cast<uint32_t>(request_.pid));
        if (outcome != mimir::render_fps::MelonProducerTerminal::kNone &&
            outcome != mimir::render_fps::MelonProducerTerminal::kWarmup) {
          const char* status = outcome == mimir::render_fps::MelonProducerTerminal::kWrongProcess
                                   ? "stale"
                                   : "lost";
          FillPublication(now_boot_ms, cutoff_mono_ms, status, publication);
          Stop();
          return true;
        }
        continue;
      }
      if (event.time_ns > cutoff_ns) {
        if (carry_events_.size() >= kMaxEvents) {
          FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
          Stop();
          return true;
        }
        carry_events_.push_back(event);
        continue;
      }
      const auto outcome = pairing_.Consume(
          {event.kind == EventKind::kEntry ? mimir::render_fps::PairEventKind::kEntry : mimir::render_fps::PairEventKind::kReturn,
           event.pid, event.tid, event.time_ns, event.value},
          static_cast<uint32_t>(request_.pid));
      if (outcome != mimir::render_fps::PairTerminal::kNone) {
        const char* status = outcome == mimir::render_fps::PairTerminal::kAmbiguous ? "ambiguous" : "lost";
        FillPublication(now_boot_ms, cutoff_mono_ms, status, publication);
        Stop();
        return true;
      }
    }
    if (melon_ds_) {
      const auto outcome = melon_producer_.ExpirePending(cutoff_ns, kPendingTimeoutNs);
      if (outcome != mimir::render_fps::MelonProducerTerminal::kNone &&
          outcome != mimir::render_fps::MelonProducerTerminal::kWarmup) {
        FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
        Stop();
        return true;
      }
    }
    const uint64_t oldest_retro_pending_ns = retroarch_ ? retro_counter_.OldestPendingTimestampNs() : 0;
    if (oldest_retro_pending_ns != 0 && cutoff_ns >= oldest_retro_pending_ns &&
        cutoff_ns - oldest_retro_pending_ns > kPendingTimeoutNs) {
      FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
      Stop();
      return true;
    }
    const uint64_t oldest_arms_pending_ns = armsx2_ ? armsx2_counter_.OldestPendingTimestampNs() : 0;
    if (oldest_arms_pending_ns != 0 && cutoff_ns >= oldest_arms_pending_ns &&
        cutoff_ns - oldest_arms_pending_ns > kPendingTimeoutNs) {
      FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
      Stop();
      return true;
    }
    const uint64_t oldest_pending_ns = pairing_.OldestActiveTimestampNs();
    if (oldest_pending_ns != 0 && cutoff_ns >= oldest_pending_ns &&
        cutoff_ns - oldest_pending_ns > kPendingTimeoutNs) {
      FillPublication(now_boot_ms, cutoff_mono_ms, "lost", publication);
      Stop();
      return true;
    }
    return Publish(now_boot_ms, cutoff_mono_ms, "ok", publication);
  }

 private:
  void FillPublication(uint64_t now_boot_ms, uint64_t now_mono_ms, const char* failure_status, Record* publication) const {
    publication->token = request_.token; publication->package_name = request_.package_name; publication->uid = request_.uid; publication->pid = request_.pid; publication->start_ticks = request_.start_ticks; publication->generation = request_.generation; publication->sample_boottime_ms = now_boot_ms; publication->interval_ms = now_mono_ms - interval_start_mono_ms_; publication->count = 0; publication->status = failure_status;
  }

  bool Publish(uint64_t now_boot_ms, uint64_t now_mono_ms, const char* requested_status, Record* publication) {
    if (now_mono_ms < next_publish_mono_ms_) return false;
    const bool duck_seed_interval = duckstation_ && duck_counter_.seeded_this_interval();
    const uint64_t count = duck_seed_interval ? 0
                                   : (retroarch_ ? retro_counter_.count()
                                   : (armsx2_ ? armsx2_counter_.count()
                                   : ((ppsspp_ || eden_ || azahar_ || cemu_ || x1box_ || flycast_ || mupen64plus_) ? producer_.count()
                                   : (duckstation_ ? duck_counter_.count()
                                   : (melon_ds_ ? melon_producer_.count()
                                                : (pairing_.selectedSurface() != 0 ? pairing_.count() : 0))))));
    const bool valid = retroarch_ ? count > 0
                               : (armsx2_ ? count > 0
                               : ((ppsspp_ || eden_ || azahar_ || cemu_ || x1box_ || flycast_ || mupen64plus_) ? count > 0
                               : (duckstation_ ? count > 0
                               : (melon_ds_ ? count > 0
                                            : (pairing_.selectedSurface() != 0 && count > 0)))));
    const char* status = valid ? "ok" : ((ppsspp_ || melon_ds_ || eden_ || azahar_ || cemu_ || duckstation_ || x1box_ || armsx2_ || retroarch_ || flycast_ || mupen64plus_) ? "warming" : requested_status);
    uint64_t interval_ms = now_mono_ms - interval_start_mono_ms_;
    if (armsx2_ && armsx2_counter_.interval_has_publication()) {
      const uint64_t duration_ns = armsx2_counter_.duration_ns();
      interval_ms = duration_ns / 1'000'000ULL;
      if (duration_ns % 1'000'000ULL >= 500'000ULL) ++interval_ms;
      if (interval_ms == 0) interval_ms = 1;
      if (interval_ms > 5000) {
        FillPublication(now_boot_ms, now_mono_ms, "lost", publication);
        Stop();
        return true;
      }
    }
    publication->token = request_.token; publication->package_name = request_.package_name; publication->uid = request_.uid; publication->pid = request_.pid; publication->start_ticks = request_.start_ticks; publication->generation = request_.generation; publication->sample_boottime_ms = now_boot_ms; publication->interval_ms = interval_ms; publication->count = count; publication->status = status;
    interval_start_mono_ms_ = now_mono_ms; next_publish_mono_ms_ = now_mono_ms + kPublishMs;
    pairing_.BeginInterval();
    producer_.BeginInterval();
    duck_counter_.BeginInterval();
    retro_counter_.BeginInterval();
    melon_producer_.BeginInterval();
    armsx2_counter_.BeginInterval();
    return true;
  }
  Request request_{}; LibraryResolver resolver_; FileIdentity library_identity_{}; std::string library_path_;
  CaptureMode mode_ = CaptureMode::kSurface;
  bool ppsspp_ = false;
  bool melon_ds_ = false;
  bool eden_ = false;
  bool azahar_ = false;
  bool cemu_ = false;
  bool duckstation_ = false;
  bool x1box_ = false;
  bool armsx2_ = false;
  bool retroarch_ = false;
  bool flycast_ = false;
  bool mupen64plus_ = false;
  bool mupen_bridge_ = false;
  std::vector<int> tids_; std::vector<std::unique_ptr<PerfRing>> rings_; std::vector<pollfd> pollfds_;
  std::vector<Event> events_; std::vector<Event> carry_events_;
  mimir::render_fps::Pairing pairing_{}; mimir::render_fps::ProducerCounter producer_{};
  mimir::render_fps::DuckCounter duck_counter_{};
  mimir::render_fps::RetroArchCounter retro_counter_{};
  mimir::render_fps::MelonProducerCounter melon_producer_{};
  mimir::render_fps::ArmsX2Counter armsx2_counter_{};
  std::vector<RetroCoreMapping> retro_core_maps_;
  uint64_t interval_start_mono_ms_ = 0; uint64_t next_publish_mono_ms_ = 0;
  uint64_t next_identity_check_ms_ = 0; uint64_t last_boot_ms_ = 0; uint64_t last_mono_ms_ = 0;
};

std::string SelfDirectory(const char* argv0) {
  char resolved[PATH_MAX]{};
  if (realpath("/proc/self/exe", resolved) == nullptr && realpath(argv0, resolved) == nullptr) return {};
  std::string path(resolved);
  const size_t slash = path.rfind('/');
  return slash == std::string::npos ? std::string() : path.substr(0, slash);
}

bool WriteRecord(const std::string& path, const Record& record) {
  const std::string text = mimir::render_fps::FormatRecord(record);
  if (text.empty()) return false;
  const std::string temporary = path + ".tmp";
  int fd = open(temporary.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  if (fd < 0) return false;
  const ssize_t written = write(fd, text.data(), text.size());
  close(fd);
  if (written != static_cast<ssize_t>(text.size())) { unlink(temporary.c_str()); return false; }
  return rename(temporary.c_str(), path.c_str()) == 0;
}

bool ReadRequest(const std::string& path, Request* request) {
  std::string text;
  return ReadFile(path, &text, mimir::render_fps::kMaxProtocolBytes) && mimir::render_fps::ParseRequest(text, request) && ApprovedPackage(request->package_name);
}

// The lock is held by this process for its entire daemon lifetime. A second fixed launcher exits
// cleanly without trusting a global pidof or accidentally attaching to another instance.
int AcquireProcessLock(const std::string& directory) {
  const std::string path = directory + "/" + kLockFileName;
  int fd = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0600);
  if (fd < 0) return -1;
  // Keep the return value unambiguous: zero means another live helper owns the
  // lock, while this process must retain its own descriptor for its full lifetime.
  if (fd < 3) {
    const int retained = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    close(fd);
    if (retained < 0) return -1;
    fd = retained;
  }
  if (flock(fd, LOCK_EX | LOCK_NB) == 0) return fd;
  const int error = errno;
  close(fd);
  if (error == EWOULDBLOCK || error == EAGAIN) return 0;
  return -1;
}

int RunDaemon(const std::string& directory, int /*lock_fd*/, const FileIdentity& executable_identity) {
  const std::string request_path = directory + "/request.v1";
  const std::string record_path = directory + "/record.v1";
  const std::string executable_path = directory + "/render_fps_helper";
  Collector collector;
  Request current{};
  bool have_request = false;
  bool start_attempted = false;
  uint64_t idle_since = BootMs();
  uint64_t next_executable_check_ms = idle_since;
  while (true) {
    const uint64_t now = BootMs();
    if (now >= next_executable_check_ms) {
      FileIdentity current_executable{};
      if (!ReadFileIdentity(executable_path.c_str(), &current_executable) ||
          !SameFileIdentity(executable_identity, current_executable)) {
        collector.Stop();
        unlink(record_path.c_str());
        return 0;
      }
      next_executable_check_ms = now + kIdentityCheckMs;
    }
    Request request{};
    if (!ReadRequest(request_path, &request) || !mimir::render_fps::RequestIsLiveAt(request, now)) {
      collector.Stop();
      have_request = false;
      start_attempted = false;
      unlink(record_path.c_str());
      if (now - idle_since >= kIdleExitMs) return 0;
      usleep(50000);
      continue;
    }
    idle_since = now;
    if (!have_request || mimir::render_fps::RequestChanged(current, request)) {
      collector.Stop();
      current = request;
      have_request = true;
      start_attempted = false;
      unlink(record_path.c_str());
    } else if (request.lease_boottime_ms < current.lease_boottime_ms ||
               (request.lease_boottime_ms > current.lease_boottime_ms &&
                !mimir::render_fps::RequestCanRenew(current, request, now))) {
      collector.Stop();
      have_request = false;
      start_attempted = false;
      unlink(record_path.c_str());
      continue;
    } else if (request.lease_boottime_ms > current.lease_boottime_ms) {
      current.lease_boottime_ms = request.lease_boottime_ms;
      collector.RenewLease(current.lease_boottime_ms);
    }
    if (!mimir::render_fps::RequestIsLiveAt(current, now)) {
      collector.Stop(); unlink(record_path.c_str()); return 0;
    }
    if (!collector.Active() && !start_attempted) {
      start_attempted = true;
      if (!collector.Start(current)) {
        Record unavailable{};
        unavailable.token = current.token;
        unavailable.package_name = current.package_name;
        unavailable.uid = current.uid;
        unavailable.pid = current.pid;
        unavailable.start_ticks = current.start_ticks;
        unavailable.generation = current.generation;
        unavailable.sample_boottime_ms = now;
        unavailable.interval_ms = 0;
        unavailable.count = 0;
        unavailable.status = "unsupported";
        (void)WriteRecord(record_path, unavailable);
      }
    }
    if (!collector.Active()) {
      usleep(50000);
      continue;
    }
    Record publication{};
    if (collector.Tick(now, &publication) && !WriteRecord(record_path, publication)) {
      collector.Stop();
      start_attempted = true;
    }
  }
}

}  // namespace

int main(int argc, char** argv) {
  if (argc != 1) return 2;
  const std::string directory = SelfDirectory(argv[0]);
  if (directory.empty()) return 2;
  FileIdentity executable_identity{};
  if (!ReadFileIdentity("/proc/self/exe", &executable_identity)) return 2;
  const std::string staged_executable = directory + "/render_fps_helper";
  FileIdentity staged_identity{};
  if (!ReadFileIdentity(staged_executable.c_str(), &staged_identity) ||
      !SameFileIdentity(executable_identity, staged_identity)) return 2;
  const int lock_fd = AcquireProcessLock(directory);
  if (lock_fd == 0) return 0;
  if (lock_fd < 0) return 2;
  return RunDaemon(directory, lock_fd, executable_identity);
}
