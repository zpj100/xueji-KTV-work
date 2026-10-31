package com.local.ktv

import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View

/** Android 4.0/4.1（API 14/15）没有 View.setBackground，退化到 setBackgroundDrawable。 */
@Suppress("DEPRECATION")
fun View.setBackgroundCompat(drawable: Drawable?) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        setBackground(drawable)
    } else {
        setBackgroundDrawable(drawable)
    }
}

/** API 15 及以下无法读取 background，返回 false 视为未设置。 */
fun View.hasBackgroundCompat(): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) background != null else false
