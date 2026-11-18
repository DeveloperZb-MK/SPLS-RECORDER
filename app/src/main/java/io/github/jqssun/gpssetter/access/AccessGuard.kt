package io.github.jqssun.gpssetter.access

import android.content.Context
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.jqssun.gpssetter.utils.PrefManager
import kotlinx.coroutines.launch

object AccessGuard {

    @Volatile
    private var checking = false

    /** Opens the access screen as a fresh task. */
    fun openAccessScreen(activity: AppCompatActivity, expired: Boolean = false) {
        activity.startActivity(
            Intent(activity, AccessActivity::class.java)
                .putExtra(AccessActivity.EXTRA_EXPIRED, expired)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        activity.finish()
    }

    /** Removes the saved access, stops the background check and switches the location mock off. */
    fun revoke(context: Context) {
        AccessStore.clearAll(context)
        AccessWorker.cancel(context)
        PrefManager.update(false, PrefManager.getLat, PrefManager.getLng)
    }

    /**
     * Silent check used from onResume. Only an answer of "not valid" kicks the user out.
     * A network problem is ignored here.
     */
    fun verifyOnResume(activity: AppCompatActivity) {
        if (checking) return
        checking = true
        activity.lifecycleScope.launch {
            try {
                when (AccessVerifier.verify(activity)) {
                    VerifyResult.Rejected, VerifyResult.NoToken -> {
                        revoke(activity)
                        openAccessScreen(activity, expired = true)
                    }
                    else -> Unit
                }
            } finally {
                checking = false
            }
        }
    }
}
