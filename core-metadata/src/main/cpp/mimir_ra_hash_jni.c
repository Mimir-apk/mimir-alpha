#include <jni.h>

#include <stdlib.h>
#include <stdint.h>
#include <string.h>

#include <libchdr/chd.h>
#include "mimir_chd_reader.h"
#include "rc_hash.h"

typedef struct mimir_identity_chd {
  chd_file* chd;
  uint32_t hunk_bytes;
  uint32_t hunk_count;
  uint8_t* hunk_buffer;
} mimir_identity_chd_t;

#define MIMIR_IDENTITY_MAX_CHAIN_DEPTH 8U
#define MIMIR_IDENTITY_CHAIN_COOKIE UINT32_C(0x4d434844)

typedef struct mimir_identity_chd_chain {
  uint32_t cookie;
  chd_file* top;
  chd_file* nodes[MIMIR_IDENTITY_MAX_CHAIN_DEPTH];
  uint32_t node_count;
  uint32_t hunk_capacity;
  uint8_t* hunk_buffer;
} mimir_identity_chd_chain_t;

#define MIMIR_IDENTITY_MAX_LOGICAL_BYTES (UINT64_C(16) * 1024U * 1024U * 1024U)
#define MIMIR_IDENTITY_MAX_HUNK_BYTES (8U * 1024U * 1024U)
#define MIMIR_IDENTITY_MAX_HUNKS 1000000U
#define MIMIR_CHD_OPEN_OK 0
#define MIMIR_CHD_OPEN_IO_RETRYABLE 1
#define MIMIR_CHD_OPEN_PARENT_REQUIRED 2
#define MIMIR_CHD_OPEN_UNSUPPORTED 3
#define MIMIR_CHD_OPEN_RESOURCE_REFUSED 4
#define MIMIR_CHD_OPEN_MALFORMED 5
#define MIMIR_CHD_READ_OK 0
#define MIMIR_CHD_READ_IO_RETRYABLE 1
#define MIMIR_CHD_READ_RESOURCE_REFUSED 2
#define MIMIR_CHD_READ_UNSUPPORTED 3
#define MIMIR_CHD_READ_MALFORMED 4
#define MIMIR_CHD_METADATA_IO_RETRYABLE 5
#define MIMIR_CHD_METADATA_UNSUPPORTED 6

static jlongArray mimir_chd_open_result(JNIEnv* env, jlong status, jlong handle) {
  jlong values[2];
  jlongArray result = (*env)->NewLongArray(env, 2);
  if (!result)
    return NULL;
  values[0] = status;
  values[1] = handle;
  (*env)->SetLongArrayRegion(env, result, 0, 2, values);
  return (*env)->ExceptionCheck(env) ? NULL : result;
}

static jlong mimir_chd_open_status(chd_error error) {
  switch (error) {
    case CHDERR_FILE_NOT_FOUND:
    case CHDERR_READ_ERROR:
      return MIMIR_CHD_OPEN_IO_RETRYABLE;
    case CHDERR_REQUIRES_PARENT:
    case CHDERR_INVALID_PARENT:
      return MIMIR_CHD_OPEN_PARENT_REQUIRED;
    case CHDERR_NOT_SUPPORTED:
    case CHDERR_UNSUPPORTED_VERSION:
    case CHDERR_UNSUPPORTED_FORMAT:
      return MIMIR_CHD_OPEN_UNSUPPORTED;
    case CHDERR_OUT_OF_MEMORY:
      return MIMIR_CHD_OPEN_RESOURCE_REFUSED;
    default:
      return MIMIR_CHD_OPEN_MALFORMED;
  }
}

static jint mimir_chd_read_status(chd_error error) {
  switch (error) {
    case CHDERR_NONE:
      return MIMIR_CHD_READ_OK;
    case CHDERR_FILE_NOT_FOUND:
    case CHDERR_READ_ERROR:
    case CHDERR_OPERATION_PENDING:
      return MIMIR_CHD_READ_IO_RETRYABLE;
    case CHDERR_OUT_OF_MEMORY:
      return MIMIR_CHD_READ_RESOURCE_REFUSED;
    case CHDERR_NOT_SUPPORTED:
    case CHDERR_UNSUPPORTED_VERSION:
    case CHDERR_UNSUPPORTED_FORMAT:
      return MIMIR_CHD_READ_UNSUPPORTED;
    default:
      return MIMIR_CHD_READ_MALFORMED;
  }
}

