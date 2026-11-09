package com.bitchat.android.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FaroUpdaterTest {

    private val sha = "a".repeat(64)

    @Test
    fun `lee un version json valido`() {
        val info = FaroUpdater.parseVersion(
            """{"versionCode":105,"versionName":"0.1.105","apkUrl":"https://example.org/faro.apk","sha256":"${sha.uppercase()}"}"""
        )
        assertNotNull(info)
        assertEquals(105L, info!!.versionCode)
        assertEquals("0.1.105", info.versionName)
        assertEquals(sha, info.sha256)
    }

    @Test
    fun `rechaza enlaces que no son https`() {
        assertNull(
            FaroUpdater.parseVersion(
                """{"versionCode":1,"apkUrl":"http://example.org/faro.apk","sha256":"$sha"}"""
            )
        )
    }

    @Test
    fun `rechaza una huella mal formada`() {
        assertNull(
            FaroUpdater.parseVersion(
                """{"versionCode":1,"apkUrl":"https://example.org/faro.apk","sha256":"1234"}"""
            )
        )
    }

    @Test
    fun `rechaza json incompleto o roto`() {
        assertNull(FaroUpdater.parseVersion("""{"versionCode":1}"""))
        assertNull(FaroUpdater.parseVersion("no es json"))
    }
}
