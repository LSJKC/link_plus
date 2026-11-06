package io.github.shaonialife.buicklinkplus.audio

import android.content.Context
import androidx.core.content.edit

/**
 * 用户音频策略，修改后在下次连接时读取。
 *
 * **CarLife 音频已改为外部 PCM 直通（引擎 9240/9241 → AudioSender.startExternal），本策略
 * 不再参与任何会话路径**。保留类仅为 MainActivity（旧「音频策略」UI）与 AirPlayClient
 * （媒体档位识别 `isMediaChannel`）编译兼容；activation-ui 清理旧 UI 后应删除本文件。
 */
enum class AudioPreference(
    val title: String,
    val detail: String,
) {
    AUTO("自动", "按车机能力和已验证档位自动选择"),
    QUALITY("音质优先", "先尝试车机声明的 PCM 媒体通道，协商失败再尝试 AAC"),
    COMPATIBILITY("兼容", "优先兼容 PCM 档位，保留媒体 PCM 作为后备"),
    ;

    companion object {
        /**
         * 媒体发送允许的通道，不接受通话、语音识别和提示音通道
         */
        fun isMediaChannel(type: String?): Boolean = type in setOf("media", "compatibility", "default")

        /**
         * 读取持久化策略，未知配置回退自动
         */
        fun read(context: Context): AudioPreference =
            entries.firstOrNull {
                it.name == context.getSharedPreferences("audio_preferences", Context.MODE_PRIVATE).getString("strategy", null)
            } ?: AUTO

        /**
         * 保存下一次连接的用户策略
         */
        fun write(
            context: Context,
            value: AudioPreference,
        ) {
            context
                .getSharedPreferences("audio_preferences", Context.MODE_PRIVATE)
                .edit {
                    putString("strategy", value.name)
                }
        }

        /** 用户选择优先，自动模式继续使用档位记忆 */
        fun order(
            candidates: List<AudioProfile>,
            remembered: List<AudioProfile>,
            value: AudioPreference,
        ): List<AudioProfile> =
            when (value) {
                AUTO -> remembered
                QUALITY ->
                    candidates.filter { it.audioType == "media" }.sortedBy {
                        if (it.codec == AudioCodec.PCM) 0 else 1
                    }
                COMPATIBILITY ->
                    candidates.filter { it.codec == AudioCodec.PCM }.sortedBy {
                        when (it.audioType) {
                            "compatibility" -> 0
                            "default" -> 1
                            else -> 2
                        }
                    }
            }.distinctBy { it.key }
    }
}