static uint8_t mimir_chd_metadata_error_status(chd_error error) {
  switch (mimir_chd_read_status(error)) {
    case MIMIR_CHD_READ_IO_RETRYABLE:
      return MIMIR_CHD_METADATA_IO_RETRYABLE;
    case MIMIR_CHD_READ_RESOURCE_REFUSED:
      return 3U;
    case MIMIR_CHD_READ_UNSUPPORTED:
      return MIMIR_CHD_METADATA_UNSUPPORTED;
    default:
      return 4U;
  }
}

static jbyteArray mimir_chd_metadata_status(JNIEnv* env, uint8_t status) {
  jbyte value = (jbyte)status;
  jbyteArray result = (*env)->NewByteArray(env, 1);
  if (!result)
    return NULL;
  (*env)->SetByteArrayRegion(env, result, 0, 1, &value);
  return (*env)->ExceptionCheck(env) ? NULL : result;
}

static int mimir_sha1_nonzero(const uint8_t* value) {
  uint32_t index;
  uint8_t aggregate = 0U;
  for (index = 0U; index < CHD_SHA1_BYTES; ++index)
    aggregate |= value[index];
  return aggregate != 0U;
}

static void mimir_write_u32_be(uint8_t* out, uint32_t value) {
  out[0] = (uint8_t)(value >> 24);
  out[1] = (uint8_t)(value >> 16);
  out[2] = (uint8_t)(value >> 8);
  out[3] = (uint8_t)value;
}

static void mimir_write_u64_be(uint8_t* out, uint64_t value) {
  uint32_t index;
  for (index = 0U; index < 8U; ++index)
    out[index] = (uint8_t)(value >> (56U - index * 8U));
}

static int mimir_ra_generate_hash(
    char hash[33], uint32_t console_id, const char* path,
    mimir_chd_borrow_t* borrow) {
  rc_hash_iterator_t iterator;
  int generated;

  rc_hash_initialize_iterator(&iterator, path, NULL, 0U);
  if (borrow)
    mimir_install_cdreader_with_borrow(&iterator, borrow);
  else
    mimir_install_cdreader(&iterator);
  generated = rc_hash_generate(hash, console_id, &iterator);
  rc_hash_destroy_iterator(&iterator);
  return generated;
}

JNIEXPORT jstring JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_online_RetroAchievementsNativeHash_nativeHash(
    JNIEnv* env, jobject instance, jint console_id, jstring path) {
  const char* path_utf8;
  char hash[33];
  int generated;

  (void)instance;
  if (!path || console_id <= 0)
    return NULL;

  path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
  if (!path_utf8)
    return NULL;

  generated = mimir_ra_generate_hash(
      hash, (uint32_t)console_id, path_utf8, NULL);
  (*env)->ReleaseStringUTFChars(env, path, path_utf8);

  if (!generated)
    return NULL;
  hash[32] = '\0';
  return (*env)->NewStringUTF(env, hash);
}

JNIEXPORT jstring JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_online_RetroAchievementsNativeHash_nativeHashChain(
    JNIEnv* env, jobject instance, jint console_id, jstring path,
    jlong raw_handle) {
  mimir_identity_chd_chain_t* handle =
      (mimir_identity_chd_chain_t*)(intptr_t)raw_handle;
  mimir_chd_borrow_t borrow;
  const char* path_utf8;
  char hash[33];
  int generated;

  (void)instance;
  if (!path || console_id <= 0 || !handle ||
      handle->cookie != MIMIR_IDENTITY_CHAIN_COOKIE || !handle->top)
    return NULL;

  path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
  if (!path_utf8)
    return NULL;
  borrow.cookie = MIMIR_CHD_BORROW_COOKIE;
  borrow.target_path = path_utf8;
  borrow.top = handle->top;
  generated = mimir_ra_generate_hash(
      hash, (uint32_t)console_id, path_utf8, &borrow);
  borrow.cookie = 0U;
  (*env)->ReleaseStringUTFChars(env, path, path_utf8);

  if (!generated)
    return NULL;
  hash[32] = '\0';
  return (*env)->NewStringUTF(env, hash);
}

