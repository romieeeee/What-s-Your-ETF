package com.d102.wye.presentation.navigation

import com.d102.wye.R

/**
 * 하단 탭 정의
 *
 * Route와의 연결:
 * - route 프로퍼티로 NavController 이동에 사용
 * - routes Set으로 AppScaffold에서 BottomBar 표시 여부 판단
 */
enum class BottomNavTab(
    val label: String,
    val iconRes: Int,
    val route: String
) {
    HOME(
        label = "홈",
        iconRes = R.drawable.ic_nav_home,
        route = Route.Home.route
    ),
    EXPLORE(
        label = "탐색",
        iconRes = R.drawable.ic_nav_explore,
        route = Route.Explore.route
    ),
    SIMULATION(
        label = "시뮬레이션",
        iconRes = R.drawable.ic_nav_simulation,
        // 성능 개선 작업 중에는 서버 의존 프리셋 화면을 건너뛰고 작성 화면으로 바로 진입한다.
        route = Route.Simulation.route
    ),
    STRATEGY(
        label = "나의 전략",
        iconRes = R.drawable.ic_nav_strategy,
        route = Route.Strategy.route
    ),
    MYPAGE(
        label = "마이페이지",
        iconRes = R.drawable.ic_nav_mypage,
        route = Route.MyPage.route
    );

    companion object {
        // BottomNavBar가 보이는 route 집합
        val routes: Set<String> = entries.map { it.route }.toSet()

        // route 문자열로 탭 찾기 (AppScaffold에서 현재 선택 탭 판단용)
        fun fromRoute(route: String?): BottomNavTab =
            entries.firstOrNull { it.route == route } ?: HOME
    }
}