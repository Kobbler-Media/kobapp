/*****************************************************************************
 * kobvaapi.c: VA-API decoding over DRM for libavcodec, without a VA-API vout
 *****************************************************************************
 * Copyright (C) 2017 VLC authors and VideoLAN
 * Copyright (C) 2009-2010 Laurent Aimar
 * Copyright (C) 2012-2014 Rémi Denis-Courmont
 *
 * Authors: Laurent Aimar <fenrir_AT_ videolan _DOT_ org>
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation; either version 2.1 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

/*
 * The DRM backend of VLC 3.0.x modules/codec/avcodec/vaapi.c as of 84177b2273 on the
 * 3.0.x branch, which carries a71099385e and 20fc39fc97. Every released 3.0 up to at
 * least 3.0.23 ships it broken (it never opens), so a player decoding into memory
 * callbacks gets no VA-API at all. Trimmed to the DRM path, renamed so it cannot collide
 * with the system plugin, plus a device option to pick the GPU. Delete once the VLC that
 * distributions ship includes those commits.
 */

#include <assert.h>

#include <vlc_common.h>
#include <vlc_plugin.h>
#include <vlc_fourcc.h>
#include <vlc_picture.h>
#include <vlc_picture_pool.h>

#include <va/va_drm.h>
#include <libavcodec/avcodec.h>
#include <libavutil/hwcontext_vaapi.h>
#include <libavutil/pixdesc.h>

#include "vlc_vaapi.h"

/* ABI shared with the system libavcodec_plugin: modules/codec/avcodec/va.h in 3.0.x. */
typedef struct vlc_va_sys_t vlc_va_sys_t;
typedef struct vlc_va_t vlc_va_t;
struct vlc_va_t {
    VLC_COMMON_MEMBERS

    vlc_va_sys_t *sys;
    module_t *module;
    const char *description;

    int  (*get)(vlc_va_t *, picture_t *pic, uint8_t **data);
};

struct vlc_va_sys_t
{
    struct vlc_vaapi_instance *va_inst;
    VADisplay display;
    picture_pool_t *pool;
};

static int GetVaProfile(AVCodecContext *ctx, VAProfile *va_profile,
                        int *vlc_chroma, unsigned *pic_count)
{
    VAProfile i_profile;
    unsigned count = 3;
    int i_vlc_chroma = VLC_CODEC_VAAPI_420;

    switch(ctx->codec_id)
    {
    case AV_CODEC_ID_MPEG1VIDEO:
    case AV_CODEC_ID_MPEG2VIDEO:
        i_profile = VAProfileMPEG2Main;
        count = 4;
        break;
    case AV_CODEC_ID_MPEG4:
        i_profile = VAProfileMPEG4AdvancedSimple;
        break;
    case AV_CODEC_ID_WMV3:
        i_profile = VAProfileVC1Main;
        break;
    case AV_CODEC_ID_VC1:
        i_profile = VAProfileVC1Advanced;
        break;
    case AV_CODEC_ID_H264:
        i_profile = VAProfileH264High;
        count = 18;
        break;
    case AV_CODEC_ID_HEVC:
        if (ctx->profile == AV_PROFILE_HEVC_MAIN)
            i_profile = VAProfileHEVCMain;
        else if (ctx->profile == AV_PROFILE_HEVC_MAIN_10)
        {
            i_profile = VAProfileHEVCMain10;
            i_vlc_chroma = VLC_CODEC_VAAPI_420_10BPP;
        }
        else
            return VLC_EGENERIC;
        count = 18;
        break;
    case AV_CODEC_ID_VP8:
        i_profile = VAProfileVP8Version0_3;
        count = 5;
        break;
    case AV_CODEC_ID_VP9:
        if (ctx->profile == AV_PROFILE_VP9_0)
            i_profile = VAProfileVP9Profile0;
        else if (ctx->profile == AV_PROFILE_VP9_2)
        {
            i_profile = VAProfileVP9Profile2;
            i_vlc_chroma = VLC_CODEC_VAAPI_420_10BPP;
        }
        else
            return VLC_EGENERIC;
        count = 10;
        break;
    default:
        return VLC_EGENERIC;
    }

    *va_profile = i_profile;
    *pic_count = count + ctx->thread_count;
    *vlc_chroma = i_vlc_chroma;
    return VLC_SUCCESS;
}