JNIEXPORT jlongArray JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdNativeBridge_nativeOpen(
    JNIEnv* env, jobject instance, jstring path) {
  const chd_header* header;
  mimir_identity_chd_t* handle;
  const char* path_utf8;
  chd_file* chd = NULL;
  uint32_t hunk_count;
  chd_error error;
  jlongArray result;

  (void)instance;
  if (!path)
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_MALFORMED, 0);
  path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
  if (!path_utf8)
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_RESOURCE_REFUSED, 0);
  error = chd_open(path_utf8, CHD_OPEN_READ, NULL, &chd);
  (*env)->ReleaseStringUTFChars(env, path, path_utf8);
  if (error != CHDERR_NONE || !chd)
    return mimir_chd_open_result(env, mimir_chd_open_status(error), 0);

  header = chd_get_header(chd);
  if (!header || header->version < 3U || header->version > 5U ||
      header->logicalbytes == 0U || header->hunkbytes == 0U ||
      (header->version >= 4U && !mimir_sha1_nonzero(header->rawsha1)) ||
      !mimir_sha1_nonzero(header->sha1)) {
    chd_close(chd);
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_MALFORMED, 0);
  }
  if (header->logicalbytes > MIMIR_IDENTITY_MAX_LOGICAL_BYTES ||
      header->hunkbytes > MIMIR_IDENTITY_MAX_HUNK_BYTES) {
    chd_close(chd);
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_RESOURCE_REFUSED, 0);
  }
  hunk_count = header->hunkcount ? header->hunkcount : header->totalhunks;
  if (hunk_count == 0U) {
    chd_close(chd);
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_MALFORMED, 0);
  }
  if (hunk_count > MIMIR_IDENTITY_MAX_HUNKS) {
    chd_close(chd);
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_RESOURCE_REFUSED, 0);
  }
  handle = (mimir_identity_chd_t*)calloc(1U, sizeof(*handle));
  if (!handle) {
    chd_close(chd);
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_RESOURCE_REFUSED, 0);
  }
  handle->chd = chd;
  handle->hunk_bytes = header->hunkbytes;
  handle->hunk_count = hunk_count;
  handle->hunk_buffer = (uint8_t*)malloc(handle->hunk_bytes);
  if (!handle->hunk_buffer) {
    chd_close(chd);
    free(handle);
    return mimir_chd_open_result(env, MIMIR_CHD_OPEN_RESOURCE_REFUSED, 0);
  }
  result = mimir_chd_open_result(
      env, MIMIR_CHD_OPEN_OK, (jlong)(intptr_t)handle);
  if (!result) {
    chd_close(handle->chd);
    free(handle->hunk_buffer);
    free(handle);
  }
  return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdNativeBridge_nativeInfo(
    JNIEnv* env, jobject instance, jlong raw_handle) {
  const chd_header* header;
  mimir_identity_chd_t* handle = (mimir_identity_chd_t*)(intptr_t)raw_handle;
  uint8_t info[60];
  jbyteArray result;

  (void)instance;
  if (!handle || !handle->chd)
    return NULL;
  header = chd_get_header(handle->chd);
  if (!header)
    return NULL;
  memset(info, 0, sizeof(info));
  mimir_write_u32_be(&info[0], header->version);
  mimir_write_u64_be(&info[4], header->logicalbytes);
  mimir_write_u32_be(&info[12], handle->hunk_bytes);
  mimir_write_u32_be(&info[16], handle->hunk_count);
  memcpy(&info[20],
         header->version == 3U ? header->sha1 : header->rawsha1,
         CHD_SHA1_BYTES);
  memcpy(&info[40], header->sha1, CHD_SHA1_BYTES);

  result = (*env)->NewByteArray(env, (jsize)sizeof(info));
  if (!result)
    return NULL;
  (*env)->SetByteArrayRegion(env, result, 0, (jsize)sizeof(info), (const jbyte*)info);
  return result;
}

