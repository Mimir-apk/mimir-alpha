#include "mimir_chd_reader.h"

#include <ctype.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include <libchdr/chd.h>

#define MIMIR_CD_PAYLOAD_BYTES 2048U
#define MIMIR_CD_RAW_BYTES 2352U
#define MIMIR_TRACK_ALIGNMENT 4U

typedef struct mimir_chd_metadata {
  uint32_t track;
  uint32_t frames;
  uint32_t extra_frames;
  uint32_t frame_offset;
  uint32_t first_sector;
  char type[64];
} mimir_chd_metadata_t;

typedef struct mimir_chd_track {
  chd_file* chd;
  uint8_t* hunk;
  uint32_t hunk_number;
  uint32_t hunk_bytes;
  uint32_t unit_bytes;
  uint32_t frames_per_hunk;
  uint32_t track_frame;
  uint32_t track_frames;
  uint32_t first_sector;
  uint32_t frame_bytes;
  uint32_t payload_offset;
  int hunk_loaded;
  int owns_chd;
} mimir_chd_track_t;

static int mimir_chd_load_hunk(mimir_chd_track_t* track, uint32_t hunk_number);

static uint32_t mimir_padding_frames(uint32_t frames) {
  return ((frames + MIMIR_TRACK_ALIGNMENT - 1U) & ~(MIMIR_TRACK_ALIGNMENT - 1U)) - frames;
}

static int mimir_extension_is_chd(const char* path) {
  const char* extension;
  if (!path)
    return 0;

  extension = strrchr(path, '.');
  if (!extension)
    return 0;

  return tolower((unsigned char)extension[1]) == 'c' &&
         tolower((unsigned char)extension[2]) == 'h' &&
         tolower((unsigned char)extension[3]) == 'd' &&
         extension[4] == '\0';
}

static int mimir_read_metadata(chd_file* chd, uint32_t index, mimir_chd_metadata_t* out) {
  char raw[256];
  char subtype[32];
  char pgtype[32];
  char pgsub[32];
  uint32_t result_length = 0;
  uint32_t pregap = 0;
  uint32_t postgap = 0;
  uint32_t pad = 0;
  chd_error error;

  memset(out, 0, sizeof(*out));
  memset(raw, 0, sizeof(raw));
  memset(subtype, 0, sizeof(subtype));
  memset(pgtype, 0, sizeof(pgtype));
  memset(pgsub, 0, sizeof(pgsub));

  error = chd_get_metadata(chd, CDROM_TRACK_METADATA2_TAG, index, raw,
                           sizeof(raw) - 1U, &result_length, NULL, NULL);
  if (error == CHDERR_NONE) {
    if (sscanf(raw,
               "TRACK:%u TYPE:%63s SUBTYPE:%31s FRAMES:%u PREGAP:%u PGTYPE:%31s PGSUB:%31s POSTGAP:%u",
               &out->track, out->type, subtype, &out->frames, &pregap, pgtype, pgsub,
               &postgap) != 8)
      return 0;
    out->extra_frames = mimir_padding_frames(out->frames);
    return 1;
  }

  memset(raw, 0, sizeof(raw));
  error = chd_get_metadata(chd, CDROM_TRACK_METADATA_TAG, index, raw,
                           sizeof(raw) - 1U, &result_length, NULL, NULL);
  if (error == CHDERR_NONE) {
    if (sscanf(raw, "TRACK:%u TYPE:%63s SUBTYPE:%31s FRAMES:%u",
               &out->track, out->type, subtype, &out->frames) != 4)
      return 0;
    out->extra_frames = mimir_padding_frames(out->frames);
    return 1;
  }

  memset(raw, 0, sizeof(raw));
  error = chd_get_metadata(chd, GDROM_TRACK_METADATA_TAG, index, raw,
                           sizeof(raw) - 1U, &result_length, NULL, NULL);
  if (error == CHDERR_NONE) {
    if (sscanf(raw,
               "TRACK:%u TYPE:%63s SUBTYPE:%31s FRAMES:%u PAD:%u PREGAP:%u PGTYPE:%31s PGSUB:%31s POSTGAP:%u",
               &out->track, out->type, subtype, &out->frames, &pad, &pregap, pgtype,
               pgsub, &postgap) != 9)
      return 0;
    /* CHD track payloads are four-frame aligned. The PAD field describes
     * source layout; stored-frame traversal uses the alignment itself. */
    out->extra_frames = mimir_padding_frames(out->frames);
    return 1;
  }

  memset(raw, 0, sizeof(raw));
  error = chd_get_metadata(chd, DVD_METADATA_TAG, index, raw,
                           sizeof(raw) - 1U, &result_length, NULL, NULL);
  if (error == CHDERR_NONE) {
    const chd_header* header = chd_get_header(chd);
    if (!header || index != 0U || header->unitbytes == 0U)
      return 0;
    out->track = 1U;
    out->frames = (uint32_t)header->unitcount;
    memcpy(out->type, "DVD", 4U);
    return 1;
  }

  return 0;
}