static int Get(vlc_va_t *va, picture_t *pic, uint8_t **data)
{
    vlc_va_sys_t *sys = va->sys;

    picture_t *vapic = picture_pool_Wait(sys->pool);
    if (vapic == NULL)
        return VLC_EGENERIC;
    vlc_vaapi_PicAttachContext(vapic);

    pic->context = vapic->context->copy(vapic->context);
    picture_Release(vapic);
    if (pic->context == NULL)
        return VLC_EGENERIC;

    *data = (void *)(uintptr_t)vlc_vaapi_PicGetSurface(pic);
    return VLC_SUCCESS;
}

static void Delete(vlc_va_t *va, void **hwctx)
{
    vlc_va_sys_t *sys = va->sys;

    (void) hwctx;
    picture_pool_Release(sys->pool);
    vlc_vaapi_ReleaseInstance(sys->va_inst);
    free(sys);
}

static int Create(vlc_va_t *va, AVCodecContext *ctx,
                  const AVPixFmtDescriptor *desc, enum AVPixelFormat pix_fmt,
                  const es_format_t *fmt, picture_sys_t *p_sys)
{
    VLC_UNUSED(desc);
    VLC_UNUSED(fmt);
    if (pix_fmt != AV_PIX_FMT_VAAPI || p_sys)
        return VLC_EGENERIC;

    vlc_object_t *o = VLC_OBJECT(va);

    VAProfile i_profile;
    unsigned count;
    int i_vlc_chroma;
    if (GetVaProfile(ctx, &i_profile, &i_vlc_chroma, &count) != VLC_SUCCESS)
        return VLC_EGENERIC;

    vlc_va_sys_t *sys = calloc(1, sizeof (*sys));
    if (!sys)
       return VLC_ENOMEM;

    char *device = var_InheritString(va, "kobvaapi-device");
    sys->va_inst = vlc_vaapi_InitializeInstanceDRM(o, vaGetDisplayDRM,
                                                   &sys->display, device);
    free(device);
    if (!sys->va_inst)
        goto error;

    assert(ctx->coded_width > 0 && ctx->coded_height > 0);
    video_format_t vfmt = {
        .i_chroma = i_vlc_chroma,
        .i_width = ctx->coded_width,
        .i_height = ctx->coded_height,
        .i_visible_width = ctx->coded_width,
        .i_visible_height = ctx->coded_height
    };

    VASurfaceID *surfaces;
    sys->pool = vlc_vaapi_PoolNew(o, sys->va_inst, sys->display, count,
                                  &surfaces, &vfmt, false);
    if (!sys->pool)
        goto error;

    /* libavcodec builds its own VA config and context; it only needs the display. */
    AVBufferRef *hwdev_ref = av_hwdevice_ctx_alloc(AV_HWDEVICE_TYPE_VAAPI);
    if (hwdev_ref == NULL)
        goto error;

    AVHWDeviceContext *hwdev_ctx = (void *) hwdev_ref->data;
    AVVAAPIDeviceContext *vadev_ctx = hwdev_ctx->hwctx;
    vadev_ctx->display = sys->display;

    if (av_hwdevice_ctx_init(hwdev_ref) < 0)
    {
        av_buffer_unref(&hwdev_ref);
        goto error;
    }

    ctx->hw_device_ctx = hwdev_ref;
    va->sys = sys;
    va->description = vaQueryVendorString(sys->display);
    va->get = Get;
    return VLC_SUCCESS;

error:
    if (sys->pool != NULL)
        picture_pool_Release(sys->pool);
    if (sys->va_inst != NULL)
        vlc_vaapi_ReleaseInstance(sys->va_inst);
    free(sys);
    return VLC_EGENERIC;
}

vlc_module_begin ()
    set_description( "VA-API video decoder via DRM (kobplayer)" )
    set_capability( "hw decoder", 0 )
    set_callbacks( Create, Delete )
    add_shortcut( "kobvaapi" )
    add_string( "kobvaapi-device", NULL, "DRM render node",
                "Render node to decode on, e.g. /dev/dri/renderD129", true )
    set_category( CAT_INPUT )
    set_subcategory( SUBCAT_INPUT_VCODEC )
vlc_module_end ()
