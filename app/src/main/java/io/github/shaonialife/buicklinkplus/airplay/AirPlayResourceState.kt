package io.github.shaonialife.buicklinkplus.airplay

/** 保存协商后的资源归属，车机借屏时不重复声明手机持有 */
internal class AirPlayResourceState {
    private data class Resource(
        var permanentEntity: Long = 1L,
        var takeConstraint: Int? = null,
        val borrowers: MutableSet<String> = linkedSetOf(),
    ) {
        val entity: Long get() = if (borrowers.isEmpty()) permanentEntity else 2L
    }

    private val resources = linkedMapOf(1 to Resource(), 2 to Resource())
    private val appEntities = linkedMapOf(2 to 0L, 3 to 0L, 1 to 0L)
    private var speechMode = -1L

    @get:Synchronized
    val phoneOwnsScreen: Boolean get() = resources.getValue(1).entity == 1L

    /** 每条控制连接独立初始化，采用 /info 的主屏初始归属 */
    @Synchronized
    fun initialize(modes: Map<*, *>?) {
        resources.values.forEach {
            it.permanentEntity = 1L
            it.takeConstraint = null
            it.borrowers.clear()
        }
        appEntities.keys.forEach { appEntities[it] = 0L }
        speechMode = -1L
        (modes?.get("resources") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val resource = resources[(item["resourceID"] as? Number)?.toInt()] ?: return@forEach
            resource.permanentEntity = 2L
        }
        (modes?.get("initialPermanentEntity") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val resource = resources[(item["resourceID"] as? Number)?.toInt()] ?: return@forEach
            (item["permanentEntity"] as? Number)?.toLong()?.takeIf { it in 0L..2L }?.let {
                resource.permanentEntity = it
            }
            resource.takeConstraint = (item["takeConstraint"] as? Number)?.toInt()
        }
        applyCarChange(modes)
    }

    /** 启动只申请主音频，主屏状态先按车机给出的归属发布 */
    @Synchronized
    fun claimAudio() {
        resources.getValue(2).permanentEntity = 1L
    }

    /** 请求返回手机界面时，保留车机尚未归还的临时借用 */
    @Synchronized
    fun requestPhoneScreen() {
        val screen = resources.getValue(1)
        if (screen.borrowers.isEmpty() && screen.takeConstraint.let { it == null || it == 100 || it == 500 }) {
            screen.permanentEntity = 1L
        }
    }

    /** 车机的转移语义为接管、归还、临时借用和结束借用，未知操作不改变所有权 */
    @Synchronized
    fun applyCarChange(params: Map<*, *>?) {
        (params?.get("resources") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val resource = resources[(item["resourceID"] as? Number)?.toInt()] ?: return@forEach
            when ((item["transferType"] as? Number)?.toInt()) {
                1 -> {
                    resource.permanentEntity = 2L
                    resource.takeConstraint = (item["takeConstraint"] as? Number)?.toInt()
                }
                2 -> resource.permanentEntity = 1L
                3 -> resource.borrowers.add(item["borrowID"] as? String ?: "")
                4 -> resource.borrowers.remove(item["borrowID"] as? String ?: "")
            }
        }
        applyAppStates(params)
    }

    /** 生成独立快照，避免后续事件改动已排队命令 */
    @Synchronized
    fun command(): Map<String, Any?> =
        linkedMapOf(
            "type" to "modesChanged",
            "params" to
                linkedMapOf(
                    "appStates" to
                        appEntities.map { (id, entity) ->
                            linkedMapOf<String, Any?>("appStateID" to id.toLong(), "entity" to entity).apply {
                                if (id == 1) put("speechMode", speechMode)
                            }
                        },
                    "resources" to
                        resources.map { (id, resource) ->
                            linkedMapOf<String, Any?>(
                                "resourceID" to id.toLong(),
                                "entity" to resource.entity,
                                "permanentEntity" to resource.permanentEntity,
                            )
                        },
                ),
        )

    private fun applyAppStates(params: Map<*, *>?) {
        (params?.get("appStates") as? List<*>)?.forEach { raw ->
            val item = raw as? Map<*, *> ?: return@forEach
            val id = (item["appStateID"] as? Number)?.toInt() ?: return@forEach
            if (id !in appEntities) return@forEach
            if (id == 1) {
                (item["speechMode"] as? Number)?.toLong()?.let {
                    speechMode = it
                    appEntities[id] = if (it == -1L) 0L else 2L
                }
            } else {
                (item["state"] as? Boolean)?.let { appEntities[id] = if (it) 2L else 0L }
            }
        }
    }
}

/** 同时检查 RTSP 和业务结果，不凭响应中出现 200 判断成功 */
internal object AirPlayCommandResult {
    fun accepted(
        statusLine: String,
        body: ByteArray,
    ): Boolean {
        if (!Regex("^(?:RTSP|HTTP)/[0-9.]+\\s+200(?:\\s|$)").containsMatchIn(statusLine)) return false
        if (body.isEmpty()) return true
        val response = runCatching { BinaryPlist.parse(body) as? Map<*, *> }.getOrNull() ?: return false
        if (!response.containsKey("status")) return true
        val status = response["status"] as? Number ?: return false
        return status.toDouble() == 0.0
    }
}