static int mimir_find_track(chd_file* chd, uint32_t requested, mimir_chd_metadata_t* selected) {
  mimir_chd_metadata_t current;
  mimir_chd_metadata_t largest;
  mimir_chd_metadata_t last;
  uint32_t frame_offset = 0U;
  uint32_t sector_offset = 0U;
  uint32_t largest_frames = 0U;
  uint32_t index;
  int have_largest = 0;
  int have_last = 0;

  memset(&largest, 0, sizeof(largest));
  memset(&last, 0, sizeof(last));

  for (index = 0U; mimir_read_metadata(chd, index, &current); ++index) {
    current.frame_offset = frame_offset;

    if (current.track == requested) {
      *selected = current;
      selected->first_sector = sector_offset;
      return 1;
    }

    if (requested == RC_HASH_CDTRACK_FIRST_DATA && strcmp(current.type, "AUDIO") != 0) {
      *selected = current;
      selected->first_sector = sector_offset;
      return 1;
    }

    last = current;
    last.first_sector = sector_offset;
    have_last = 1;

    if (strcmp(current.type, "DVD") == 0) {
      largest = current;
      largest.first_sector = sector_offset;
      have_largest = 1;
    } else if (strcmp(current.type, "AUDIO") != 0 && current.frames > largest_frames) {
      largest_frames = current.frames;
      largest = current;
      largest.first_sector = sector_offset;
      have_largest = 1;
    }

    sector_offset += current.frames;
    frame_offset += current.frames + mimir_padding_frames(current.frames);
  }

  if (requested == RC_HASH_CDTRACK_LAST && have_last) {
    *selected = last;
    return 1;
  }
  if (requested == RC_HASH_CDTRACK_LARGEST && have_largest) {
    *selected = largest;
    return 1;
  }

  return 0;
}

static void mimir_chd_close_track(void* track_handle) {
  mimir_chd_track_t* track = (mimir_chd_track_t*)track_handle;
  if (!track)
    return;
  free(track->hunk);
  if (track->chd && track->owns_chd)
    chd_close(track->chd);
  free(track);
}

static void* mimir_chd_open_track_from_chd(
    chd_file* chd, uint32_t requested, int owns_chd) {
  mimir_chd_metadata_t metadata;
  mimir_chd_track_t* track = NULL;
  const chd_header* header;

  if (!chd)
    return NULL;
  if (!mimir_find_track(chd, requested, &metadata)) {
    if (owns_chd)
      chd_close(chd);
    return NULL;
  }

  header = chd_get_header(chd);
  if (!header || header->unitbytes == 0U || header->hunkbytes == 0U ||
      header->hunkbytes % header->unitbytes != 0U) {
    if (owns_chd)
      chd_close(chd);
    return NULL;
  }

  track = (mimir_chd_track_t*)calloc(1U, sizeof(*track));
  if (!track) {
    if (owns_chd)
      chd_close(chd);
    return NULL;
  }
  /* Transfer ownership before the second allocation so every failure path closes the CHD once. */
  track->chd = chd;
  track->owns_chd = owns_chd;
  track->hunk = (uint8_t*)malloc(header->hunkbytes);
  if (!track->hunk) {
    mimir_chd_close_track(track);
    return NULL;
  }

  track->hunk_bytes = header->hunkbytes;
  track->unit_bytes = header->unitbytes;
  track->frames_per_hunk = header->hunkbytes / header->unitbytes;
  track->track_frame = metadata.frame_offset;
  track->track_frames = metadata.frames;
  track->first_sector = metadata.first_sector;

  if (strcmp(metadata.type, "DVD") == 0) {
    track->frame_bytes = header->unitbytes;
    track->payload_offset = 0U;
  } else if (strcmp(metadata.type, "MODE1_RAW") == 0) {
    track->frame_bytes = MIMIR_CD_RAW_BYTES;
    track->payload_offset = 16U;
  } else if (strcmp(metadata.type, "MODE2_RAW") == 0) {
    track->frame_bytes = MIMIR_CD_RAW_BYTES;
    /* XA sectors carry an additional eight-byte subheader before the
     * 2048-byte form-1 payload used by the ISO-9660 reader. */
    track->payload_offset = 24U;
  } else if (strncmp(metadata.type, "MODE2/2336", 10U) == 0) {
    track->frame_bytes = 2336U;
    track->payload_offset = 8U;
  } else if (strncmp(metadata.type, "MODE", 4U) == 0) {
    track->frame_bytes = MIMIR_CD_PAYLOAD_BYTES;
    track->payload_offset = 0U;
  } else {
    track->frame_bytes = header->unitbytes;
    track->payload_offset = 0U;
  }

  /* The track metadata is only a storage hint: some PS1 CHDs are tagged
   * MODE1_RAW while their filesystem sectors are XA mode 2. Inspect the
   * ISO-9660 volume descriptor exactly as the reference CDFS reader does. */
  if (track->frame_bytes == MIMIR_CD_RAW_BYTES && track->track_frames > 16U) {
    const uint32_t frame = track->track_frame + 16U;
    const uint32_t hunk_number = frame / track->frames_per_hunk;
    const uint32_t frame_in_hunk = frame % track->frames_per_hunk;
    if (mimir_chd_load_hunk(track, hunk_number)) {
      const uint8_t* raw = track->hunk + (uint64_t)frame_in_hunk * track->unit_bytes;
      if (memcmp(raw + 25U, "CD001", 5U) == 0)
        track->payload_offset = 24U;
      else if (memcmp(raw + 17U, "CD001", 5U) == 0)
        track->payload_offset = 16U;
    }
  }

  if (track->payload_offset + MIMIR_CD_PAYLOAD_BYTES > track->unit_bytes) {
    mimir_chd_close_track(track);
    return NULL;
  }
  return track;
}

