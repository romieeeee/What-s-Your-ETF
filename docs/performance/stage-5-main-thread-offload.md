# 5단계: 시뮬레이션 계산 Main Thread 격리

## 목적

4단계 trace에서 확인한 첫 번째 병목 가설에 따라 CPU 중심의 시뮬레이션 도메인 계산을 Main Thread 밖으로 격리한다.

이 변경의 주목적은 계산 알고리즘의 실행 시간을 단축하는 것이 아니라, `RunSimulationUseCase`를 Main Thread에서 안전하게 호출할 수 있도록 만드는 것이다. 입력 처리와 `UiState` 갱신은 기존처럼 Main Thread에 유지한다.

## 변경 내용

- `DefaultDispatcher` qualifier와 Hilt provider 추가
- `RunSimulationUseCase`에 `CoroutineDispatcher` 주입
- `RunSimulationUseCase.invoke()`의 CPU 계산을 `withContext(defaultDispatcher)`에서 실행
- 가상 시간 테스트에는 기존 `TestDispatcher` 주입
- Android 성능 측정에는 실제 `Dispatchers.Default` 주입
- 주입한 dispatcher 사용과 계산 결과 보존을 단위 테스트로 검증

Android의 coroutine 권장사항에 따라 dispatcher를 하드코딩하지 않고 주입하며, CPU 중심 작업을 수행하는 suspend 함수가 자신의 main-safety를 책임지도록 구성했다.

- [Android coroutine 권장사항](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)

## 실행 스레드 검증

Pixel 7 AVD(Android 15, API 35)에서 진단용 trace를 실행해 다음 스레드를 확인했다.

| 구간 | 실행 스레드 |
|---|---|
| cache read 및 입력 준비 | `main` |
| `CalculateBacktestUseCase` | `DefaultDispatcher-worker-1` |
| `RunSimulationUseCase` | `DefaultDispatcher-worker-1` |
| domain calculation 결과 수신 | `main` |
| UI model 변환 및 `UiState` 갱신 | `main` |

따라서 CPU 계산만 worker thread로 이동하고 UI 상태 변경은 Main Thread에 남는 것을 확인했다.

## 공식 After 측정

### 실행 조건

- Before commit: `ea2604860d5c9a561bef46e9ddbf7027729188a2`
- After commit: `ff8e9216bd74ceb81e020f2a7fc4df59bf74eb2e`
- 기기: Pixel 7 AVD
- Android: 15 / API 35
- Build: Debug
- Java: 21.0.6
- 입력 간격: `227ms`
- warm-up: 5회
- 본 측정: 20회
- After working tree: clean
- After 결과: `frontend/WYE/performance-results/baseline-20261006-200303`

### 정확성 및 취소 검증

- warm-up 성공: `5/5`
- 본 측정 성공 결과: `20/20`
- 취소 요청/종료: `80/80`
- 결과 검증: 통과

### Before/After

| 지표 | Before | After | 관찰 변화 |
|---|---:|---:|---:|
| 시나리오 전체 median | `1249.458ms` | `1241.632ms` | `-0.6%` |
| 마지막 입력 → 상태 갱신 median | `323.191ms` | `316.833ms` | `-2.0%` |
| 계산 median | `19.248ms` | `13.144ms` | `-31.7%` |
| domain calculation median | `13.438ms` | `8.433ms` | `-37.2%` |
| backtest median | `11.586ms` | `6.577ms` | `-43.2%` |
| 계산 max | `91.181ms` | `19.614ms` | `-78.5%` |

## 결과 해석

이번 변경에서 확정할 수 있는 결과는 다음과 같다.

1. 도메인 계산이 Main Thread에서 `DefaultDispatcher` worker thread로 이동했다.
2. 입력 처리와 최종 상태 갱신은 Main Thread에 유지됐다.
3. 동일 fixture의 결과 정확성과 최신 입력 보장이 유지됐다.
4. 취소 요청과 종료가 `80/80`으로 대응했다.
5. 기존 공식 측정 지표에서 유의한 회귀가 관찰되지 않았다.

After 측정에서 계산 관련 시간이 낮아졌지만, dispatcher 이동은 알고리즘의 연산량을 줄이지 않는다. 따라서 관찰된 시간 감소 전체를 이 변경의 직접적인 속도 향상으로 단정하지 않는다. 실행 스케줄링, JIT, GC 및 에뮬레이터 상태에 따른 변동 가능성이 있다.

또한 frame metric을 직접 측정하지 않았으므로 실제 dropped frame 또는 jank 감소를 주장하지 않는다. 이번 단계의 성과는 계산 속도 개선이 아니라 Main Thread 점유 위험의 구조적 격리다.

## 검증

- [x] `testDebugUnitTest`
- [x] `compileDebugAndroidTestKotlin`
- [x] `assembleDebug`
- [x] Pixel 7 AVD에서 `connectedDebugAndroidTest`
- [x] 진단용 trace에서 worker thread 실행 확인
- [x] 공식 After warm-up 5회 및 본 측정 20회 완료
- [x] 결과 정확성 및 취소 동작 검증

## 후속 후보

실제 계산 시간을 추가로 단축해야 한다면 dispatcher 변경과 분리해 다음 항목을 검토한다.

- ETF별 거래일 교집합 생성 과정의 중간 Set 할당
- 가격 이력의 날짜별 Map 재구성
- `calcLumpSum`의 날짜 × ETF 반복 조회

후속 알고리즘 변경은 별도 Baseline과 Before/After로 효과를 검증한다.
