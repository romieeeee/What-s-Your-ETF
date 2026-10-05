# 3단계: Baseline 측정

## 현재 상태

공식 Baseline을 반복 수집하기 위한 실행·검증·집계 자동화를 구성했다. 실제 UI 입력을 3회 관찰해 대표 입력 간격을 `227ms`로 확정했다.

## 측정 명령

에뮬레이터 또는 기기 한 대만 연결한 상태에서 실행한다.

```powershell
cd frontend/WYE
./scripts/run-simulation-baseline.ps1 `
  -InputIntervalMillis 227 `
  -WarmupCount 5 `
  -MeasurementCount 20
```

측정 스크립트는 다음 작업을 한 번에 수행한다.

1. Git SHA와 working tree 상태를 확인한다.
2. 연결 기기, Android 버전, API Level을 기록한다.
3. `SimulationPerf` logcat을 수집하면서 instrumentation harness를 실행한다.
4. warm-up과 본 측정 완료 횟수를 검증한다.
5. 원본 로그, 전체 이벤트 CSV, JSON/Markdown 요약을 저장한다.

커밋되지 않은 변경이 있으면 기본적으로 실행을 중단한다. 조사 목적으로만 실행할 때는 `-AllowDirty`를 명시할 수 있지만, 그 결과를 공식 Before/After 수치로 사용하지 않는다.

## 공식 Before Baseline

2026-10-05에 `baseline-20261005-213742`를 공식 Before Baseline으로 수집했다.

| 항목 | 값 |
|---|---|
| commit SHA | `ea2604860d5c9a561bef46e9ddbf7027729188a2` |
| branch | `perf/simulation-baseline-results` |
| working tree | clean |
| build variant | Debug |
| 기기 | Pixel 7 AVD (`sdk_gphone64_x86_64`) |
| Android | 15 / API 35 |
| Java | Android Studio JBR `21.0.6.0` |
| 입력 간격 | `227ms` |
| 반복 | warm-up 5회, 본 측정 20회 |

원자료 903행과 자동 집계 결과를 대조했다. warm-up `5/5`, 본 측정 `20/20`이 완료됐고 각 본 측정 회차에 성공 결과가 정확히 하나씩 연결됐다. 취소 요청과 취소 완료는 각각 80건이며 검증 오류는 없다.

### 핵심 통계

| 지표 | median | min | max | range |
|---|---:|---:|---:|---:|
| 시나리오 전체 | 1249.458ms | 1240.994ms | 1356.527ms | 115.533ms |
| 마지막 입력 → 상태 갱신 | 323.191ms | 317.396ms | 409.599ms | 92.203ms |
| 계산 | 19.248ms | 13.338ms | 91.181ms | 77.843ms |
| 취소 요청 → 종료 | 0ms | 0ms | 15ms | 15ms |

### 단계별 median

| 단계 | median |
|---|---:|
| cache read | 0.124ms |
| prepare inputs | 0.214ms |
| date intersection | 3.528ms |
| filter and price map | 2.016ms |
| calculate | 2.936ms |
| downsample | 0.183ms |
| backtest | 11.586ms |
| weighted fundamentals | 0.064ms |
| domain calculation | 13.438ms |
| UI mapping | 0.395ms |

마지막 입력부터 상태 갱신까지의 `323.191ms` 중 계산 중앙값은 `19.248ms`다. 현재 수치만 보면 체감 대기의 대부분은 `300ms` debounce와 그 이후 상태 전달 구간에서 발생한다. 다만 계산 최대값이 `91.181ms`까지 증가한 원인은 수치만으로 확정하지 않고 trace에서 스레드 점유와 호출 구간을 확인한다.

## 산출물

각 실행은 `frontend/WYE/performance-results/baseline-<timestamp>/` 아래에 저장된다.

- `metadata.json`: commit SHA, branch, 기기, API Level, 입력 간격, 반복 횟수
- `logcat.log`: 가공하지 않은 `SimulationPerf` 로그
- `events.csv`: 이벤트와 주요 필드를 열로 분리한 원자료
- `summary.json`: 자동 검증 결과와 집계값
- `summary.md`: 사람이 빠르게 검토할 수 있는 요약
- `gradle.log`: instrumentation 실행 로그

`performance-results/`는 로컬 측정 원자료이므로 Git에 커밋하지 않는다. 공식 결과 문서에는 측정 환경, 원자료 경로, 핵심 통계와 해석만 기록한다.

## 집계 기준

- warm-up은 횟수만 검증하고 통계에서 제외한다.
- 본 측정은 median을 주 지표로 사용한다.
- min/max와 range를 함께 남겨 자연 변동 폭을 확인한다.
- `inputToSuccessMs`는 화면 렌더링 완료가 아닌 마지막 입력부터 `UiState` 갱신까지의 시간이다.
- `cancel_requested`와 `cancelled`의 logcat timestamp 차이는 취소 요청 후 종료 지연이며 CPU 사용 시간으로 표현하지 않는다.
- `thread` 필드는 실제 실행 스레드를 확인하는 근거로 사용한다.

## 공식 측정 전 남은 조건

- [x] 실제 UI 입력 간격을 3회 관찰하고 대표값 `227ms` 확정
- [x] 자동화 변경을 커밋해 Before SHA `ea2604860d5c9a561bef46e9ddbf7027729188a2` 고정
- [x] 동일 에뮬레이터/API Level에서 warm-up 5회, 본 측정 20회 실행
- [x] 원자료 903행과 요약값 대조
- [ ] 진단용 trace로 가장 비싼 단계와 thread 점유 확인
- [ ] 첫 번째 가설 유지 또는 기각 근거 기록
