package io.github.shaonialife.buicklinkplus.airplay

/** 查询与设置能力分别记忆，明确拒绝后本会话不再重复 */
internal class ReceiverVolumePolicy {
    var queryAllowed = true
        private set
    var setAllowed = true
        private set

    fun observe(
        query: Boolean,
        status: String,
    ): Boolean {
        val code = status.split(' ').getOrNull(1)?.toIntOrNull()
        if (code in setOf(403, 405, 456, 501)) {
            if (query) queryAllowed = false else setAllowed = false
        }
        return code == 200
    }
}
