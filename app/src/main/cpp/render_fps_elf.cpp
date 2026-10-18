#include "render_fps_elf.h"
#include "render_fps_azahar.h"
#include "render_fps_cemu.h"
#include "render_fps_duckstation.h"
#include "render_fps_eden.h"
#include "render_fps_flycast.h"
#include "render_fps_melonds.h"
#include "render_fps_mupen.h"
#include "render_fps_retroarch.h"
#include "render_fps_x1box.h"

#include <cstdint>
#include <cstring>
#include <limits>
#include <algorithm>
#include <vector>

namespace mimir::render_fps {
namespace {

struct ElfHeader {
  unsigned char ident[16];
  uint16_t type;
  uint16_t machine;
  uint32_t version;
  uint64_t entry;
  uint64_t phoff;
  uint64_t shoff;
  uint32_t flags;
  uint16_t ehsize;
  uint16_t phentsize;
  uint16_t phnum;
  uint16_t shentsize;
  uint16_t shnum;
  uint16_t shstrndx;
};
struct ProgramHeader { uint32_t type; uint32_t flags; uint64_t offset; uint64_t vaddr; uint64_t paddr; uint64_t filesz; uint64_t memsz; uint64_t align; };
struct SectionHeader { uint32_t name; uint32_t type; uint64_t flags; uint64_t addr; uint64_t offset; uint64_t size; uint32_t link; uint32_t info; uint64_t addralign; uint64_t entsize; };
struct Symbol { uint32_t name; unsigned char info; unsigned char other; uint16_t shndx; uint64_t value; uint64_t size; };
static_assert(sizeof(ElfHeader) == 64 && sizeof(ProgramHeader) == 56 && sizeof(SectionHeader) == 64 && sizeof(Symbol) == 24);

constexpr unsigned char kElfMagic[] = {0x7f, 'E', 'L', 'F'};
constexpr unsigned char kElfClass64 = 2;
constexpr unsigned char kElfDataLsb = 1;
constexpr unsigned char kElfCurrent = 1;
constexpr uint16_t kElfTypeDyn = 3;
constexpr uint16_t kMachineAarch64 = 183;
constexpr uint32_t kPtLoad = 1;
constexpr uint32_t kPtNote = 4;
constexpr uint32_t kPfX = 1;
constexpr uint32_t kShtDynsym = 11;
constexpr uint32_t kShtSymtab = 2;
constexpr uint32_t kShtProgbits = 1;
constexpr uint32_t kShtStrtab = 3;
constexpr uint64_t kShfAlloc = 0x2;
constexpr uint64_t kShfExecInstr = 0x4;
constexpr uint16_t kShnUndef = 0;
constexpr unsigned char kSttFunc = 2;
constexpr unsigned char kStbLocal = 0;
constexpr unsigned char kStvHidden = 2;
constexpr uint32_t kNtGnuBuildId = 3;

template <typename T>
bool ReadObject(std::string_view image, uint64_t offset, T* value) {
  if (offset > image.size() || sizeof(T) > image.size() - static_cast<size_t>(offset)) return false;
  memcpy(value, image.data() + static_cast<size_t>(offset), sizeof(T));
  return true;
}

bool Range(std::string_view image, uint64_t offset, uint64_t count) {
  return count <= image.size() && offset <= image.size() - static_cast<size_t>(count);
}

bool TableRange(std::string_view image, uint64_t offset, uint64_t count, uint64_t entry_size) {
  return entry_size != 0 && count <= std::numeric_limits<uint64_t>::max() / entry_size &&
      Range(image, offset, count * entry_size);
}

bool VaToFile(std::string_view image, const ElfHeader& header, uint64_t va, uint64_t size,
              uint64_t* file_offset) {
  const uint64_t required_size = size == 0 ? 4 : size;
  if (header.phentsize != sizeof(ProgramHeader) || header.phnum == 0 || header.phnum > 128 ||
      !TableRange(image, header.phoff, header.phnum, sizeof(ProgramHeader))) return false;
  for (uint16_t i = 0; i < header.phnum; ++i) {
    ProgramHeader program{};
    if (!ReadObject(image, header.phoff + static_cast<uint64_t>(i) * sizeof(ProgramHeader), &program)) return false;
    if (program.type != kPtLoad) continue;
    if (!Range(image, program.offset, program.filesz)) return false;
    if (!(program.flags & kPfX)) continue;
    if ((va & 3U) != 0 || va < program.vaddr || va - program.vaddr >= program.filesz) continue;
    const uint64_t delta = va - program.vaddr;
    if (delta > program.filesz || required_size > program.filesz - delta ||
        !Range(image, program.offset, delta) || !Range(image, program.offset + delta, required_size)) return false;
    *file_offset = program.offset + delta;
    return true;
  }
  return false;
}

bool VaToUniqueExecutableFile(std::string_view image, const ElfHeader& header, uint64_t va,
                              uint64_t size, uint64_t* file_offset) {
  if (file_offset == nullptr || header.phentsize != sizeof(ProgramHeader) || header.phnum == 0 ||
      header.phnum > 128 || !TableRange(image, header.phoff, header.phnum, sizeof(ProgramHeader))) {
    return false;
  }
  const uint64_t required_size = size == 0 ? 4 : size;
  uint64_t match = 0;
  uint64_t matched_offset = 0;
  for (uint16_t i = 0; i < header.phnum; ++i) {
    ProgramHeader program{};
    if (!ReadObject(image, header.phoff + static_cast<uint64_t>(i) * sizeof(ProgramHeader), &program)) {
      return false;
    }
    if (program.type != kPtLoad) continue;
    if (!Range(image, program.offset, program.filesz)) return false;
    if (!(program.flags & kPfX) || (va & 3U) != 0 || va < program.vaddr) continue;
    const uint64_t delta = va - program.vaddr;
    if (delta >= program.filesz || required_size > program.filesz - delta) continue;
    if (!Range(image, program.offset + delta, required_size)) return false;
    ++match;
    matched_offset = program.offset + delta;
  }
  if (match != 1) return false;
  *file_offset = matched_offset;
  return true;
}

bool ReadHeader(std::string_view image, ElfHeader* header) {
  return header != nullptr && image.size() >= sizeof(ElfHeader) && ReadObject(image, 0, header) &&
      memcmp(header->ident, kElfMagic, sizeof(kElfMagic)) == 0 && header->ident[4] == kElfClass64 &&
      header->ident[5] == kElfDataLsb && header->ident[6] == kElfCurrent && header->type == kElfTypeDyn &&
      header->version == kElfCurrent && header->machine == kMachineAarch64 &&
      header->phentsize == sizeof(ProgramHeader) && header->phnum != 0 && header->phnum <= 128 &&
      TableRange(image, header->phoff, header->phnum, sizeof(ProgramHeader));
}

bool ReadProgram(std::string_view image, const ElfHeader& header, uint16_t index,
                 ProgramHeader* program) {
  return index < header.phnum && ReadObject(
      image, header.phoff + static_cast<uint64_t>(index) * sizeof(ProgramHeader), program);
}

bool MatchBuildId(std::string_view image, const ElfHeader& header, std::string_view expected_hex) {
  if (expected_hex.size() == 0 || (expected_hex.size() & 1U) != 0) return false;
  auto hex = [](char value) -> int {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
  };
  const size_t expected_bytes = expected_hex.size() / 2;
  for (uint16_t i = 0; i < header.phnum; ++i) {
    ProgramHeader program{};
    if (!ReadProgram(image, header, i, &program)) return false;
    if (program.type != kPtNote || !Range(image, program.offset, program.filesz)) continue;
    uint64_t cursor = program.offset;
    const uint64_t end = program.offset + program.filesz;
    while (cursor < end) {
      if (end - cursor < 12) return false;
      uint32_t namesz = 0;
      uint32_t descsz = 0;
      uint32_t type = 0;
      if (!ReadObject(image, cursor, &namesz) || !ReadObject(image, cursor + 4, &descsz) ||
          !ReadObject(image, cursor + 8, &type)) return false;
      const uint64_t name_padded = (static_cast<uint64_t>(namesz) + 3U) & ~3ULL;
      const uint64_t desc_padded = (static_cast<uint64_t>(descsz) + 3U) & ~3ULL;
      if (name_padded > end - cursor - 12 || desc_padded > end - cursor - 12 - name_padded) return false;
      const uint64_t name_offset = cursor + 12;
      const uint64_t desc_offset = name_offset + name_padded;
      if (type == kNtGnuBuildId && namesz == 4 && descsz == expected_bytes &&
          memcmp(image.data() + static_cast<size_t>(name_offset), "GNU\0", 4) == 0) {
        for (size_t byte = 0; byte < expected_bytes; ++byte) {
          const int high = hex(expected_hex[byte * 2]);
          const int low = hex(expected_hex[byte * 2 + 1]);
          if (high < 0 || low < 0 || static_cast<unsigned char>(image[desc_offset + byte]) !=
                                      static_cast<unsigned char>((high << 4) | low)) {
            return false;
          }
        }
        return true;
      }
      cursor = desc_offset + desc_padded;
    }
  }
  return false;
}

bool ResolveSymbolFromSections(std::string_view image, const ElfHeader& header,
                               std::string_view symbol_name, uint64_t expected_size,
                               uint64_t* offset, bool require_full_function_range = false) {
  if (header.shentsize != sizeof(SectionHeader) || header.shnum == 0 || header.shnum > 4096 ||
      !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) return false;
  for (uint16_t i = 0; i < header.shnum; ++i) {
    SectionHeader section{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(i) * sizeof(SectionHeader), &section)) return false;
    if (section.type != kShtDynsym || section.entsize != sizeof(Symbol) || section.link >= header.shnum ||
        section.size % sizeof(Symbol) != 0 || !Range(image, section.offset, section.size)) continue;
    SectionHeader strings_section{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(section.link) * sizeof(SectionHeader), &strings_section) ||
        strings_section.type != kShtStrtab || !Range(image, strings_section.offset, strings_section.size)) return false;
    const uint64_t symbol_count = section.size / sizeof(Symbol);
    for (uint64_t symbol_index = 0; symbol_index < symbol_count; ++symbol_index) {
      Symbol symbol{};
      if (!ReadObject(image, section.offset + symbol_index * sizeof(Symbol), &symbol)) return false;
      if (symbol.name >= strings_section.size || symbol.shndx == kShnUndef ||
          (symbol.info & 0x0f) != kSttFunc || (expected_size != 0 && symbol.size != expected_size)) continue;
      const char* name = image.data() + static_cast<size_t>(strings_section.offset + symbol.name);
      const size_t remaining = static_cast<size_t>(strings_section.size - symbol.name);
      if (memchr(name, '\0', remaining) == nullptr || std::string_view(name) != symbol_name) continue;
      if (require_full_function_range && symbol.size == 0) continue;
      return VaToFile(image, header, symbol.value,
                      require_full_function_range ? symbol.size : expected_size, offset);
    }
  }
  return false;
}

