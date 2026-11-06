package io.github.shaonialife.buicklinkplus.session

import io.github.shaonialife.buicklinkplus.video.VideoProfile

/** 本次连接的只读状态 */
data class SessionSnapshot(
    val streaming: Boolean,
    val stopping: Boolean,
    val phase: String?,
    val video: VideoProfile?,
    val audio: String,
    val network: String?,
)
