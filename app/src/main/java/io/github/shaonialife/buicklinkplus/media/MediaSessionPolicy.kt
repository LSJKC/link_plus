package io.github.shaonialife.buicklinkplus.media

/**
 * 播放器暂停后保留原会话，其他播放器真正开始播放时才切换
 */
internal object MediaSessionPolicy {
    data class Candidate<T>(
        val id: T,
        val playing: Boolean,
        val metadata: Boolean,
    )

    /**
     * 会话列表重排不能改变暂停后的控制目标
     */
    fun <T> choose(
        candidates: List<Candidate<T>>,
        previous: T?,
    ): Int? {
        val index =
            candidates.indexOfFirst { it.playing && it.id == previous }.takeIf { it >= 0 }
                ?: candidates.indexOfFirst { it.playing }.takeIf { it >= 0 }
                ?: candidates.indexOfFirst { it.id == previous }.takeIf { it >= 0 }
                ?: candidates.indexOfFirst { it.metadata }.takeIf { it >= 0 }
        return index
    }
}
