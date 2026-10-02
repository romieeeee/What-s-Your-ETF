package com.d102.wye

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d102.wye.BuildConfig
import com.d102.wye.core.app.Constants
import com.d102.wye.domain.repository.AuthRepository
import com.d102.wye.presentation.LaunchSplashScreen
import com.d102.wye.presentation.navigation.AppEntryViewModel
import com.d102.wye.presentation.navigation.AppScaffold
import com.d102.wye.presentation.navigation.Route
import com.d102.wye.presentation.theme.WYETheme
import com.google.firebase.messaging.FirebaseMessaging
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject
import android.graphics.Color as AndroidColor

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var authRepository: AuthRepository

    private val requestNotificationPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* 결과 무관하게 토큰 등록 시도 */ }

    override fun onCreate(savedInstanceState: Bundle?) {
//        installSplashScreen()

        super.onCreate(savedInstanceState)

        // 알림 권한 요청 (Android 13+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            ),
            navigationBarStyle = SystemBarStyle.light(
                AndroidColor.TRANSPARENT,
                AndroidColor.TRANSPARENT
            )
        )
        setContent {
            WYETheme {
                val appEntryViewModel: AppEntryViewModel = hiltViewModel()
                val isLoggedIn by appEntryViewModel.isLoggedIn.collectAsStateWithLifecycle()
                val sessionExpired by authRepository.sessionExpired.collectAsStateWithLifecycle(initialValue = false)
                var isSplashFinished by rememberSaveable { mutableStateOf(false) }
                // 테스트용 Debug 빌드는 인증 상태를 기다리지 않고 게스트로 진입한다.
                val isGuestMode = BuildConfig.DEBUG
                val shouldShowSplash = !isSplashFinished || (!isGuestMode && isLoggedIn == null)

                LaunchedEffect(isGuestMode, isLoggedIn) {
                    // 로그인 이후에만 FCM 토큰을 서버에 등록해 JWT 헤더가 함께 전송되도록 한다.
                    if (!isGuestMode && isLoggedIn == true) {
                        FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                            CoroutineScope(Dispatchers.IO).launch {
                                authRepository.registerFcmToken(token)
                            }
                        }
                    }
                }

                LaunchedEffect(isGuestMode, isLoggedIn, sessionExpired) {
                    if (!isGuestMode && isLoggedIn == false && sessionExpired) {
                        Toast.makeText(
                            this@MainActivity,
                            Constants.ERROR_SESSION_EXPIRED,
                            Toast.LENGTH_SHORT
                        ).show()
                        authRepository.consumeSessionExpired()
                    }
                }

                if (shouldShowSplash) {
                    LaunchSplashScreen(
                        onAnimationFinished = { isSplashFinished = true }
                    )
                } else {
                    val startDestination =
                        if (isGuestMode || isLoggedIn == true) Route.Home.route else Route.Login.route
                    AppScaffold(startDestination = startDestination)
                }
            }
        }
    }
}
