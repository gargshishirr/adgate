/**
 * A simplified count-based circuit breaker with the settings of resilience4j.circuitbreaker.instances.redis in
 * application.yml. It is a simulation of Resilience4j's behaviour, not a port of it.
 */

export type BreakerState = 'CLOSED' | 'OPEN' | 'HALF_OPEN';

export interface BreakerConfig {
  readonly slidingWindowSize: number;
  readonly minimumNumberOfCalls: number;
  readonly failureRateThreshold: number;
  readonly slowCallDurationThresholdMillis: number;
  readonly slowCallRateThreshold: number;
  readonly waitDurationInOpenStateMillis: number;
  readonly permittedCallsInHalfOpenState: number;
}

export const REDIS_BREAKER: BreakerConfig = {
  slidingWindowSize: 50,
  minimumNumberOfCalls: 20,
  failureRateThreshold: 50,
  slowCallDurationThresholdMillis: 30,
  slowCallRateThreshold: 80,
  waitDurationInOpenStateMillis: 2_000,
  permittedCallsInHalfOpenState: 5,
};

interface Outcome {
  readonly failed: boolean;
  readonly slow: boolean;
}

export class CircuitBreaker {
  private current: BreakerState = 'CLOSED';
  private outcomes: Outcome[] = [];
  private openedAt = 0;
  private halfOpenPermits = 0;

  constructor(private readonly config: BreakerConfig = REDIS_BREAKER) {}

  state(now: number): BreakerState {
    if (this.current === 'OPEN' && now - this.openedAt >= this.config.waitDurationInOpenStateMillis) {
      this.current = 'HALF_OPEN';
      this.outcomes = [];
      this.halfOpenPermits = this.config.permittedCallsInHalfOpenState;
    }
    return this.current;
  }

  /** False means the call is not permitted (CallNotPermittedException). */
  tryAcquire(now: number): boolean {
    const state = this.state(now);
    if (state === 'OPEN') {
      return false;
    }
    if (state === 'HALF_OPEN') {
      if (this.halfOpenPermits <= 0) {
        return false;
      }
      this.halfOpenPermits--;
    }
    return true;
  }

  record(now: number, failed: boolean, durationMillis: number): void {
    const slow = durationMillis > this.config.slowCallDurationThresholdMillis;
    this.outcomes.push({ failed, slow });
    if (this.current === 'HALF_OPEN') {
      if (this.outcomes.length >= this.config.permittedCallsInHalfOpenState) {
        if (this.exceedsThresholds()) {
          this.open(now);
        } else {
          this.current = 'CLOSED';
          this.outcomes = [];
        }
      }
      return;
    }
    if (this.outcomes.length > this.config.slidingWindowSize) {
      this.outcomes.shift();
    }
    if (this.outcomes.length >= this.config.minimumNumberOfCalls && this.exceedsThresholds()) {
      this.open(now);
    }
  }

  reset(): void {
    this.current = 'CLOSED';
    this.outcomes = [];
  }

  private exceedsThresholds(): boolean {
    const total = this.outcomes.length;
    const failures = this.outcomes.filter((outcome) => outcome.failed).length;
    const slow = this.outcomes.filter((outcome) => outcome.slow).length;
    return (failures * 100) / total >= this.config.failureRateThreshold || (slow * 100) / total >= this.config.slowCallRateThreshold;
  }

  private open(now: number): void {
    this.current = 'OPEN';
    this.openedAt = now;
    this.outcomes = [];
  }
}
