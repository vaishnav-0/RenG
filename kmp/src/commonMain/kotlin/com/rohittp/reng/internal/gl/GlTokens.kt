package com.rohittp.reng.internal.gl

internal const val GL_NO_ERROR: Int = 0x0000
internal const val GL_INVALID_ENUM: Int = 0x0500
internal const val GL_INVALID_VALUE: Int = 0x0501
internal const val GL_INVALID_OPERATION: Int = 0x0502
internal const val GL_OUT_OF_MEMORY: Int = 0x0505
internal const val GL_INVALID_FRAMEBUFFER_OPERATION: Int = 0x0506

internal const val GL_VENDOR: Int = 0x1F00
internal const val GL_RENDERER: Int = 0x1F01
internal const val GL_VERSION: Int = 0x1F02
internal const val GL_EXTENSIONS: Int = 0x1F03
internal const val GL_SHADING_LANGUAGE_VERSION: Int = 0x8B8C
internal const val GL_NUM_EXTENSIONS: Int = 0x821D

internal const val GL_FRAMEBUFFER: Int = 0x8D40
internal const val GL_DRAW_FRAMEBUFFER: Int = 0x8CA9
internal const val GL_READ_FRAMEBUFFER: Int = 0x8CA8
internal const val GL_RENDERBUFFER: Int = 0x8D41
internal const val GL_DRAW_FRAMEBUFFER_BINDING: Int = 0x8CA6
internal const val GL_READ_FRAMEBUFFER_BINDING: Int = 0x8CAA
internal const val GL_RENDERBUFFER_BINDING: Int = 0x8CA7
internal const val GL_FRAMEBUFFER_COMPLETE: Int = 0x8CD5
internal const val GL_FRAMEBUFFER_UNDEFINED: Int = 0x8219
internal const val GL_COLOR_ATTACHMENT0: Int = 0x8CE0
internal const val GL_DEPTH_ATTACHMENT: Int = 0x8D00
internal const val GL_MAX_COLOR_ATTACHMENTS: Int = 0x8CDF

internal const val GL_TEXTURE_2D: Int = 0x0DE1
internal const val GL_TEXTURE0: Int = 0x84C0
internal const val GL_TEXTURE_BINDING_2D: Int = 0x8069
internal const val GL_ACTIVE_TEXTURE: Int = 0x84E0
internal const val GL_SAMPLER_BINDING: Int = 0x8919
internal const val GL_MAX_TEXTURE_SIZE: Int = 0x0D33
internal const val GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS: Int = 0x8B4D
internal const val GL_TEXTURE_MIN_FILTER: Int = 0x2801
internal const val GL_TEXTURE_MAG_FILTER: Int = 0x2800
internal const val GL_TEXTURE_WRAP_S: Int = 0x2802
internal const val GL_TEXTURE_WRAP_T: Int = 0x2803
internal const val GL_NEAREST: Int = 0x2600
internal const val GL_LINEAR: Int = 0x2601
internal const val GL_CLAMP_TO_EDGE: Int = 0x812F

internal const val GL_RGBA: Int = 0x1908
internal const val GL_RGBA8: Int = 0x8058
internal const val GL_DEPTH_COMPONENT24: Int = 0x81A6
internal const val GL_UNSIGNED_BYTE: Int = 0x1401
internal const val GL_UNSIGNED_SHORT: Int = 0x1403
internal const val GL_FLOAT: Int = 0x1406
internal const val GL_UNSIGNED_INT: Int = 0x1405

internal const val GL_REPEAT: Int = 0x2901
internal const val GL_MIRRORED_REPEAT: Int = 0x8370
internal const val GL_NEAREST_MIPMAP_NEAREST: Int = 0x2700
internal const val GL_LINEAR_MIPMAP_NEAREST: Int = 0x2701
internal const val GL_NEAREST_MIPMAP_LINEAR: Int = 0x2702
internal const val GL_LINEAR_MIPMAP_LINEAR: Int = 0x2703