bool ResolveExactDynamicSymbol(std::string_view image, const ElfHeader& header,
                               std::string_view symbol_name, uint64_t expected_value,
                               uint64_t expected_size, uint64_t* offset) {
  if (offset == nullptr || header.shentsize != sizeof(SectionHeader) || header.shnum == 0 ||
      header.shnum > 4096 || !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) {
    return false;
  }
  uint64_t resolved = 0;
  size_t matches = 0;
  for (uint16_t i = 0; i < header.shnum; ++i) {
    SectionHeader section{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(i) * sizeof(SectionHeader), &section)) {
      return false;
    }
    if (section.type != kShtDynsym || section.entsize != sizeof(Symbol) ||
        section.link >= header.shnum || section.size % sizeof(Symbol) != 0 ||
        !Range(image, section.offset, section.size)) {
      continue;
    }
    SectionHeader strings{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(section.link) * sizeof(SectionHeader),
                    &strings) || strings.type != kShtStrtab ||
        !Range(image, strings.offset, strings.size)) {
      return false;
    }
    for (uint64_t index = 0; index < section.size / sizeof(Symbol); ++index) {
      Symbol symbol{};
      if (!ReadObject(image, section.offset + index * sizeof(Symbol), &symbol)) return false;
      if (symbol.name >= strings.size || strings.offset > UINT64_MAX - symbol.name ||
          symbol.shndx == kShnUndef ||
          (symbol.info & 0x0f) != kSttFunc ||
          !Range(image, strings.offset + symbol.name, strings.size - symbol.name)) {
        continue;
      }
      const char* name = image.data() + static_cast<size_t>(strings.offset + symbol.name);
      const size_t remaining = static_cast<size_t>(strings.size - symbol.name);
      if (memchr(name, '\0', remaining) == nullptr || std::string_view(name) != symbol_name) continue;
      if (symbol.value != expected_value || symbol.size != expected_size || ++matches != 1) {
        return false;
      }
      if (!VaToUniqueExecutableFile(image, header, symbol.value, symbol.size, &resolved)) return false;
    }
  }
  if (matches != 1) return false;
  *offset = resolved;
  return true;
}

bool SectionHasName(std::string_view image, const ElfHeader& header, uint16_t section_index,
                    std::string_view expected_name) {
  if (header.shstrndx >= header.shnum || section_index >= header.shnum) return false;
  SectionHeader section_strings{};
  SectionHeader section{};
  if (!ReadObject(image, header.shoff + static_cast<uint64_t>(header.shstrndx) * sizeof(SectionHeader),
                  &section_strings) || !ReadObject(
                      image, header.shoff + static_cast<uint64_t>(section_index) * sizeof(SectionHeader),
                      &section) || section_strings.type != kShtStrtab ||
      !Range(image, section_strings.offset, section_strings.size) ||
      section.name >= section_strings.size) {
    return false;
  }
  const char* name = image.data() + static_cast<size_t>(section_strings.offset + section.name);
  const size_t remaining = static_cast<size_t>(section_strings.size - section.name);
  return memchr(name, '\0', remaining) != nullptr && std::string_view(name) == expected_name;
}

