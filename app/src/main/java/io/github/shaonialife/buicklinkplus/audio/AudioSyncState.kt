package io.github.shaonialife.buicklinkplus.audio

/** 同步降级只影响本次同步任务，媒体发送状态独立维护 */
internal class AudioSyncState {
    private var generation = 0L

    @Volatile var status = "未建立同步"
        private set

    @Synchronized fun begin(): Long {
        status = "正在建立同步"
        return ++generation
    }

    @Synchronized fun owns(request: Long): Boolean = request == generation

    @Synchronized fun update(
        request: Long,
        message: String,
    ) {
        if (owns(request)) status = message
    }

    @Synchronized fun stop() {
        generation++
        status = "已停止同步"
    }
}
