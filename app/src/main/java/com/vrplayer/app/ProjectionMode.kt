package com.vrplayer.app

enum class ProjectionMode {
    EQUIRECT_360,
    EQUIRECT_180,
    STEREO_SBS_180,
    STEREO_TB_180,
    CUBEMAP_3X2,
    STEREO_SBS,
    STEREO_TB,
    FLAT_2D;

    val hemisphere: Boolean
        get() = this == EQUIRECT_180 || this == STEREO_SBS_180 || this == STEREO_TB_180

    companion object {
        fun fromIndex(index: Int): ProjectionMode =
            entries.getOrElse(index) { EQUIRECT_360 }
    }
}
