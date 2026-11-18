package io.github.jqssun.gpssetter.access

import android.content.Context

sealed class VerifyResult {
    /** Server said success = true. */
    object Valid : VerifyResult()
    /** Server said success = false (revoked, expired, wrong device). */
    object Rejected : VerifyResult()
    /** No token saved on this device. */
    object NoToken : VerifyResult()
    /** Could not reach the server or got an unclear answer. Access is NOT revoked. */
    object Error : VerifyResult()
}

object AccessVerifier {

    /**
     * Device restore: called only when there is no saved token (fresh install or cleared data).
     * Returns the restored token, or null when the server has nothing for this device.
     */
    suspend fun restoreSession(context: Context): String? {
        return try {
            val response = AccessClient.api.restoreSession(RestoreSessionBody(getDeviceId(context)))
            response.parsedBody()?.takeIf { it.success == true }?.token?.takeIf { it.isNotBlank() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
    suspend fun verify(context: Context): VerifyResult {
        val token = AccessStore.getToken(context) ?: return VerifyResult.NoToken
        return try {
            val response = AccessClient.api.verify(VerifyBody(token, getDeviceId(context)))
            when (response.parsedBody()?.success) {
                true -> VerifyResult.Valid
                false -> VerifyResult.Rejected
                null -> if (response.code() == 401 || response.code() == 403) {
                    VerifyResult.Rejected
                } else {
                    VerifyResult.Error
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            VerifyResult.Error
        }
    }
}