bool ResolveX1BoxSymbolFromSymtab(std::string_view image, const ElfHeader& header,
                                  uint64_t* offset) {
  constexpr uint16_t kTextSection = 14;
  if (offset == nullptr || header.shentsize != sizeof(SectionHeader) || header.shnum <= kTextSection ||
      header.shnum > 4096 || !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) {
    return false;
  }

  SectionHeader text{};
  if (!ReadObject(image, header.shoff + static_cast<uint64_t>(kTextSection) * sizeof(SectionHeader), &text) ||
      text.type != kShtProgbits || (text.flags & (kShfAlloc | kShfExecInstr)) !=
          (kShfAlloc | kShfExecInstr) || !Range(image, text.offset, text.size) ||
      !SectionHasName(image, header, kTextSection, ".text")) {
    return false;
  }

  bool found = false;
  uint64_t found_value = 0;
  uint64_t found_size = 0;
  for (uint16_t i = 0; i < header.shnum; ++i) {
    SectionHeader symbols{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(i) * sizeof(SectionHeader), &symbols)) {
      return false;
    }
    if (symbols.type != kShtSymtab) continue;
    if (symbols.entsize != sizeof(Symbol) || symbols.size % sizeof(Symbol) != 0 ||
        symbols.link >= header.shnum || !Range(image, symbols.offset, symbols.size)) {
      return false;
    }
    SectionHeader strings{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(symbols.link) * sizeof(SectionHeader), &strings) ||
        strings.type != kShtStrtab || !Range(image, strings.offset, strings.size)) {
      return false;
    }
    const uint64_t count = symbols.size / sizeof(Symbol);
    for (uint64_t index = 0; index < count; ++index) {
      Symbol symbol{};
      if (!ReadObject(image, symbols.offset + index * sizeof(Symbol), &symbol)) return false;
      if (symbol.name >= strings.size || symbol.shndx != kTextSection ||
          symbol.info != static_cast<unsigned char>((kStbLocal << 4) | kSttFunc) ||
          symbol.other != kStvHidden || symbol.value != kX1BoxIncrementValue ||
          symbol.size != kX1BoxIncrementSize) {
        continue;
      }
      const char* name = image.data() + static_cast<size_t>(strings.offset + symbol.name);
      const size_t remaining = static_cast<size_t>(strings.size - symbol.name);
      if (memchr(name, '\0', remaining) == nullptr ||
          std::string_view(name) != kX1BoxIncrementSymbol) {
        continue;
      }
      if (found) return false;
      found = true;
      found_value = symbol.value;
      found_size = symbol.size;
    }
  }
  if (!found || found_value < text.addr) {
    return false;
  }
  const uint64_t delta = found_value - text.addr;
  if (delta > text.size || found_size > text.size - delta) return false;
  uint64_t resolved_file_offset = 0;
  if (!VaToUniqueExecutableFile(image, header, found_value, found_size, &resolved_file_offset) ||
      resolved_file_offset != text.offset + delta) {
    return false;
  }
  *offset = resolved_file_offset;
  return true;
}

bool ReadLe32(std::string_view image, uint64_t offset, uint32_t* value) {
  if (value == nullptr || !Range(image, offset, sizeof(uint32_t))) return false;
  const unsigned char* bytes = reinterpret_cast<const unsigned char*>(image.data() + offset);
  *value = static_cast<uint32_t>(bytes[0]) |
      (static_cast<uint32_t>(bytes[1]) << 8) |
      (static_cast<uint32_t>(bytes[2]) << 16) |
      (static_cast<uint32_t>(bytes[3]) << 24);
  return true;
}

bool MatchLe32(std::string_view image, uint64_t offset, const uint32_t* words,
               size_t count) {
  if (words == nullptr || count == 0 || count > UINT64_MAX / sizeof(uint32_t) ||
      !Range(image, offset, static_cast<uint64_t>(count) * sizeof(uint32_t))) {
    return false;
  }
  for (size_t index = 0; index < count; ++index) {
    uint32_t word = 0;
    if (!ReadLe32(image, offset + static_cast<uint64_t>(index) * sizeof(uint32_t), &word) ||
        word != words[index]) {
      return false;
    }
  }
  return true;
}

bool ResolveDuckStationImage(std::string_view image, const ElfHeader& header,
                             uint64_t* offset) {
  constexpr uint16_t kTextSection = 14;
  if (offset == nullptr || header.shentsize != sizeof(SectionHeader) ||
      header.shnum <= kTextSection || header.shnum > 4096 ||
      !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) {
    return false;
  }
  SectionHeader text{};
  if (!ReadObject(image, header.shoff + static_cast<uint64_t>(kTextSection) * sizeof(SectionHeader),
                  &text) || text.type != kShtProgbits ||
      text.flags != (kShfAlloc | kShfExecInstr) || text.addr != kDuckStationTextAddr ||
      text.offset != kDuckStationTextOffset || text.size != kDuckStationTextSize ||
      !Range(image, text.offset, text.size) ||
      !SectionHasName(image, header, kTextSection, ".text")) {
    return false;
  }

  uint64_t update_offset = 0;
  uint64_t handler_offset = 0;
  if (!VaToUniqueExecutableFile(image, header, kDuckStationUpdateVa,
                                kDuckStationUpdateRange, &update_offset) ||
      !VaToUniqueExecutableFile(image, header, kDuckStationHandlerVa,
                                kDuckStationHandlerRange, &handler_offset) ||
      update_offset != kDuckStationUpdateVa || handler_offset != kDuckStationHandlerVa) {
    return false;
  }
  constexpr uint32_t kUpdatePrefix[] = {0xd10483ffU, 0xfd004beeU, 0x6d09b3edU};
  constexpr uint32_t kUpdateArguments[] = {0x2a0203f5U, 0x2a0103f6U};
  constexpr uint32_t kHandlerWords[] = {
      0x360001b5U, 0x79403288U, 0x36100088U, 0x29400a81U, 0xaa1303e0U, 0x9401e74cU};
  if (!MatchLe32(image, update_offset, kUpdatePrefix,
                 sizeof(kUpdatePrefix) / sizeof(kUpdatePrefix[0])) ||
      !MatchLe32(image, update_offset + 0x34, kUpdateArguments,
                 sizeof(kUpdateArguments) / sizeof(kUpdateArguments[0])) ||
      !MatchLe32(image, handler_offset + 0xac, kHandlerWords,
                 sizeof(kHandlerWords) / sizeof(kHandlerWords[0]))) {
    return false;
  }
  uint32_t call = 0;
  if (!ReadLe32(image, handler_offset + 0xac + 5 * sizeof(uint32_t), &call) ||
      (call & 0xfc000000U) != 0x94000000U) {
    return false;
  }
  int64_t immediate = static_cast<int64_t>(call & 0x03ffffffU);
  if ((immediate & (1LL << 25)) != 0) immediate |= ~((1LL << 26) - 1);
  const int64_t target = static_cast<int64_t>(kDuckStationHandlerVa + 0xac +
                                               5 * sizeof(uint32_t)) + immediate * 4;
  if (target != static_cast<int64_t>(kDuckStationUpdateVa)) return false;
  *offset = update_offset;
  return true;
}

