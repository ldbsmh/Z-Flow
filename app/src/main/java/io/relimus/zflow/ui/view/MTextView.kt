package io.relimus.zflow.ui.view

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.widget.AppCompatTextView

/**
 * @date 2022/8/23
 * @author sunshine0523
 * 获得焦点以自动跑马灯
 */
class MTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : AppCompatTextView(context, attrs) {

    override fun isFocused(): Boolean {
        return true
    }
}