JNIEXPORT jint JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdNativeBridge_nativeReadHunk(
    JNIEnv* env, jobject instance, jlong raw_handle, jint hunk_index,
    jbyteArray destination) {
  mimir_identity_chd_t* handle = (mimir_identity_chd_t*)(intptr_t)raw_handle;
  chd_error error;

  (void)instance;
  if (!handle || !handle->chd || !destination || hunk_index < 0 ||
      (uint32_t)hunk_index >= handle->hunk_count ||
      (*env)->GetArrayLength(env, destination) < (jsize)handle->hunk_bytes)
    return MIMIR_CHD_READ_MALFORMED;

  error = chd_read(handle->chd, (uint32_t)hunk_index, handle->hunk_buffer);
  if (error != CHDERR_NONE)
    return mimir_chd_read_status(error);
  (*env)->SetByteArrayRegion(env, destination, 0, (jsize)handle->hunk_bytes,
                            (const jbyte*)handle->hunk_buffer);
  return (*env)->ExceptionCheck(env)
      ? MIMIR_CHD_READ_RESOURCE_REFUSED
      : MIMIR_CHD_READ_OK;
}

JNIEXPORT jbyteArray JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdNativeBridge_nativeMetadata(
    JNIEnv* env, jobject instance, jlong raw_handle, jint metadata_index,
    jint max_bytes) {
  mimir_identity_chd_t* handle = (mimir_identity_chd_t*)(intptr_t)raw_handle;
  uint8_t dummy = 0U;
  uint8_t flags = 0U;
  uint32_t length = 0U;
  uint32_t tag = 0U;
  uint8_t* data = NULL;
  uint8_t header[9];
  jbyteArray result;
  chd_error error;

  (void)instance;
  if (!handle || !handle->chd || metadata_index < 0 || max_bytes < 0)
    return mimir_chd_metadata_status(env, 4U);

  error = chd_get_metadata(handle->chd, CHDMETATAG_WILDCARD,
                           (uint32_t)metadata_index, &dummy, 0U, &length, &tag,
                           &flags);
  if (error == CHDERR_METADATA_NOT_FOUND)
    return mimir_chd_metadata_status(env, 0U);
  if (error != CHDERR_NONE)
    return mimir_chd_metadata_status(env, mimir_chd_metadata_error_status(error));
  if ((flags & CHD_MDFLAGS_CHECKSUM) == 0U)
    return mimir_chd_metadata_status(env, 1U);
  if (length > (uint32_t)max_bytes)
    return mimir_chd_metadata_status(env, 3U);

  data = (uint8_t*)malloc(length == 0U ? 1U : length);
  if (!data)
    return mimir_chd_metadata_status(env, 3U);
  {
    uint32_t confirmed_length = 0U;
    uint32_t confirmed_tag = 0U;
    uint8_t confirmed_flags = 0U;
    error = chd_get_metadata(handle->chd, CHDMETATAG_WILDCARD,
                             (uint32_t)metadata_index, data, length,
                             &confirmed_length, &confirmed_tag,
                             &confirmed_flags);
    if (error != CHDERR_NONE) {
      free(data);
      return mimir_chd_metadata_status(
          env, mimir_chd_metadata_error_status(error));
    }
    if (confirmed_length != length || confirmed_tag != tag ||
        (confirmed_flags & CHD_MDFLAGS_CHECKSUM) == 0U) {
      free(data);
      return mimir_chd_metadata_status(env, 4U);
    }
  }

  header[0] = 2U;
  mimir_write_u32_be(&header[1], tag);
  mimir_write_u32_be(&header[5], length);
  result = (*env)->NewByteArray(env, (jsize)(sizeof(header) + length));
  if (!result) {
    free(data);
    return NULL;
  }
  (*env)->SetByteArrayRegion(
      env, result, 0, (jsize)sizeof(header), (const jbyte*)header);
  if (length > 0U && !(*env)->ExceptionCheck(env))
    (*env)->SetByteArrayRegion(
        env, result, (jsize)sizeof(header), (jsize)length,
        (const jbyte*)data);
  free(data);
  return (*env)->ExceptionCheck(env) ? NULL : result;
}

JNIEXPORT void JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdNativeBridge_nativeClose(
    JNIEnv* env, jobject instance, jlong raw_handle) {
  mimir_identity_chd_t* handle = (mimir_identity_chd_t*)(intptr_t)raw_handle;
  (void)env;
  (void)instance;
  if (!handle)
    return;
  if (handle->chd)
    chd_close(handle->chd);
  free(handle->hunk_buffer);
  free(handle);
}

