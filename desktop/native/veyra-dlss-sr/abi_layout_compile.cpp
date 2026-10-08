#include "bilipai_veyra_dlss_sr_v2.h"
#include <cstddef>
#include <type_traits>
static_assert(sizeof(void*)==8);
static_assert(sizeof(bvd_config_v2)==112);
static_assert(sizeof(bvd_frame_v2)==168);
static_assert(sizeof(bvd_result_v2)==120);
static_assert(sizeof(bvd_status_v2)==288);
static_assert(offsetof(bvd_config_v2,d3d12_device)==40);
static_assert(offsetof(bvd_config_v2,input_width)==80);
static_assert(offsetof(bvd_frame_v2,color_texture)==72);
static_assert(offsetof(bvd_frame_v2,input_ready_value)==112);
static_assert(offsetof(bvd_frame_v2,jitter_offset_x)==140);
static_assert(offsetof(bvd_result_v2,output_texture)==88);
static_assert(offsetof(bvd_result_v2,completion_value)==104);
static_assert(std::is_standard_layout_v<bvd_config_v2>);
static_assert(std::is_standard_layout_v<bvd_frame_v2>);
static_assert(std::is_standard_layout_v<bvd_result_v2>);
