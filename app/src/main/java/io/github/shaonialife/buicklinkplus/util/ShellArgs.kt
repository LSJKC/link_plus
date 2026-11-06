package io.github.shaonialife.buicklinkplus.util

/** 将组件等单个参数按 shell 单引号规则转义 */
internal fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