static jlongArray mimir_chd_chain_open_result(
    JNIEnv* env, jlong status, jlong handle, jlong failing_node_index) {
  jlong values[3];
  jlongArray result = (*env)->NewLongArray(env, 3);
  if (!result)
    return NULL;
  values[0] = status;
  values[1] = handle;
  values[2] = failing_node_index;
  (*env)->SetLongArrayRegion(env, result, 0, 3, values);
  return (*env)->ExceptionCheck(env) ? NULL : result;
}

static int mimir_chd_identity_header_valid(
    const chd_header* header, uint32_t* hunk_count) {
  uint32_t count;
  if (!header || !hunk_count || header->version < 3U || header->version > 5U ||
      header->logicalbytes == 0U || header->hunkbytes == 0U ||
      (header->version >= 4U && !mimir_sha1_nonzero(header->rawsha1)) ||
      !mimir_sha1_nonzero(header->sha1))
    return 0;
  if (header->logicalbytes > MIMIR_IDENTITY_MAX_LOGICAL_BYTES ||
      header->hunkbytes > MIMIR_IDENTITY_MAX_HUNK_BYTES)
    return 0;
  count = header->hunkcount ? header->hunkcount : header->totalhunks;
  if (count == 0U || count > MIMIR_IDENTITY_MAX_HUNKS)
    return 0;
  *hunk_count = count;
  return 1;
}

JNIEXPORT jlongArray JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdChainNativeBridge_nativeOpenChain(
    JNIEnv* env, jobject instance, jobjectArray paths) {
  mimir_identity_chd_chain_t* handle = NULL;
  chd_file* nodes[MIMIR_IDENTITY_MAX_CHAIN_DEPTH];
  chd_file* top = NULL;
  uint32_t max_hunk_bytes = 0U;
  jsize count;
  jsize index;
  jlong status = MIMIR_CHD_OPEN_MALFORMED;

  (void)instance;
  memset(nodes, 0, sizeof(nodes));
  if (!paths)
    return mimir_chd_chain_open_result(env, status, 0, -1);
  count = (*env)->GetArrayLength(env, paths);
  if (count < 1 || count > (jsize)MIMIR_IDENTITY_MAX_CHAIN_DEPTH)
    return mimir_chd_chain_open_result(
        env, MIMIR_CHD_OPEN_RESOURCE_REFUSED, 0, -1);

  for (index = 0; index < count; ++index) {
    const chd_header* header;
    const char* path_utf8;
    chd_file* opened = NULL;
    uint32_t hunk_count = 0U;
    chd_error error;
    jstring path = (jstring)(*env)->GetObjectArrayElement(env, paths, index);
    if (!path || (*env)->ExceptionCheck(env)) {
      if (path)
        (*env)->DeleteLocalRef(env, path);
      status = MIMIR_CHD_OPEN_MALFORMED;
      goto failure;
    }
    path_utf8 = (*env)->GetStringUTFChars(env, path, NULL);
    if (!path_utf8) {
      (*env)->DeleteLocalRef(env, path);
      status = MIMIR_CHD_OPEN_RESOURCE_REFUSED;
      goto failure;
    }
    error = chd_open(path_utf8, CHD_OPEN_READ, top, &opened);
    (*env)->ReleaseStringUTFChars(env, path, path_utf8);
    (*env)->DeleteLocalRef(env, path);
    if (error != CHDERR_NONE || !opened) {
      status = error == CHDERR_INVALID_PARENT
          ? MIMIR_CHD_OPEN_MALFORMED
          : mimir_chd_open_status(error);
      goto failure;
    }
    top = opened;
    nodes[index] = opened;
    header = chd_get_header(opened);
    if (!mimir_chd_identity_header_valid(header, &hunk_count)) {
      status = MIMIR_CHD_OPEN_MALFORMED;
      goto failure;
    }
    if (header->hunkbytes > max_hunk_bytes)
      max_hunk_bytes = header->hunkbytes;
  }

  handle = (mimir_identity_chd_chain_t*)calloc(1U, sizeof(*handle));
  if (!handle) {
    status = MIMIR_CHD_OPEN_RESOURCE_REFUSED;
    goto failure;
  }
  handle->hunk_buffer = (uint8_t*)malloc(max_hunk_bytes);
  if (!handle->hunk_buffer) {
    status = MIMIR_CHD_OPEN_RESOURCE_REFUSED;
    goto failure;
  }
  handle->cookie = MIMIR_IDENTITY_CHAIN_COOKIE;
  handle->top = top;
  handle->node_count = (uint32_t)count;
  handle->hunk_capacity = max_hunk_bytes;
  memcpy(handle->nodes, nodes, (size_t)count * sizeof(nodes[0]));
  {
    jlongArray result = mimir_chd_chain_open_result(
        env, MIMIR_CHD_OPEN_OK, (jlong)(intptr_t)handle, -1);
    if (!result)
      goto failure;
    return result;
  }

failure:
  if (handle) {
    free(handle->hunk_buffer);
    free(handle);
  }
  if (top)
    chd_close(top);
  return mimir_chd_chain_open_result(env, status, 0, (jlong)index);
}

