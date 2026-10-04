# 2단계: Baseline 측정 사전 조건

## 목적

최적화 전에 동일한 입력과 데이터를 반복하고, 계산 이벤트와 원자료를 추적 가능한 형태로 남긴다.

## 고정된 fixture

- 기준일: `2026-10-02`
- ETF: 6개
- 비중: `17, 17, 17, 17, 16, 16`
- 가격 이력: 기준일 이전 약 3년의 평일 데이터
- 투자 기간: 36개월
- 투자 방식: 거치식
- 최종 입력: `10000`만원

Debug Fake Repository는 실행 날짜와 관계없이 위 기준일을 사용한다. 앱을 다시 실행해도 같은 입력은 같은 가격 이력과 시뮬레이션 결과를 만든다.

## 자동 입력

`SimulationAmountInputDriver`는 다음 입력을 순서대로 전달한다.

```text
1 → 10 → 100 → 1000 → 10000
```

입력 간격은 실제 UI 관찰 결과를 instrumentation argument로 전달한다. 가상 시간은 driver 순서와 동시성 검증에만 사용하며 성능 수치로 사용하지 않는다.

## 검증 항목

- 동일 fixture와 동일 입력은 동일한 `SimulationUiModel`을 생성한다.
- 연속 입력 결과는 마지막 입력만 단독으로 전달한 결과와 동일하다.
- 다른 투자 금액은 다른 최종 자산 결과를 만든다.
- driver는 동일한 입력 순서와 간격을 재현한다.
- 각 Timber 이벤트는 `calculationId`와 `inputEventId`로 연결한다.

## Android 측정 harness

`SimulationPerformanceInstrumentedTest`는 실제 Android runtime과 Main dispatcher에서 입력을 반복한다.

```powershell
.\gradlew.bat connectedDebugAndroidTest `
  -Pandroid.testInstrumentationRunnerArguments.class=com.d102.wye.performance.SimulationPerformanceInstrumentedTest `
  -Pandroid.testInstrumentationRunnerArguments.runSimulationPerformance=true `
  -Pandroid.testInstrumentationRunnerArguments.inputIntervalMillis=<UI에서 관찰한 값> `
  -Pandroid.testInstrumentationRunnerArguments.warmupCount=5 `
  -Pandroid.testInstrumentationRunnerArguments.measurementCount=20
```

`inputIntervalMillis`는 실제 UI 관찰 전에는 확정하지 않는다.

## 원자료 저장

측정 전에 별도 PowerShell 터미널에서 다음 스크립트를 실행한다.

```powershell
.\scripts\capture-simulation-performance.ps1 -DurationSeconds 60
```

결과는 `frontend/WYE/performance-results/`에 원본 logcat과 CSV로 저장된다. 이 디렉터리는 Git에 커밋하지 않는다.

## Baseline 진입 전 체크리스트

- [x] 서버에 의존하지 않는 Fake Repository
- [x] 실행 날짜와 무관한 고정 fixture
- [x] 동일 입력 순서와 간격을 재생하는 driver
- [x] 실제 Android runtime 측정 harness
- [x] 동일 결과 및 최신 입력 우선 처리 테스트
- [x] 원본 로그와 CSV 저장 스크립트
- [ ] 실제 UI 입력 간격 3회 관찰 및 대표값 결정
- [ ] 사전 작업 commit SHA를 Before SHA로 기록
- [ ] 에뮬레이터/API Level/build variant를 측정 보고서에 기록

마지막 세 항목을 기록한 뒤 3단계 Baseline 측정을 시작한다.
