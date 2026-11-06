package io.github.shaonialife.buicklinkplus.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * WMO 天气代码映射为固定中文描述，未知代码回落多云
 */
class WeatherStateTest {
    @Test fun describesWeatherCodes() {
        assertEquals("晴", WeatherState.describeCode(0))
        assertEquals("多云", WeatherState.describeCode(2))
        assertEquals("阴", WeatherState.describeCode(3))
        assertEquals("雾", WeatherState.describeCode(45))
        assertEquals("雨", WeatherState.describeCode(61))
        assertEquals("雪", WeatherState.describeCode(73))
        assertEquals("阵雨", WeatherState.describeCode(80))
        assertEquals("雷雨", WeatherState.describeCode(95))
        assertEquals("多云", WeatherState.describeCode(999))
    }
}