JNIEXPORT jbyteArray JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdChainNativeBridge_nativeChainInfo(
    JNIEnv* env, jobject instance, jlong raw_handle, jint node_index) {
  mimir_identity_chd_chain_t* handle =
      (mimir_identity_chd_chain_t*)(intptr_t)raw_handle;
  const chd_header* header;
  uint32_t hunk_count;
  uint32_t compression_index;
  uint8_t info[96];
  jbyteArray result;

  (void)instance;
  if (!handle || handle->cookie != MIMIR_IDENTITY_CHAIN_COOKIE ||
      node_index < 0 || (uint32_t)node_index >= handle->node_count)
    return NULL;
  header = chd_get_header(handle->nodes[node_index]);
  if (!mimir_chd_identity_header_valid(header, &hunk_count))
    return NULL;
  memset(info, 0, sizeof(info));
  mimir_write_u32_be(&info[0], header->version);
  mimir_write_u64_be(&info[4], header->logicalbytes);
  mimir_write_u32_be(&info[12], header->hunkbytes);
  mimir_write_u32_be(&info[16], hunk_count);
  memcpy(&info[20],
         header->version == 3U ? header->sha1 : header->rawsha1,
         CHD_SHA1_BYTES);
  memcpy(&info[40], header->sha1, CHD_SHA1_BYTES);
  memcpy(&info[60], header->parentsha1, CHD_SHA1_BYTES);
  for (compression_index = 0U; compression_index < 4U; ++compression_index)
    mimir_write_u32_be(
        &info[80 + compression_index * 4U],
        header->compression[compression_index]);

  result = (*env)->NewByteArray(env, (jsize)sizeof(info));
  if (!result)
    return NULL;
  (*env)->SetByteArrayRegion(
      env, result, 0, (jsize)sizeof(info), (const jbyte*)info);
  return (*env)->ExceptionCheck(env) ? NULL : result;
}

JNIEXPORT jint JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdChainNativeBridge_nativeChainReadHunk(
    JNIEnv* env, jobject instance, jlong raw_handle, jint node_index,
    jint hunk_index, jbyteArray destination) {
  mimir_identity_chd_chain_t* handle =
      (mimir_identity_chd_chain_t*)(intptr_t)raw_handle;
  const chd_header* header;
  uint32_t hunk_count;
  chd_error error;

  (void)instance;
  if (!handle || handle->cookie != MIMIR_IDENTITY_CHAIN_COOKIE ||
      node_index < 0 || (uint32_t)node_index >= handle->node_count ||
      !destination || hunk_index < 0)
    return MIMIR_CHD_READ_MALFORMED;
  header = chd_get_header(handle->nodes[node_index]);
  if (!mimir_chd_identity_header_valid(header, &hunk_count) ||
      (uint32_t)hunk_index >= hunk_count ||
      header->hunkbytes > handle->hunk_capacity ||
      (*env)->GetArrayLength(env, destination) < (jsize)header->hunkbytes)
    return MIMIR_CHD_READ_MALFORMED;
  error = chd_read(
      handle->nodes[node_index], (uint32_t)hunk_index, handle->hunk_buffer);
  if (error != CHDERR_NONE)
    return mimir_chd_read_status(error);
  (*env)->SetByteArrayRegion(
      env, destination, 0, (jsize)header->hunkbytes,
      (const jbyte*)handle->hunk_buffer);
  return (*env)->ExceptionCheck(env)
      ? MIMIR_CHD_READ_RESOURCE_REFUSED
      : MIMIR_CHD_READ_OK;
}