bool ResolveArmsX2Image(
    std::string_view image, const ElfHeader& header,
    std::array<uint64_t, ArmsX2Counter::kProbeCount>* offsets,
    bool is_16k) {
  constexpr uint16_t kTextSection = 15;
  const uint64_t text_size = is_16k ? kArmsX2TextSize16K : kArmsX2TextSize4K;
  const uint64_t executable_size = is_16k ? kArmsX2ExecutableSize16K : kArmsX2ExecutableSize4K;
  const uint64_t convert_value_va = is_16k ? 0x54409cULL : 0x5431d4ULL;
  if (offsets == nullptr || header.shentsize != sizeof(SectionHeader) ||
      header.shnum <= kTextSection || header.shnum > 4096 ||
      !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) {
    return false;
  }

  SectionHeader text{};
  if (!ReadObject(image, header.shoff + static_cast<uint64_t>(kTextSection) * sizeof(SectionHeader),
                  &text) || text.type != kShtProgbits ||
      text.flags != (kShfAlloc | kShfExecInstr) || text.addr != kArmsX2TextAddr ||
      text.offset != kArmsX2TextOffset || text.size != text_size ||
      !Range(image, text.offset, text.size) ||
      !SectionHasName(image, header, kTextSection, ".text")) {
    return false;
  }

  size_t executable_matches = 0;
  for (uint16_t index = 0; index < header.phnum; ++index) {
    ProgramHeader program{};
    if (!ReadProgram(image, header, index, &program)) return false;
    if (program.type != kPtLoad) continue;
    if (!Range(image, program.offset, program.filesz)) return false;
    if ((program.flags & kPfX) == 0) continue;
    if (program.vaddr == kArmsX2TextAddr && program.offset == kArmsX2TextOffset &&
        program.filesz == executable_size) {
      ++executable_matches;
    }
  }
  if (executable_matches != 1) return false;

  uint64_t update_offset = 0;
  uint64_t delta_offset = 0;
  uint64_t blit_offset = 0;
  uint64_t privileged_offset = 0;
  if (!VaToUniqueExecutableFile(image, header, kArmsX2UpdateVa, kArmsX2UpdateRange,
                                &update_offset) ||
      !VaToUniqueExecutableFile(image, header, kArmsX2DeltaVa, 4, &delta_offset) ||
      !VaToUniqueExecutableFile(image, header, kArmsX2BlitVa, 4, &blit_offset) ||
      !VaToUniqueExecutableFile(image, header, kArmsX2PrivilegedVa, 4, &privileged_offset)) {
    return false;
  }
  uint64_t zero_offset = 0;
  if (!VaToUniqueExecutableFile(image, header, kArmsX2ZeroVa, 4, &zero_offset) ||
      update_offset != kArmsX2UpdateVa || delta_offset != kArmsX2DeltaVa ||
      blit_offset != kArmsX2BlitVa || privileged_offset != kArmsX2PrivilegedVa ||
      zero_offset != kArmsX2ZeroVa) {
    return false;
  }

  constexpr uint32_t kUpdatePrefix[] = {
      0xd10303ffU, 0xfd001becU, 0x6d042bebU, 0x6d0523e9U};
  constexpr uint32_t kUpdateArguments[] = {0x2a0103f3U, 0x2a0003f4U};
  constexpr uint32_t kDeltaContext[] = {0xf94162c8U, 0xcb080000U};
  constexpr uint32_t kDeltaTail[] = {
      0x1e624008U, 0x1e2c1000U, 0x1e202100U, 0x54001fa4U};
  constexpr uint32_t kBranchZero[] = {0x34000088U};
  constexpr uint32_t kHackAdrp[] = {0xd0060469U};
  constexpr uint32_t kHackLoad[] = {0x39722929U};
  constexpr uint32_t kHackBranch[] = {0x360000e9U};
  constexpr uint32_t kBlitLoad[] = {0xb9463e88U};
  constexpr uint32_t kBlitZero[] = {0x34000128U};
  constexpr uint32_t kBlitWord[] = {0x1e230100U};
  constexpr uint32_t kPrivilegedWord[] = {0x1e230100U};
  constexpr uint32_t kZeroWord[] = {0x2f00e400U};
  constexpr uint32_t kResultStore[] = {0xbd037d20U};
  if (!MatchLe32(image, update_offset, kUpdatePrefix,
                 sizeof(kUpdatePrefix) / sizeof(kUpdatePrefix[0])) ||
      !MatchLe32(image, update_offset + 0x30, kUpdateArguments,
                 sizeof(kUpdateArguments) / sizeof(kUpdateArguments[0])) ||
      !MatchLe32(image, update_offset + 0x12c, kDeltaContext,
                 sizeof(kDeltaContext) / sizeof(kDeltaContext[0])) ||
      !MatchLe32(image, delta_offset + 0x04, kDeltaTail,
                 sizeof(kDeltaTail) / sizeof(kDeltaTail[0])) ||
      !MatchLe32(image, update_offset + 0x208, kBranchZero, 1) ||
      !MatchLe32(image, update_offset + 0x20c, kHackAdrp, 1) ||
      !MatchLe32(image, update_offset + 0x210, kHackLoad, 1) ||
      !MatchLe32(image, update_offset + 0x214, kHackBranch, 1) ||
      !MatchLe32(image, update_offset + 0x218, kBlitLoad, 1) ||
      !MatchLe32(image, update_offset + 0x21c, kBlitZero, 1) ||
      !MatchLe32(image, update_offset + 0x220, kBlitWord, 1) ||
      !MatchLe32(image, update_offset + 0x230, kPrivilegedWord, 1) ||
      !MatchLe32(image, zero_offset, kZeroWord, 1) ||
      !MatchLe32(image, update_offset + 0x250, kResultStore, 1)) {
    return false;
  }
  uint32_t call = 0;
  if (!ReadLe32(image, delta_offset, &call) || (call & 0xfc000000U) != 0x94000000U) {
    return false;
  }
  int64_t immediate = static_cast<int64_t>(call & 0x03ffffffU);
  if ((immediate & (1LL << 25)) != 0) immediate |= ~((1LL << 26) - 1);
  const int64_t target = static_cast<int64_t>(kArmsX2DeltaVa) + immediate * 4;
  if (target != static_cast<int64_t>(convert_value_va)) return false;

  *offsets = {delta_offset, blit_offset, privileged_offset, zero_offset};
  return true;
}

