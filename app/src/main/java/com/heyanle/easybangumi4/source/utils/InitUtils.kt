package com.heyanle.easybangumi4.source.utils

import android.content.Context
import com.heyanle.easybangumi4.APP
import com.heyanle.easybangumi4.BuildConfig
import com.heyanle.lib_anim.utils.AppHelper
import com.heyanle.lib_anim.utils.FileHelper
import com.heyanle.lib_anim.utils.fileHelper
import com.heyanle.lib_anim.utils.network.NetworkHelper
import com.heyanle.lib_anim.utils.network.networkHelper
import com.heyanle.lib_anim.utils.network.webViewUserHelper
import com.heyanle.lib_anim.utils.network.webview_helper.WebViewHelperImpl
import com.heyanle.lib_anim.utils.network.webview_helper.webViewHelper
import com.heyanle.lib_anim.utils.stringHelper


/**
 * Created by HeYanLe on 2023/2/1 17:49.
 * https://github.com/heyanLE
 */
fun initUtils(context: Context) {
    AppHelper.context = APP
    fileHelper = FileHelper(context)
    networkHelper = NetworkHelper(context, BuildConfig.DEBUG)
    stringHelper = StringHelperImpl()
    webViewHelper = WebViewHelperImpl(context)
    webViewUserHelper = WebViewUserHelperImpl
}