package com.mts.tikmod.discovery

object Fingerprints {
    // Known Standard Class Names in TikTok v47.1.4
    const val AWEME_CLASS = "com.ss.android.ugc.aweme.feed.model.Aweme"
    const val AWEME_CONTROL_CLASS = "com.ss.android.ugc.aweme.feed.model.AwemeControl"
    const val AWEME_STATUS_CLASS = "com.ss.android.ugc.aweme.feed.model.AwemeStatus"
    const val VIDEO_CLASS = "com.ss.android.ugc.aweme.feed.model.Video"
    
    const val BASE_CONTENT_CLASS = "com.ss.android.ugc.aweme.im.sdk.chat.data.model.BaseContent"
    const val IM_MESSAGE_CLASS = "com.ss.android.ugc.aweme.im.service.model.IMMessage"
    
    const val PNS_REGION_SERVICE_CLASS = "com.ss.android.ugc.aweme.changeregion.impl.PNSChangeRegionServiceImpl"
    const val SETTINGS_ACTIVITY_CLASS = "com.ss.android.ugc.aweme.setting.ui.SettingNewVersionActivity"

    // String fingerprints for dynamic scanning
    const val STR_PLAY_WM = "/playwm/"
    const val STR_IS_AD = "isAd"
    const val STR_DELETE_MESSAGE = "deleteMessage"
    const val STR_RECALL_MESSAGE = "recallMessage"
    const val STR_DOWNLOAD_ADDR = "getDownloadAddr"
    const val STR_SETTINGS_AND_PRIVACY = "SettingNewVersionActivity"
    const val SHARE_PANEL_CLASS = "com.ss.android.ugc.aweme.share.ui.SharePanel"
}