// ---- Eden shape path -------------------------------------------------------------------------
// an Eden-family build whose GNU Build ID
// is not pinned is admitted when the hook function itself is the known wrapper. This deliberately
// relaxes the 09-08 spec's "unknown builds fail closed" for the Eden family only; every other
// family keeps its exact Build ID pin. The fork is a rolling nightly, so a pin goes stale on each
// self-update. See kEdenShapePattern / kEdenShapeMask in render_fps_eden.h.

// Lowercase hex of the first GNU Build ID note, or empty when absent or malformed.
std::string ReadGnuBuildIdHex(std::string_view image, const ElfHeader& header) {
  static const char kDigits[] = "0123456789abcdef";
  for (uint16_t i = 0; i < header.phnum; ++i) {
    ProgramHeader program{};
    if (!ReadProgram(image, header, i, &program)) return std::string();
    if (program.type != kPtNote || !Range(image, program.offset, program.filesz)) continue;
    uint64_t cursor = program.offset;
    const uint64_t end = program.offset + program.filesz;
    while (cursor < end) {
      if (end - cursor < 12) return std::string();
      uint32_t namesz = 0;
      uint32_t descsz = 0;
      uint32_t type = 0;
      if (!ReadObject(image, cursor, &namesz) || !ReadObject(image, cursor + 4, &descsz) ||
          !ReadObject(image, cursor + 8, &type)) return std::string();
      const uint64_t name_padded = (static_cast<uint64_t>(namesz) + 3U) & ~3ULL;
      const uint64_t desc_padded = (static_cast<uint64_t>(descsz) + 3U) & ~3ULL;
      if (name_padded > end - cursor - 12 || desc_padded > end - cursor - 12 - name_padded) {
        return std::string();
      }
      const uint64_t name_offset = cursor + 12;
      const uint64_t desc_offset = name_offset + name_padded;
      if (type == kNtGnuBuildId && namesz == 4 && descsz >= 1 && descsz <= 64 &&
          memcmp(image.data() + static_cast<size_t>(name_offset), "GNU\0", 4) == 0) {
        std::string hex;
        hex.reserve(static_cast<size_t>(descsz) * 2);
        for (uint32_t byte = 0; byte < descsz; ++byte) {
          const unsigned char value = static_cast<unsigned char>(image[desc_offset + byte]);
          hex.push_back(kDigits[value >> 4]);
          hex.push_back(kDigits[value & 0x0f]);
        }
        return hex;
      }
      cursor = desc_offset + desc_padded;
    }
  }
  return std::string();
}

struct NamedSymbol {
  bool found = false;
  bool ambiguous = false;
  Symbol symbol{};
};

// Scans every .dynsym and .symtab for DEFINED symbols with this exact name. Unlike
// ResolveSymbolFromSections it filters neither type nor size, so the caller can say why a
// candidate was refused. A name present in both tables is one symbol when the copies agree;
// copies that disagree on type, section, value or size are ambiguous. A malformed table is skipped.
NamedSymbol FindDefinedSymbol(std::string_view image, const ElfHeader& header,
                              std::string_view name) {
  NamedSymbol result;
  if (header.shentsize != sizeof(SectionHeader) || header.shnum == 0 || header.shnum > 4096 ||
      !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) {
    return result;
  }
  for (uint16_t i = 0; i < header.shnum; ++i) {
    SectionHeader section{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(i) * sizeof(SectionHeader), &section)) {
      return NamedSymbol{};
    }
    if ((section.type != kShtDynsym && section.type != kShtSymtab) ||
        section.entsize != sizeof(Symbol) || section.link >= header.shnum ||
        section.size % sizeof(Symbol) != 0 || !Range(image, section.offset, section.size)) {
      continue;
    }
    SectionHeader strings{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(section.link) * sizeof(SectionHeader),
                    &strings) || strings.type != kShtStrtab ||
        !Range(image, strings.offset, strings.size)) {
      continue;
    }
    const uint64_t count = section.size / sizeof(Symbol);
    for (uint64_t index = 0; index < count; ++index) {
      Symbol symbol{};
      if (!ReadObject(image, section.offset + index * sizeof(Symbol), &symbol)) break;
      if (symbol.shndx == kShnUndef || symbol.name >= strings.size ||
          strings.size - symbol.name < name.size() + 1) {
        continue;
      }
      const char* text = image.data() + static_cast<size_t>(strings.offset + symbol.name);
      if (memcmp(text, name.data(), name.size()) != 0 || text[name.size()] != '\0') continue;
      if (!result.found) {
        result.found = true;
        result.symbol = symbol;
      } else if ((symbol.info & 0x0f) != (result.symbol.info & 0x0f) ||
                 symbol.shndx != result.symbol.shndx || symbol.value != result.symbol.value ||
                 symbol.size != result.symbol.size) {
        result.ambiguous = true;
      }
    }
  }
  return result;
}

// (word & mask) == pattern for `count` little-endian words at `offset`.
bool MatchMaskedLe32(std::string_view image, uint64_t offset, const uint32_t* pattern,
                     const uint32_t* mask, size_t count) {
  if (pattern == nullptr || mask == nullptr || count == 0 ||
      !Range(image, offset, static_cast<uint64_t>(count) * sizeof(uint32_t))) {
    return false;
  }
  for (size_t index = 0; index < count; ++index) {
    uint32_t word = 0;
    if (!ReadLe32(image, offset + static_cast<uint64_t>(index) * sizeof(uint32_t), &word) ||
        (word & mask[index]) != pattern[index]) {
      return false;
    }
  }
  return true;
}

// Target VA of an AArch64 B/BL whose instruction sits at `pc`.
bool BranchTarget(uint64_t pc, uint32_t word, uint64_t* target) {
  int64_t imm = static_cast<int64_t>(word & 0x03ffffffU);
  if ((imm & 0x02000000) != 0) imm -= 0x04000000;
  const int64_t delta = imm * 4;
  if (delta < 0 ? static_cast<uint64_t>(-delta) > pc
                : pc > UINT64_MAX - static_cast<uint64_t>(delta)) {
    return false;
  }
  *target = pc + static_cast<uint64_t>(delta);
  return true;
}

// True when [va, va + size) lies inside one allocated, executable PROGBITS section (.text, .plt...).
bool InExecutableSection(std::string_view image, const ElfHeader& header, uint64_t va,
                         uint64_t size) {
  if (header.shentsize != sizeof(SectionHeader) || header.shnum == 0 || header.shnum > 4096 ||
      !TableRange(image, header.shoff, header.shnum, sizeof(SectionHeader))) {
    return false;
  }
  for (uint16_t i = 0; i < header.shnum; ++i) {
    SectionHeader section{};
    if (!ReadObject(image, header.shoff + static_cast<uint64_t>(i) * sizeof(SectionHeader), &section)) {
      return false;
    }
    if (section.type != kShtProgbits ||
        (section.flags & (kShfAlloc | kShfExecInstr)) != (kShfAlloc | kShfExecInstr) ||
        va < section.addr || va - section.addr > section.size ||
        size > section.size - (va - section.addr)) {
      continue;
    }
    return true;
  }
  return false;
}