JNIEXPORT jbyteArray JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdChainNativeBridge_nativeChainMetadata(
    JNIEnv* env, jobject instance, jlong raw_handle, jint node_index,
    jint metadata_index, jint max_bytes) {
  mimir_identity_chd_chain_t* handle =
      (mimir_identity_chd_chain_t*)(intptr_t)raw_handle;
  chd_file* chd;
  uint8_t dummy = 0U;
  uint8_t flags = 0U;
  uint32_t length = 0U;
  uint32_t tag = 0U;
  uint8_t* data = NULL;
  uint8_t header[9];
  jbyteArray result;
  chd_error error;

  (void)instance;
  if (!handle || handle->cookie != MIMIR_IDENTITY_CHAIN_COOKIE ||
      node_index < 0 || (uint32_t)node_index >= handle->node_count ||
      metadata_index < 0 || max_bytes < 0)
    return mimir_chd_metadata_status(env, 4U);
  chd = handle->nodes[node_index];
  error = chd_get_metadata(
      chd, CHDMETATAG_WILDCARD, (uint32_t)metadata_index, &dummy, 0U,
      &length, &tag, &flags);
  if (error == CHDERR_METADATA_NOT_FOUND)
    return mimir_chd_metadata_status(env, 0U);
  if (error != CHDERR_NONE)
    return mimir_chd_metadata_status(
        env, mimir_chd_metadata_error_status(error));
  if ((flags & CHD_MDFLAGS_CHECKSUM) == 0U)
    return mimir_chd_metadata_status(env, 1U);
  if (length > (uint32_t)max_bytes)
    return mimir_chd_metadata_status(env, 3U);

  data = (uint8_t*)malloc(length == 0U ? 1U : length);
  if (!data)
    return mimir_chd_metadata_status(env, 3U);
  {
    uint32_t confirmed_length = 0U;
    uint32_t confirmed_tag = 0U;
    uint8_t confirmed_flags = 0U;
    error = chd_get_metadata(
        chd, CHDMETATAG_WILDCARD, (uint32_t)metadata_index, data, length,
        &confirmed_length, &confirmed_tag, &confirmed_flags);
    if (error != CHDERR_NONE) {
      free(data);
      return mimir_chd_metadata_status(
          env, mimir_chd_metadata_error_status(error));
    }
    if (confirmed_length != length || confirmed_tag != tag ||
        (confirmed_flags & CHD_MDFLAGS_CHECKSUM) == 0U) {
      free(data);
      return mimir_chd_metadata_status(env, 4U);
    }
  }

  header[0] = 2U;
  mimir_write_u32_be(&header[1], tag);
  mimir_write_u32_be(&header[5], length);
  result = (*env)->NewByteArray(env, (jsize)(sizeof(header) + length));
  if (!result) {
    free(data);
    return NULL;
  }
  (*env)->SetByteArrayRegion(
      env, result, 0, (jsize)sizeof(header), (const jbyte*)header);
  if (length > 0U && !(*env)->ExceptionCheck(env))
    (*env)->SetByteArrayRegion(
        env, result, (jsize)sizeof(header), (jsize)length,
        (const jbyte*)data);
  free(data);
  return (*env)->ExceptionCheck(env) ? NULL : result;
}

JNIEXPORT void JNICALL
Java_com_gruning_mimir_rewrite_core_metadata_ChdChainNativeBridge_nativeChainClose(
    JNIEnv* env, jobject instance, jlong raw_handle) {
  mimir_identity_chd_chain_t* handle =
      (mimir_identity_chd_chain_t*)(intptr_t)raw_handle;
  (void)env;
  (void)instance;
  if (!handle || handle->cookie != MIMIR_IDENTITY_CHAIN_COOKIE)
    return;
  handle->cookie = 0U;
  if (handle->top)
    chd_close(handle->top);
  free(handle->hunk_buffer);
  free(handle);
}
