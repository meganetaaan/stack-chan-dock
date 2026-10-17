package jp.stackchan.localvoicepoc.sano

import java.io.File

data class SanoInstallation(val model: File, val dictionary: File) {
    fun isVerified(active: () -> Unit = {}): Boolean =
        SanoAssets.inspect(model, SanoAssets.weights, active).status == "verified" &&
            SanoAssets.inspect(dictionary, SanoAssets.dictionary, active).status == "verified"
}
