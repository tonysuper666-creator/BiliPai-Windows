#include "bilipai_veyra_core_v1.h"
#include <stddef.h>
static_assert(sizeof(void*) == 8, "Windows x64 ABI required");
static_assert(sizeof(bv_handle_v1) == 8);
static_assert(offsetof(bv_config_v1, session_id) == 8);
static_assert(offsetof(bv_frame_v1, session_id) == 8);
static_assert(offsetof(bv_result_v1, session_id) == 8);
static_assert(sizeof(bv_config_v1) == 120);
static_assert(sizeof(bv_frame_v1) == 112);
static_assert(sizeof(bv_result_v1) == 104);

static_assert(sizeof(bv_status_v1) == 288);
