#ifndef MIMIR_CHD_READER_H
#define MIMIR_CHD_READER_H

#include <stdint.h>

#include <libchdr/chd.h>
#include "rc_hash.h"

/*
 * One verified libchdr top borrowed for exactly one rc_hash iterator. The
 * iterator copies userdata into its derived iterators but never owns it.
 */
#define MIMIR_CHD_BORROW_COOKIE UINT32_C(0x52414348)
typedef struct mimir_chd_borrow {
  uint32_t cookie;
  const char* target_path;
  chd_file* top;
} mimir_chd_borrow_t;

/* Installs a per-iterator dispatcher. Raw BIN/CUE/GDI/ISO files continue
 * through rcheevos' own reader; only .chd paths are handled here. */
void mimir_install_cdreader(rc_hash_iterator_t* iterator);

/* Same dispatcher with one exact-path, non-owning verified CHD top. */
void mimir_install_cdreader_with_borrow(
    rc_hash_iterator_t* iterator, mimir_chd_borrow_t* borrow);

#endif
