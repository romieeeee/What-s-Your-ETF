# 4단계: 시뮬레이션 병목 분석

## 목적

공식 Before Baseline에서 관찰한 계산 시간 변동과 Main Thread 점유 위치를 trace로 확인한다. 이 단계에서는 성능 최적화 코드를 적용하지 않는다.

공식 Before Baseline의 주요 수치는 다음과 같다.

- 마지막 입력 → 상태 갱신: median `323.191ms`, max `409.599ms`
- 계산: median `19.248ms`, max `91.181ms`
- domain calculation: median `13.438ms`
- backtest: median `11.586ms`
- 취소 요청 → 종료: median `0ms`, max `15ms`

## 첫 번째 조사 질문

1. 백테스트와 도메인 계산이 실제로 Main Thread에서 실행되는가?
2. 계산 최대값이 `91.181ms`까지 증가한 실행에서는 어느 함수가 시간을 사용했는가?
3. 취소된 계산이 의미 있는 CPU 작업을 계속 수행하는가?

Baseline에서 취소 요청과 완료가 `80/80`으로 대응하고 종료 지연 중앙값이 `0ms`였으므로, cancellation은 현재 첫 번째 병목 후보가 아니다. 우선 Main Thread 계산과 반복 변환을 확인한다.

## 진단용 method trace 수집

기존 instrumentation harness에서 warm-up을 먼저 실행한 뒤 본 측정 3회만 sampling trace로 수집한다.

```powershell
cd frontend/WYE
./scripts/capture-simulation-method-trace.ps1 `
  -InputIntervalMillis 227 `
  -WarmupCount 5 `
  -MeasurementCount 3
```

각 실행은 `frontend/WYE/performance-results/trace-<timestamp>/` 아래에 다음 파일을 저장한다.

- `trace-<timestamp>.trace`: Android Studio CPU Profiler에서 확인할 sampling method trace
- `logcat.log`: trace와 같은 실행에서 수집한 `SimulationPerf` 원본 로그
- `events.csv`: 이벤트와 회차를 분리한 원자료
- `summary.json`, `summary.md`: 진단 실행의 자동 집계 결과
- `metadata.json`: commit SHA, 기기, 입력 간격, trace 설정
- `gradle.log`: instrumentation 실행 로그

## trace 확인 항목

Android Studio Profiler에서 `.trace` 파일을 불러온 뒤 다음을 확인한다.

1. `main` thread에서 `SimulationViewModel` 계산 호출이 이어지는지 확인한다.
2. Top Down 또는 Flame Chart에서 `RunSimulationUseCase`, `CalculateBacktestUseCase` 호출 경로를 찾는다.
3. Bottom Up에서 앱 패키지(`com.d102.wye`)의 sample이 집중된 함수를 확인한다.
4. 가격 데이터 교집합, 필터링·가격 맵 구성, 실제 계산 중 sample이 반복되는 위치를 구분한다.
5. 취소된 calculation ID가 다음 입력 이후에도 계산 함수에서 관찰되는지 로그와 대조한다.

## 해석 제한

method sampling은 앱 실행에 Profiler 오버헤드를 추가한다. 따라서 trace 실행의 시간은 공식 Before Baseline과 직접 비교하지 않는다.

- trace: 어느 thread와 함수가 비용을 사용하는지 진단
- 공식 Baseline: Profiler 없이 수집한 정량 수치

병목은 trace의 호출 위치, 기존 단계별 로그, 반복 측정의 자연 변동을 함께 근거로 판단한다.

## 진단 결과

`trace-20261006-185514`를 Pixel 7 AVD(Android 15, API 35)에서 입력 간격 `227ms`, warm-up 5회, 본 측정 3회 조건으로 수집했다. trace에는 buffer overflow가 없었고(`data-file-overflow=false`), Main Thread의 호출 경로를 `Thread Time` 기준으로 확인했다.

### Main Thread 호출 경로

Top Down에서 다음 호출 경로가 확인됐다.

```text
SimulationViewModel.triggerCalculation
  → RunSimulationUseCase.invoke
    → CalculateBacktestUseCase.invoke
      → calcLumpSum
      → CollectionsKt.intersect