EdenShapeReject ResolveEdenByShape(std::string_view image, const ElfHeader& header,
                                   uint64_t* offset) {
  const NamedSymbol found = FindDefinedSymbol(image, header, kEdenRendererFrameEndNotifySymbol);
  if (!found.found) return EdenShapeReject::kMissing;
  if (found.ambiguous) return EdenShapeReject::kAmbiguous;
  const Symbol& symbol = found.symbol;
  if ((symbol.info & 0x0f) != kSttFunc) return EdenShapeReject::kNotFunc;
  if (symbol.size != kEdenRendererFrameEndNotifySize) return EdenShapeReject::kSize;

  // The symbol must sit inside an allocated, executable PROGBITS section AND an executable
  // PT_LOAD, and the two must agree on where its bytes are in the file.
  if (symbol.shndx >= header.shnum) return EdenShapeReject::kNotExecutable;
  SectionHeader section{};
  if (!ReadObject(image, header.shoff + static_cast<uint64_t>(symbol.shndx) * sizeof(SectionHeader),
                  &section) || section.type != kShtProgbits ||
      (section.flags & (kShfAlloc | kShfExecInstr)) != (kShfAlloc | kShfExecInstr) ||
      symbol.value < section.addr || symbol.value - section.addr > section.size ||
      symbol.size > section.size - (symbol.value - section.addr)) {
    return EdenShapeReject::kNotExecutable;
  }
  const uint64_t into_section = symbol.value - section.addr;
  uint64_t file_offset = 0;
  if (!VaToUniqueExecutableFile(image, header, symbol.value, symbol.size, &file_offset) ||
      section.offset > UINT64_MAX - into_section || file_offset != section.offset + into_section) {
    return EdenShapeReject::kNotExecutable;
  }

  // The words are read at the FILE offset; vaddr and file offset differ in the real fork build.
  if (!MatchMaskedLe32(image, file_offset, kEdenShapePattern.data(), kEdenShapeMask.data(),
                       kEdenShapeWordCount)) {
    return EdenShapeReject::kBytes;
  }
  // The masked BL and the tail B must land in executable code: inside the one executable PT_LOAD
  // and inside an SHF_ALLOC|SHF_EXECINSTR section (.text, or a .plt stub).
  for (const size_t word_index : {size_t{4}, size_t{6}}) {
    uint32_t word = 0;
    uint64_t target = 0;
    uint64_t target_file_offset = 0;
    if (!ReadLe32(image, file_offset + word_index * sizeof(uint32_t), &word) ||
        !BranchTarget(symbol.value + word_index * sizeof(uint32_t), word, &target) ||
        !VaToUniqueExecutableFile(image, header, target, 4, &target_file_offset) ||
        !InExecutableSection(image, header, target, 4)) {
      return EdenShapeReject::kTarget;
    }
  }
  *offset = file_offset;
  return EdenShapeReject::kNone;
}

std::string_view EdenShapeRejectName(EdenShapeReject reject) {
  switch (reject) {
    case EdenShapeReject::kImage: return "shape_image";
    case EdenShapeReject::kMissing: return "shape_missing";
    case EdenShapeReject::kAmbiguous: return "shape_ambiguous";
    case EdenShapeReject::kNotFunc: return "shape_not_func";
    case EdenShapeReject::kSize: return "shape_size";
    case EdenShapeReject::kNotExecutable: return "shape_not_exec";
    case EdenShapeReject::kBytes: return "shape_bytes";
    case EdenShapeReject::kTarget: return "shape_target";
    case EdenShapeReject::kIdentity: return "identity_changed";
    case EdenShapeReject::kNone: break;
  }
  return "none";
}

}  // namespace

bool ResolveQueueBufferOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  return ReadHeader(image, &header) && ResolveSymbolFromSections(image, header, kQueueBufferSymbol, 0, offset);
}

bool ResolvePpssppActualFlipOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  return ReadHeader(image, &header) &&
      (MatchBuildId(image, header, kPpssppBuildIdHex) ||
       MatchBuildId(image, header, kPpssppOdinBuildIdHex)) &&
      ResolveSymbolFromSections(image, header, kPpssppActualFlipSymbol, kPpssppActualFlipSize, offset);
}

bool ResolveEdenRendererFrameEnd(std::string_view image, uint64_t* offset,
                                 EdenResolution* resolution) {
  EdenResolution local;
  EdenResolution& out = resolution != nullptr ? *resolution : local;
  out = EdenResolution{};
  if (offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header)) {
    out.reject = EdenShapeReject::kImage;
    return false;
  }
  out.build_id_hex = ReadGnuBuildIdHex(image, header);
  uint64_t resolved = 0;
  // Fast path, unchanged: a pinned Build ID plus the exact 28-byte symbol range.
  if ((MatchBuildId(image, header, mimir::render_fps::kEdenBuildIdHex) ||
       MatchBuildId(image, header, mimir::render_fps::kEdenOptimizedBuildIdHex)) &&
      ResolveSymbolFromSections(image, header, mimir::render_fps::kEdenRendererFrameEndNotifySymbol,
                                mimir::render_fps::kEdenRendererFrameEndNotifySize, &resolved)) {
    out.admission = EdenAdmission::kPinned;
    *offset = resolved;
    return true;
  }
  const EdenShapeReject reject = ResolveEdenByShape(image, header, &resolved);
  if (reject != EdenShapeReject::kNone) {
    out.reject = reject;
    return false;
  }
  out.admission = EdenAdmission::kShape;
  *offset = resolved;
  return true;
}

bool ResolveEdenRendererFrameEndOffset(std::string_view image, uint64_t* offset) {
  return ResolveEdenRendererFrameEnd(image, offset, nullptr);
}

std::string FormatEdenTargetLog(const EdenResolution& resolution) {
  const std::string build_id = resolution.build_id_hex.empty() ? "none" : resolution.build_id_hex;
  switch (resolution.admission) {
    case EdenAdmission::kPinned: return "eden_target=pinned build_id=" + build_id;
    case EdenAdmission::kShape: return "eden_target=shape build_id=" + build_id;
    case EdenAdmission::kRejected: break;
  }
  return "eden_target=rejected reason=" + std::string(EdenShapeRejectName(resolution.reject)) +
      " build_id=" + build_id;
}

bool ResolveAzaharSetBufferSwapOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header)) return false;
  if (MatchBuildId(image, header, mimir::render_fps::kAzaharThorBuildIdHex)) {
    return ResolveSymbolFromSections(image, header, mimir::render_fps::kAzaharSetBufferSwapSymbol,
                                     mimir::render_fps::kAzaharThorSetBufferSwapSize, offset);
  }
  if (MatchBuildId(image, header, mimir::render_fps::kAzaharOfficialBuildIdHex)) {
    return ResolveSymbolFromSections(image, header, mimir::render_fps::kAzaharSetBufferSwapSymbol,
                                     mimir::render_fps::kAzaharOfficialSetBufferSwapSize, offset);
  }
  return false;
}

bool ResolveCemuSwapScanBufferOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  return ReadHeader(image, &header) &&
      MatchBuildId(image, header, mimir::render_fps::kCemuBuildIdHex) &&
      ResolveSymbolFromSections(image, header, mimir::render_fps::kCemuSwapScanBufferSymbol,
                                mimir::render_fps::kCemuSwapScanBufferSize, offset, true);
}

bool ResolveRetroArchFrameOffsets(std::string_view image, uint64_t* frame_offset,
                                  uint64_t* post_offset) {
  if (frame_offset == nullptr || post_offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header) || !MatchBuildId(image, header, kRetroArchBuildIdHex)) {
    return false;
  }
  uint64_t resolved_frame = 0;
  uint64_t resolved_post = 0;
  if (!ResolveExactDynamicSymbol(image, header, kRetroArchFrameSymbol, kRetroArchFrameVa,
                                 kRetroArchFrameSize, &resolved_frame) ||
      !VaToUniqueExecutableFile(image, header, kRetroArchFrameVa, kRetroArchFrameSize,
                                &resolved_frame) ||
      !VaToUniqueExecutableFile(image, header, kRetroArchPostVa, 12, &resolved_post)) {
    return false;
  }
  // The callback entry and the post-driver probe are guarded by exact words from this
  // frontend image. The post sequence starts with BLR X8 at VA 0xbd1c94 and leaves
  // the driver's Boolean result in W0 at VA 0xbd1c98.
  constexpr uint32_t kFramePrefix[] = {0xfc190fe8U, 0xa9017bfdU, 0xa9026ffcU};
  constexpr uint32_t kPostSequence[] = {0xd63f0100U, 0xb9420268U, 0x36000060U};
  uint64_t frame_check = 0;
  uint64_t post_check = 0;
  if (!VaToUniqueExecutableFile(image, header, kRetroArchFrameVa,
                                sizeof(kFramePrefix), &frame_check) ||
      !VaToUniqueExecutableFile(image, header, kRetroArchPostVa - 4,
                                sizeof(kPostSequence), &post_check) ||
      frame_check != resolved_frame || post_check > UINT64_MAX - 4 || post_check + 4 != resolved_post ||
      !MatchLe32(image, frame_check, kFramePrefix,
                 sizeof(kFramePrefix) / sizeof(kFramePrefix[0])) ||
      !MatchLe32(image, post_check, kPostSequence,
                 sizeof(kPostSequence) / sizeof(kPostSequence[0]))) {
    return false;
  }
  *frame_offset = resolved_frame;
  *post_offset = resolved_post;
  return true;
}

bool ReadRetroArchExecutableRanges(
    std::string_view image,
    std::array<RetroArchExecutableRange, kRetroArchMaxExecutableRanges>* ranges,
    size_t* count) {
  if (ranges == nullptr || count == nullptr) return false;
  *count = 0;
  ElfHeader header{};
  if (!ReadHeader(image, &header)) return false;
  for (uint16_t index = 0; index < header.phnum; ++index) {
    ProgramHeader program{};
    if (!ReadProgram(image, header, index, &program)) return false;
    if (program.type != kPtLoad) continue;
    if (!Range(image, program.offset, program.filesz)) return false;
    if ((program.flags & kPfX) == 0 || program.filesz == 0) continue;
    if (*count >= kRetroArchMaxExecutableRanges) return false;
    (*ranges)[*count] = {program.offset, program.filesz};
    ++*count;
  }
  return *count != 0;
}

bool ResolveDuckStationUpdateOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header) ||
      !MatchBuildId(image, header, kDuckStationBuildIdHex)) {
    return false;
  }
  return ResolveDuckStationImage(image, header, offset);
}

bool ResolveArmsX2Offsets(
    std::string_view image,
    std::array<uint64_t, ArmsX2Counter::kProbeCount>* offsets) {
  if (offsets == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header)) return false;
  std::array<uint64_t, ArmsX2Counter::kProbeCount> resolved{};
  if (MatchBuildId(image, header, kArmsX2BuildId4K)) {
    if (!ResolveArmsX2Image(image, header, &resolved, false)) return false;
  } else if (MatchBuildId(image, header, kArmsX2BuildId16K)) {
    if (!ResolveArmsX2Image(image, header, &resolved, true)) return false;
  } else {
    return false;
  }
  *offsets = resolved;
  return true;
}

bool ResolveX1BoxIncrementOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  return ReadHeader(image, &header) && MatchBuildId(image, header, kX1BoxBuildIdHex) &&
      ResolveX1BoxSymbolFromSymtab(image, header, offset);
}

bool ResolveFlycastPresentOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header) ||
      (!MatchBuildId(image, header, mimir::render_fps::kFlycastThorBuildIdHex) &&
       !MatchBuildId(image, header, mimir::render_fps::kFlycastOdinBuildIdHex))) {
    return false;
  }
  return ResolveSymbolFromSections(image, header, mimir::render_fps::kFlycastPresentSymbol,
                                   mimir::render_fps::kFlycastPresentSize, offset, true);
}

bool ResolveMupen64PlusSwapOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header) ||
      !MatchBuildId(image, header, mimir::render_fps::kMupen64PlusThorBuildIdHex)) {
    return false;
  }
  return ResolveSymbolFromSections(image, header, mimir::render_fps::kMupen64PlusSwapSymbol,
                                   mimir::render_fps::kMupen64PlusSwapSize, offset, true);
}

bool ResolveMupenBridgeSwapOffset(std::string_view image, uint64_t* offset) {
  if (offset == nullptr) return false;
  ElfHeader header{};
  uint64_t symbol_offset = 0, function_offset = 0, call_offset = 0, plt_offset = 0;
  if (!ReadHeader(image, &header) || !MatchBuildId(image, header, kMupenBridgeThorBuildIdHex) ||
      !ResolveSymbolFromSections(image, header, kMupenBridgeSwapSymbol,
                                 kMupenBridgeSwapSize, &symbol_offset, true) ||
      !VaToFile(image, header, 0x70c4, kMupenBridgeSwapSize, &function_offset) ||
      symbol_offset != function_offset ||
      !VaToFile(image, header, 0x71cc, 4, &call_offset) ||
      !VaToFile(image, header, 0x54f0, 16, &plt_offset)) return false;
  uint32_t instruction = 0;
  if (!ReadObject(image, call_offset, &instruction) || instruction != 0x97fff8c9U) return false;
  // Build-specific import stub and caller; do not turn this into a raw-offset fallback.
  constexpr uint32_t expected[] = {0xd0000050U, 0xf9450e11U, 0x91286210U, 0xd61f0220U};
  for (size_t i = 0; i < 4; ++i) {
    if (!ReadObject(image, plt_offset + i * 4, &instruction) || instruction != expected[i]) return false;
  }
  *offset = plt_offset;
  return true;
}

