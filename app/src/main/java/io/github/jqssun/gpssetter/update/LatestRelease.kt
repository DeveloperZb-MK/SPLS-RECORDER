package io.github.jqssun.gpssetter.update

import io.github.jqssun.gpssetter.BuildConfig
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/** Reads the newest release of the public releases repository. */
object LatestRelease {
    const val API_BASE = "https://api.github.com/repos/DeveloperZb-MK/khpalGps-releases/"
    const val RELEASES_PAGE = "https://github.com/DeveloperZb-MK/khpalGps-releases/releases"

    fun defaultService(): GitHubService =
        Retrofit.Builder()
            .baseUrl(API_BASE)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(GitHubService::class.java)

    /** True when [remoteTag] (for example "v1.0.3") is a higher version than [current] ("1.0.2"). */
    fun isNewer(remoteTag: String, current: String): Boolean {
        fun parts(v: String) = v.trim().removePrefix("v").removePrefix("V").split(".")
            .map { seg -> seg.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
        val a = parts(remoteTag)
        val b = parts(current)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    /** Returns the update when the latest release is newer than this app, otherwise null. Blocking call. */
    fun fetchNewer(service: GitHubService): UpdateChecker.Update? {
        var release: GitHubRelease? = null
        try {
            val response = service.getReleases().execute()
            if (response.isSuccessful) release = response.body()
        } catch (e: Exception) {
            return null
        }
        if (release == null) return null

        val tag = release.tagName ?: return null
        if (!isNewer(tag, BuildConfig.TAG_NAME)) return null

        val apks = release.assets.orEmpty().filter { it.name?.endsWith(".apk", true) == true }
        val asset = apks.firstOrNull { it.name?.contains(BuildConfig.FLAVOR, true) == true }
            ?: apks.firstOrNull()
        val url = asset?.browserDownloadUrl
        if (asset == null || url == null) return null

        return UpdateChecker.Update(
            name = release.name ?: tag,
            changelog = release.body.orEmpty().trim(),
            timestamp = release.publishedAt.orEmpty(),
            assetUrl = url,
            assetName = asset.name ?: "KHPAL-GPS-update.apk",
            releaseUrl = "$RELEASES_PAGE/tag/$tag"
        )
    }
}
