package com.topjohnwu.magisk.view

import android.content.Context
import android.text.Spanned
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.core.text.PrecomputedTextCompat
import androidx.core.widget.TextViewCompat
import com.topjohnwu.magisk.R
import com.topjohnwu.magisk.data.repository.StringRepository
import io.noties.markwon.Markwon
import kotlinx.coroutines.*
import org.koin.core.KoinComponent
import org.koin.core.inject
import timber.log.Timber
import kotlin.coroutines.coroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class PrecomputedTextSetter : Markwon.TextSetter {

    override fun setText(
        tv: TextView,
        text: Spanned,
        bufferType: TextView.BufferType,
        onComplete: Runnable
    ) {
        val scope = tv.tag as? CoroutineScope ?: GlobalScope
        scope.launch(Dispatchers.Main) {
            trySetText(tv, text, onComplete)
        }
    }

    /**
     * Tries precomputing text and setting it [retryBudget] + 1 times, otherwise gives up. When
     * giving up the text will be set to empty string for safety. [onComplete] callback will be
     * called only and only if the view successfully sets the precomputed text.
     *
     * It catches the exception that [TextViewCompat.setPrecomputedText] produces when
     * configurations of precomputed and current view do **not** match.
     * */
    private suspend fun trySetText(
        tv: TextView,
        text: Spanned,
        onComplete: Runnable,
        retryBudget: Int = 3
    ) {
        if (retryBudget < 0) {
            tv.text = ""
            return
        }

        val pre = withContext(Dispatchers.Default) {
            PrecomputedTextCompat.create(text, TextViewCompat.getTextMetricsParams(tv)).also {
                tv.awaitPost()
            }
        }

        kotlin.runCatching {
            // throws IllegalStateException if the text is incompatible with the view
            // view might've changed parameters and needs to be recomputed once more
            TextViewCompat.setPrecomputedText(tv, pre)
        }.onSuccess {
            onComplete.run()
        }.onFailure {
            trySetText(tv, text, onComplete, retryBudget - 1)
        }
    }

    private suspend fun View.awaitPost() = suspendCoroutine<Unit> {
        val success = post {
            it.resume(Unit)
        }

        if (!success) { // looper is exiting, no need to wait, it will be all over soon
            it.resume(Unit)
        }
    }

}

object MarkDownWindow : KoinComponent {

    private val repo: StringRepository by inject()
    private val markwon: Markwon by inject()

    suspend fun show(activity: Context, title: String?, url: String) {
        show(activity, title) {
            repo.getString(url)
        }
    }

    suspend fun show(activity: Context, title: String?, input: suspend () -> String) {
        val mdRes = R.layout.markdown_window_md2
        val mv = LayoutInflater.from(activity).inflate(mdRes, null)
        val tv = mv.findViewById<TextView>(R.id.md_txt)
        tv.tag = CoroutineScope(coroutineContext)

        try {
            markwon.setMarkdown(tv, input())
        } catch (e: Exception) {
            if (e is CancellationException)
                throw e
            Timber.e(e)
            tv.setText(R.string.download_file_error)
        }

        MagiskDialog(activity)
            .applyTitle(title ?: "")
            .applyView(mv)
            .applyButton(MagiskDialog.ButtonType.NEGATIVE) {
                titleRes = android.R.string.cancel
            }
            .reveal()
    }
}
