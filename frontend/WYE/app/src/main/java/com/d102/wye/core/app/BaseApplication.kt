package com.d102.wye.core.app

import android.app.Application
import android.content.Context
import com.d102.wye.BuildConfig
import com.kakao.sdk.common.KakaoSdk
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

/**
 * Application 클래스
 */
@HiltAndroidApp
class BaseApplication : Application() {


    companion object {
        private var instance: BaseApplication? = null

        /**
         * Application Context를 반환합니다
         */
        fun getContext(): Context {
            return instance?.applicationContext
                ?: throw IllegalStateException("Application not initialized")
        }

        // Notification Channel IDs
        const val CHANNEL_NEWS_SERVICE = "news_channel"
    }


    /** 앱 전역 라이브러리와 SDK를 초기화한다. */
    override fun onCreate() {
        super.onCreate()
        instance = this

        // Timber 초기화
        Timber.plant(Timber.DebugTree())

        // 테스트용 Debug 빌드는 게스트 모드로 바로 진입하므로 카카오 로그인을 초기화하지 않는다.
        if (BuildConfig.DEBUG) {
            Timber.d("Kakao SDK initialization skipped in guest test mode.")
        } else {
            initializeKakaoSdk()
        }
    }

    /** 카카오 SDK를 초기화한다. */
    private fun initializeKakaoSdk() {
        if (BuildConfig.KAKAO_NATIVE_APP_KEY.startsWith("TODO_")) {
            Timber.w("Kakao native app key is not configured yet.")
            return
        }

        KakaoSdk.init(this, BuildConfig.KAKAO_NATIVE_APP_KEY)
        Timber.d("Kakao SDK initialized.")
    }
}