internal const val GL_ARRAY_BUFFER: Int = 0x8892
internal const val GL_ELEMENT_ARRAY_BUFFER: Int = 0x8893
internal const val GL_PIXEL_UNPACK_BUFFER: Int = 0x88EC
internal const val GL_UNIFORM_BUFFER: Int = 0x8A11
internal const val GL_ARRAY_BUFFER_BINDING: Int = 0x8894
internal const val GL_ELEMENT_ARRAY_BUFFER_BINDING: Int = 0x8895
internal const val GL_PIXEL_UNPACK_BUFFER_BINDING: Int = 0x88EF
internal const val GL_UNIFORM_BUFFER_BINDING: Int = 0x8A28
internal const val GL_VERTEX_ARRAY_BINDING: Int = 0x85B5
internal const val GL_STATIC_DRAW: Int = 0x88E4
internal const val GL_DYNAMIC_DRAW: Int = 0x88E8
internal const val GL_MAX_UNIFORM_BLOCK_SIZE: Int = 0x8A30
internal const val GL_MAX_VERTEX_UNIFORM_BLOCKS: Int = 0x8A2B

internal const val GL_VERTEX_SHADER: Int = 0x8B31
internal const val GL_FRAGMENT_SHADER: Int = 0x8B30
internal const val GL_COMPILE_STATUS: Int = 0x8B81
internal const val GL_LINK_STATUS: Int = 0x8B82
internal const val GL_INFO_LOG_LENGTH: Int = 0x8B84
internal const val GL_CURRENT_PROGRAM: Int = 0x8B8D

internal const val GL_BLEND: Int = 0x0BE2
internal const val GL_BLEND_SRC_RGB: Int = 0x80C9
internal const val GL_BLEND_DST_RGB: Int = 0x80C8
internal const val GL_BLEND_SRC_ALPHA: Int = 0x80CB
internal const val GL_BLEND_DST_ALPHA: Int = 0x80CA
internal const val GL_BLEND_EQUATION_RGB: Int = 0x8009
internal const val GL_BLEND_EQUATION_ALPHA: Int = 0x883D
internal const val GL_BLEND_COLOR: Int = 0x8005
internal const val GL_ZERO: Int = 0x0000
internal const val GL_ONE: Int = 0x0001
internal const val GL_SRC_ALPHA: Int = 0x0302
internal const val GL_ONE_MINUS_SRC_ALPHA: Int = 0x0303
internal const val GL_FUNC_ADD: Int = 0x8006

internal const val GL_DEPTH_TEST: Int = 0x0B71
internal const val GL_DEPTH_FUNC: Int = 0x0B74
internal const val GL_DEPTH_WRITEMASK: Int = 0x0B72
internal const val GL_DEPTH_RANGE: Int = 0x0B70
internal const val GL_DEPTH_CLEAR_VALUE: Int = 0x0B73
internal const val GL_LESS: Int = 0x0201
internal const val GL_GREATER: Int = 0x0204
internal const val GL_GEQUAL: Int = 0x0206

internal const val GL_CULL_FACE: Int = 0x0B44
internal const val GL_CULL_FACE_MODE: Int = 0x0B45
internal const val GL_FRONT_FACE: Int = 0x0B46
internal const val GL_FRONT: Int = 0x0404
internal const val GL_BACK: Int = 0x0405
internal const val GL_CW: Int = 0x0900
internal const val GL_CCW: Int = 0x0901

internal const val GL_VIEWPORT: Int = 0x0BA2
internal const val GL_SCISSOR_TEST: Int = 0x0C11
internal const val GL_SCISSOR_BOX: Int = 0x0C10
internal const val GL_COLOR_WRITEMASK: Int = 0x0C23
internal const val GL_COLOR_CLEAR_VALUE: Int = 0x0C22
internal const val GL_COLOR_BUFFER_BIT: Int = 0x4000
internal const val GL_DEPTH_BUFFER_BIT: Int = 0x0100

internal const val GL_UNPACK_ALIGNMENT: Int = 0x0CF5
internal const val GL_UNPACK_ROW_LENGTH: Int = 0x0CF2
internal const val GL_UNPACK_SKIP_ROWS: Int = 0x0CF3
internal const val GL_UNPACK_SKIP_PIXELS: Int = 0x0CF4
internal const val GL_PACK_ALIGNMENT: Int = 0x0D05

internal const val GL_FRAMEBUFFER_SRGB: Int = 0x8DB9
internal const val GL_DRAW_BUFFER: Int = 0x0C01
internal const val GL_LINE_SMOOTH: Int = 0x0B20

internal const val GL_TRIANGLES: Int = 0x0004
internal const val GL_TRIANGLE_STRIP: Int = 0x0005
internal const val GL_NONE: Int = 0x0000

internal const val GL_UNPACK_ALIGNMENT_DEFAULT: Int = 4
internal const val GL_PACK_ALIGNMENT_DEFAULT: Int = 4