```

`SimulationViewModel.triggerCalculation()`은 `viewModelScope.launch`에서 실행되고, `runSimulation()` 호출 전 별도의 dispatcher 전환이 없다. `RunSimulationUseCase`와 `CalculateBacktestUseCase`에도 `withContext(Dispatchers.Default)` 또는 이에 준하는 CPU dispatcher 경계가 없다. 따라서 가격 데이터 전처리와 백테스트 계산이 Main Thread에서 동기 실행되는 것을 trace와 코드에서 함께 확인했다.

### 함수별 Thread Time

| 함수 | Total | Main Thread 대비 | 해석 |
|---|---:|---:|---|
| `RunSimulationUseCase.invoke` | `12,806μs` | `21.19%` | 시뮬레이션 도메인 계산 전체 |
| `CalculateBacktestUseCase.invoke` | `11,559μs` | `19.13%` | 백테스트 계산 |
| `calcLumpSum` | `3,679μs` | `6.09%` | 거치식 일별 평가액 계산 |
| `CollectionsKt.intersect` | `2,738μs` | `4.53%` | ETF별 거래일 교집합 생성 |
| `SimulationResult.toUiModel` | `931μs` | `1.54%` | 첫 번째 최적화 대상으로 보기 어려움 |

이 값은 sampling trace의 진단 수치이므로 공식 Baseline과 절대 시간을 직접 비교하지 않는다. 다만 공식 Baseline에서도 계산 중앙값 `19.248ms`, backtest 중앙값 `11.586ms`가 관찰되어, Main Thread에서 도메인 계산이 수행된다는 방향은 서로 일치한다.

### 계측 로그 오버헤드

Bottom Up에서 Timber `d()` 호출 경로의 Total은 `28,315μs`(`46.85%`), `Log.println_native()`의 Self는 `23,249μs`(`38.47%`)였다. 이는 병목을 관찰하기 위해 추가한 Debug 계측 로그의 비용이 포함된 결과다. 따라서 Timber와 Android Log를 실제 시뮬레이션 알고리즘의 첫 번째 병목으로 선택하지 않는다.

### 재현하지 못한 항목

공식 Baseline에서 관찰한 계산 최대값 `91.181ms`는 본 측정 3회의 trace에서 재현되지 않았다. 따라서 해당 최대값을 특정 함수의 비용으로 단정하지 않는다. 실제 프레임 시간이나 dropped frame도 이번 harness에서 직접 측정하지 않았으므로, 현재 근거만으로 사용자에게 jank가 발생했다고 확정하지 않는다.

## 첫 번째 병목 가설과 다음 변경 범위

첫 번째 병목 가설은 다음과 같다.

> CPU 중심의 시뮬레이션 도메인 계산이 Main Thread에서 실행되어 UI 응답성을 저하시킬 위험이 있다.

다음 최적화에서는 계산 부분에만 주입 가능한 CPU dispatcher 경계를 두고, 입력 처리와 `UiState` 갱신은 Main Thread에 유지한다. 이 변경은 계산 알고리즘 자체를 빠르게 만드는 것이 아니라 Main Thread 점유를 격리하는 작업으로 정의한다.

다음 항목은 첫 번째 변경에서 제외한다.

- `300ms` debounce 변경: 입력 정책과 UX 판단이 필요한 별도 문제다.
- 날짜 교집합 또는 `calcLumpSum` 알고리즘 변경: dispatcher 변경과 효과를 분리해 측정하기 위해 후속 후보로 남긴다.
- Timber 제거를 성능 개선으로 간주: Debug 계측 비용이며 실제 도메인 알고리즘 개선이 아니다.
- 실제 jank 개선 주장: 표준 frame metric을 측정하지 않았으므로 주장 범위에서 제외한다.

다음 변경의 성공 조건은 결과 정확성과 최신 입력 보장이 유지되고, trace에서 도메인 계산이 Main Thread가 아닌 주입한 CPU dispatcher에서 실행되며, 공식 측정 지표가 유의하게 악화되지 않는 것이다.

## 완료 조건

- [x] 진단용 trace 파일 수집
- [x] Main Thread 실행 여부 확인
- [x] 계산 sample이 집중된 함수 확인
- [x] cancellation 가설 유지 또는 기각
- [x] 첫 번째 병목 가설을 코드 위치와 연결해 문서화
- [x] 변경할 코드 하나와 변경하지 않을 대안 기록
