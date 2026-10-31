package com.local.ktv

import android.graphics.Outline
import android.view.View
import android.view.ViewOutlineProvider

/** 仅 Android 5.0+ 调用；4.x 没有 ViewOutlineProvider/clipToOutline。 */
object ViewOutlineCompat {
    fun oval(view: View) {
        view.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, outline: Outline) = outline.setOval(0, 0, v.width, v.height)
        }
        view.clipToOutline = true
    }
}