bool ResolveMelonDsOffsets(std::string_view image, uint64_t* run_frame_offset,
                           uint64_t* discard_offset, uint64_t* push_offset) {
  if (run_frame_offset == nullptr || discard_offset == nullptr || push_offset == nullptr) return false;
  ElfHeader header{};
  if (!ReadHeader(image, &header) ||
      (!MatchBuildId(image, header, mimir::render_fps::kMelonDsCanonicalBuildId) &&
       !MatchBuildId(image, header, mimir::render_fps::kMelonDsThorBetaBuildId))) {
    return false;
  }
  uint64_t run_frame = 0;
  uint64_t discard = 0;
  uint64_t push = 0;
  if (!ResolveSymbolFromSections(image, header, mimir::render_fps::kMelonDsRunFrameSymbol, 0,
                                 &run_frame, true) ||
      !ResolveSymbolFromSections(image, header,
                                 mimir::render_fps::kMelonDsDiscardRenderedFrameSymbol, 0,
                                 &discard, true) ||
      !ResolveSymbolFromSections(image, header,
                                 mimir::render_fps::kMelonDsPushRenderedFrameSymbol, 0,
                                 &push, true)) {
    return false;
  }
  *run_frame_offset = run_frame;
  *discard_offset = discard;
  *push_offset = push;
  return true;
}

namespace {

uint16_t Zip16(std::string_view archive, uint64_t offset) {
  uint16_t value = 0;
  memcpy(&value, archive.data() + static_cast<size_t>(offset), sizeof(value));
  return value;
}

uint32_t Zip32(std::string_view archive, uint64_t offset) {
  uint32_t value = 0;
  memcpy(&value, archive.data() + static_cast<size_t>(offset), sizeof(value));
  return value;
}

bool ZipRange(std::string_view archive, uint64_t offset, uint64_t count) {
  return offset <= archive.size() && count <= archive.size() - static_cast<size_t>(offset);
}

struct ZipRangePair {
  uint64_t start = 0;
  uint64_t end = 0;
};

}  // namespace

bool ResolveStoredZipMember(std::string_view archive, std::string_view member_name,
                            StoredZipMember* member) {
  if (member == nullptr || member_name.empty() || archive.size() < 22) return false;
  const uint64_t search_start = archive.size() > 22 + 65535 ? archive.size() - (22 + 65535) : 0;
  uint64_t eocd = std::numeric_limits<uint64_t>::max();
  for (uint64_t cursor = archive.size() - 22;; --cursor) {
    if (Zip32(archive, cursor) == 0x06054b50U) {
      eocd = cursor;
      break;
    }
    if (cursor == search_start) break;
  }
  if (eocd == std::numeric_limits<uint64_t>::max() || eocd + 22 > archive.size()) return false;
  const uint16_t disk = Zip16(archive, eocd + 4);
  const uint16_t central_disk = Zip16(archive, eocd + 6);
  const uint16_t entries_on_disk = Zip16(archive, eocd + 8);
  const uint16_t entries = Zip16(archive, eocd + 10);
  const uint32_t central_size = Zip32(archive, eocd + 12);
  const uint32_t central_offset = Zip32(archive, eocd + 16);
  const uint16_t comment_size = Zip16(archive, eocd + 20);
  if (disk != 0 || central_disk != 0 || entries_on_disk != entries || entries == 0xffff ||
      central_size == 0xffffffffU || central_offset == 0xffffffffU ||
      comment_size != archive.size() - (eocd + 22) ||
      !ZipRange(archive, central_offset, central_size)) return false;
  const uint64_t central_end = static_cast<uint64_t>(central_offset) + central_size;
  if (central_end > eocd) return false;

  std::vector<ZipRangePair> ranges;
  ranges.reserve(static_cast<size_t>(entries) + 1);
  ranges.push_back({central_offset, central_end});
  uint64_t cursor = central_offset;
  uint64_t matching_members = 0;
  StoredZipMember found{};
  for (uint16_t index = 0; index < entries; ++index) {
    if (cursor > central_end || central_end - cursor < 46 || Zip32(archive, cursor) != 0x02014b50U) return false;
    const uint16_t flags = Zip16(archive, cursor + 8);
    const uint16_t method = Zip16(archive, cursor + 10);
    const uint32_t compressed_size = Zip32(archive, cursor + 20);
    const uint32_t uncompressed_size = Zip32(archive, cursor + 24);
    const uint16_t name_size = Zip16(archive, cursor + 28);
    const uint16_t extra_size = Zip16(archive, cursor + 30);
    const uint16_t comment_length = Zip16(archive, cursor + 32);
    const uint32_t local_offset = Zip32(archive, cursor + 42);
    const uint64_t record_size = 46ULL + name_size + extra_size + comment_length;
    if (record_size > central_end - cursor || !ZipRange(archive, cursor + 46, name_size) ||
        local_offset == 0xffffffffU || (flags & (1U | 8U)) != 0 ||
        local_offset > archive.size() || archive.size() - local_offset < 30 ||
        Zip32(archive, local_offset) != 0x04034b50U) return false;
    const std::string_view entry_name(archive.data() + static_cast<size_t>(cursor + 46), name_size);
    const uint16_t local_flags = Zip16(archive, local_offset + 6);
    const uint16_t local_method = Zip16(archive, local_offset + 8);
    const uint32_t local_compressed_size = Zip32(archive, local_offset + 18);
    const uint32_t local_uncompressed_size = Zip32(archive, local_offset + 22);
    const uint16_t local_name_size = Zip16(archive, local_offset + 26);
    const uint16_t local_extra_size = Zip16(archive, local_offset + 28);
    const uint64_t local_data = static_cast<uint64_t>(local_offset) + 30 + local_name_size + local_extra_size;
    if (local_flags != flags || local_method != method || local_compressed_size != compressed_size ||
        local_uncompressed_size != uncompressed_size || local_name_size != name_size ||
        local_data > archive.size() || compressed_size > archive.size() - local_data ||
        local_data > UINT64_MAX - compressed_size || local_data + compressed_size > central_offset ||
        !ZipRange(archive, local_offset + 30, local_name_size) ||
        std::string_view(archive.data() + static_cast<size_t>(local_offset + 30), local_name_size) != entry_name) return false;
    ranges.push_back({local_offset, local_data + compressed_size});
    if (entry_name == member_name) {
      if (method != 0 || compressed_size != uncompressed_size) return false;
      ++matching_members;
      found.data_offset = local_data;
      found.size = uncompressed_size;
    }
    cursor += record_size;
  }
  if (cursor != central_end || matching_members != 1) return false;
  std::sort(ranges.begin(), ranges.end(), [](const ZipRangePair& left, const ZipRangePair& right) {
    if (left.start != right.start) return left.start < right.start;
    return left.end < right.end;
  });
  for (size_t index = 1; index < ranges.size(); ++index) {
    if (ranges[index - 1].end > ranges[index].start) return false;
  }
  *member = found;
  return true;
}

}  // namespace mimir::render_fps
