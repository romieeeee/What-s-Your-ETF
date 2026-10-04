package com.d102.wye.presentation.simulation.entry

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.d102.wye.R
import com.d102.wye.domain.model.EtfBundle
import com.d102.wye.presentation.designsystem.WyePrimaryButton
import com.d102.wye.presentation.designsystem.WyeTopBar
import com.d102.wye.presentation.model.UiState
import com.d102.wye.presentation.theme.BackGroundLightGreen
import com.d102.wye.presentation.theme.TextPrimary
import com.d102.wye.presentation.theme.TextSecondary
import com.d102.wye.presentation.theme.TextTertiary

@Composable
fun SimulationEntryScreen(
    onMakePortfolioClick: (List<String>) -> Unit,
    viewModel: SimulationEntryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedBundle by viewModel.selectedBundleDetail.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }


    LaunchedEffect(uiState) {
        if (uiState is UiState.Error) {
            snackbarHostState.showSnackbar(
                message = (uiState as UiState.Error).message
            )
        }
    }

    val isPortfolioFull = (uiState as? UiState.Success)?.data?.isPortfolioFull ?: false

    selectedBundle?.let { bundle ->
        BundleDetailDialog(
            bundle = bundle,
            isPortfolioFull = isPortfolioFull,
            onDismiss = { viewModel.onBundleDialogDismiss() },
            onStartSimulation = { bundle ->
                viewModel.onBundleDialogDismiss()
                onMakePortfolioClick(bundle.etfItems.map { it.ticker })
            }
        )
    }

    SimulationScreenContent(
        uiState = uiState,
        snackbarHostState = snackbarHostState,
        onMakePortfolioClick = onMakePortfolioClick,
        onBundleClick = { viewModel.onBundleClick(it) }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SimulationScreenContent(
    uiState: UiState<SimulationEntryData>,
    snackbarHostState: SnackbarHostState,
    onMakePortfolioClick: (List<String>) -> Unit,
    onBundleClick: (EtfBundle) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
    ) {
        WyeTopBar("투자 시뮬레이션")

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.White)
        ) {
            when (uiState) {
                is UiState.Loading -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                is UiState.Success -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                    ) {
                        // 1. 상단 배너 영역
                        SimulationBanner(
                            isPortfolioFull = uiState.data.isPortfolioFull,
                            onMakePortfolioClick = { onMakePortfolioClick(emptyList()) }
                        )

                        Spacer(modifier = Modifier.height(40.dp))

                        // 2. 추천 ETF 꾸러미 영역
                        RecommendedBundlesSection(
                            bundles = uiState.data.bundles,
                            onBundleClick = onBundleClick
                        )
                    }
                }

                is UiState.Error -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 24.dp)
                    ) {
                        SimulationBanner(
                            isPortfolioFull = false,
                            onMakePortfolioClick = { onMakePortfolioClick(emptyList()) }
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        Text(
                            text = "추천 ETF 꾸러미는 서버 연결 후 표시됩니다.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextTertiary
                        )
                    }
                }

                UiState.Idle -> Unit
            }

            SnackbarHost(
                hostState = snackbarHostState,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun SimulationBanner(
    isPortfolioFull: Boolean,
    onMakePortfolioClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(color = BackGroundLightGreen, shape = RoundedCornerShape(12.dp))
            .padding(24.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            // 타이틀 텍스트
            Text(
                text = "나만의 투자 전략,\n미리 확인해 보세요",
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.fillMaxWidth(),
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 서브 텍스트
            Text(
                text = "과거 데이터를 바탕으로 내 자산의 미래를 그려보는\n시뮬레이션입니다.",
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.fillMaxWidth(),
                color = TextTertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 중앙 아이콘
            Box(
                modifier = Modifier.padding(vertical = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(R.drawable.img_trendingup),
                    contentDescription = null,
                    modifier = Modifier.size(100.dp),
                )
            }

            // 포트폴리오 만들기 버튼
            WyePrimaryButton(
                text = "포트폴리오 만들기",
                style = MaterialTheme.typography.labelLarge,
                enabled = !isPortfolioFull,
                onClick = onMakePortfolioClick
            )

            if (isPortfolioFull) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "포트폴리오는 최대 10개까지 저장할 수 있어요.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        }
    }
}

@Composable
fun RecommendedBundlesSection(
    bundles: List<EtfBundle>,
    onBundleClick: (EtfBundle) -> Unit
) {
    Column {
        Text(
            text = "막막하다면? 추천 ETF 꾸러미로 시작하기",
            style = MaterialTheme.typography.titleMedium.copy(fontSize = 18.sp)
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "전문가가 엄선한 테마별 꾸러미를 바로 시뮬레이션 해보세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = TextTertiary
        )

        Spacer(modifier = Modifier.height(16.dp))

        // 가로 스크롤 리스트
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            items(bundles) { bundle ->
                BundleCard(bundle = bundle, onClick = { onBundleClick(bundle) })
            }
        }
        Spacer(modifier = Modifier.height(16.dp))
    }
}
