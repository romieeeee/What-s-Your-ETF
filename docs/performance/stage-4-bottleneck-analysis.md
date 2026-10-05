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

## 완료 조건

- [ ] 진단용 trace 파일 수집
- [ ] Main Thread 실행 여부 확인
- [ ] 계산 sample이 집중된 함수 확인
- [ ] cancellation 가설 유지 또는 기각
- [ ] 첫 번째 병목 가설을 코드 위치와 연결해 문서화
- [ ] 변경할 코드 하나와 변경하지 않을 대안 기록
