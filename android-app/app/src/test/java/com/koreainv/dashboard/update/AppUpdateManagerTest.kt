package com.koreainv.dashboard.update

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {
    @Test
    fun parseReleasePolicyMatchesDesktopMandatoryTokens() {
        MANDATORY_POLICY_TOKENS.forEach { token ->
            assertEquals(ReleasePolicy.MANDATORY, parseReleasePolicy("Release note\n$token"))
        }
    }

    @Test
    fun parseReleasePolicyIgnoresWhitespaceDifferences() {
        assertEquals(
            ReleasePolicy.MANDATORY,
            parseReleasePolicy("변경 사항\n업데이트 정책 :  필수"),
        )
    }

    @Test
    fun startupFilteringSkipsRecommendedRelease() {
        val release = parseReleaseInfo(
            json = releaseJson(tagName = "v1.6.8", body = "UI 개선"),
            currentVersion = "1.6.7",
            includeRecommended = false,
        )

        assertNull(release)
    }

    @Test
    fun startupFilteringKeepsMandatoryRelease() {
        val release = parseReleaseInfo(
            json = releaseJson(tagName = "v1.6.8", body = "[mandatory-update]\n필수 업데이트"),
            currentVersion = "1.6.7",
            includeRecommended = false,
        )

        assertNotNull(release)
        assertEquals(ReleasePolicy.MANDATORY, release?.policy)
    }

    @Test
    fun manualChecksStillReturnRecommendedRelease() {
        val release = parseReleaseInfo(
            json = releaseJson(tagName = "v1.6.8", body = "성능 개선"),
            currentVersion = "1.6.7",
            includeRecommended = true,
        )

        assertNotNull(release)
        assertEquals(ReleasePolicy.RECOMMENDED, release?.policy)
        assertEquals("KISDashboard-android.apk", release?.asset?.name)
    }

    @Test
    fun versionComparisonHandlesPatchDepthAndSameVersion() {
        assertTrue(isNewerVersion("v1.6.7.1", "1.6.7"))
        assertFalse(isNewerVersion("v1.6.7", "1.6.7"))
        assertFalse(isNewerVersion("v1.6.6", "1.6.7"))
    }

    @Test
    fun parseReleaseInfoReturnsNullWhenAndroidAssetMissing() {
        val release = parseReleaseInfo(
            json = JsonParser().parse(
                """
                {
                  "tag_name": "v1.6.8",
                  "body": "mandatory-update",
                  "assets": [
                    {
                      "name": "KISDashboard-win64.zip",
                      "browser_download_url": "https://example.com/KISDashboard-win64.zip"
                    }
                  ]
                }
                """.trimIndent(),
            ).asJsonObject,
            currentVersion = "1.6.7",
            includeRecommended = true,
        )

        assertNull(release)
    }

    private fun releaseJson(tagName: String, body: String) = JsonParser().parse(
        """
        {
          "tag_name": "$tagName",
          "body": "$body",
          "assets": [
            {
              "name": "KISDashboard-android.apk",
              "browser_download_url": "https://example.com/KISDashboard-android.apk"
            }
          ]
        }
        """.trimIndent(),
    ).asJsonObject
}