static void* mimir_chd_open_track(const char* path, uint32_t requested) {
  chd_file* chd = NULL;
  chd_error open_error = chd_open(path, CHD_OPEN_READ, NULL, &chd);
  if (open_error != CHDERR_NONE)
    return NULL;
  return mimir_chd_open_track_from_chd(chd, requested, 1);
}

static int mimir_chd_load_hunk(mimir_chd_track_t* track, uint32_t hunk_number) {
  if (track->hunk_loaded && track->hunk_number == hunk_number)
    return 1;
  if (chd_read(track->chd, hunk_number, track->hunk) != CHDERR_NONE)
    return 0;
  track->hunk_number = hunk_number;
  track->hunk_loaded = 1;
  return 1;
}

static size_t mimir_chd_read_sector(void* track_handle, uint32_t sector,
                                    void* buffer, size_t requested_bytes) {
  mimir_chd_track_t* track = (mimir_chd_track_t*)track_handle;
  uint32_t relative_sector;
  uint32_t chd_frame;
  uint32_t hunk_number;
  uint32_t frame_in_hunk;
  uint64_t byte_offset;

  if (!track || !buffer || sector < track->first_sector ||
      requested_bytes > MIMIR_CD_PAYLOAD_BYTES)
    return 0U;

  relative_sector = sector - track->first_sector;
  if (relative_sector >= track->track_frames)
    return 0U;

  chd_frame = track->track_frame + relative_sector;
  hunk_number = chd_frame / track->frames_per_hunk;
  frame_in_hunk = chd_frame % track->frames_per_hunk;
  if (!mimir_chd_load_hunk(track, hunk_number))
    return 0U;

  byte_offset = (uint64_t)frame_in_hunk * track->unit_bytes + track->payload_offset;
  if (byte_offset + requested_bytes > track->hunk_bytes)
    return 0U;

  memcpy(buffer, track->hunk + byte_offset, requested_bytes);
  return requested_bytes;
}

static uint32_t mimir_chd_first_track_sector(void* track_handle) {
  const mimir_chd_track_t* track = (const mimir_chd_track_t*)track_handle;
  return track ? track->first_sector : 0U;
}

static void* mimir_open_track_dispatch(const char* path, uint32_t track,
                                       const rc_hash_iterator_t* iterator) {
  if (mimir_extension_is_chd(path)) {
    const mimir_chd_borrow_t* borrow =
        (const mimir_chd_borrow_t*)iterator->userdata;
    rc_hash_callbacks_t* callbacks = (rc_hash_callbacks_t*)&iterator->callbacks;
    callbacks->cdreader.read_sector = mimir_chd_read_sector;
    callbacks->cdreader.close_track = mimir_chd_close_track;
    callbacks->cdreader.first_track_sector = mimir_chd_first_track_sector;
    callbacks->cdreader.open_track_iterator = mimir_open_track_dispatch;
    if (borrow && borrow->cookie == MIMIR_CHD_BORROW_COOKIE &&
        borrow->target_path && borrow->top &&
        strcmp(borrow->target_path, path) == 0)
      return mimir_chd_open_track_from_chd(borrow->top, track, 0);
    return mimir_chd_open_track(path, track);
  }

  /* rcheevos owns canonical BIN/CUE/GDI/ISO semantics. */
  {
    rc_hash_cdreader_t defaults;
    rc_hash_get_default_cdreader(&defaults);
    return defaults.open_track_iterator(path, track, iterator);
  }
}

void mimir_install_cdreader(rc_hash_iterator_t* iterator) {
  iterator->callbacks.cdreader.open_track_iterator = mimir_open_track_dispatch;
}

void mimir_install_cdreader_with_borrow(
    rc_hash_iterator_t* iterator, mimir_chd_borrow_t* borrow) {
  iterator->userdata = borrow;
  mimir_install_cdreader(iterator);
}
