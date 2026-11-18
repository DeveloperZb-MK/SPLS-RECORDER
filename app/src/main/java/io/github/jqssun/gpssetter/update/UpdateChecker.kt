package io.github.jqssun.gpssetter.update

import android.content.Context
import android.os.Parcelable
import kotlinx.android.parcel.Parcelize
import java.io.File
import javax.inject.Inject


class UpdateChecker @Inject constructor(private val apiResponse : GitHubService) {

    /** Returns the new version if one is available, otherwise null. Runs the network call, so call it off the main thread. */
    fun getLatestUpdate(): Update? = LatestRelease.fetchNewer(apiResponse)

    fun clearCachedDownloads(context: Context){
        File(context.externalCacheDir, "updates").deleteRecursively()
    }

    @Parcelize
    data class Update(val name: String, val changelog: String, val timestamp: String, val assetUrl: String, val assetName: String, val releaseUrl: String):
        Parcelable
}
