package com.example.loadbalancer.resilience;

/**
 * Circuit breaker states.
 *
 * <pre>
 *                 failure rate >= threshold
 *      CLOSED ───────────────────────────────▶ OPEN
 *        ▲                                      │
 *        │ successThreshold consecutive         │ openDuration elapsed
 *        │ probe successes                      ▼
 *        └───────────────────────────────── HALF_OPEN
 *                          ▲                    │
 *                          └────────────────────┘
 *                            any probe failure
 *                            (back to OPEN)
 * </pre>
 */
public enum CircuitState {

    /** Normal operation; calls pass through and outcomes are recorded. */
    CLOSED,

    /** Backend is presumed broken; calls are rejected without being attempted. */
    OPEN,

    /** Cooldown elapsed; a limited number of probe calls are allowed through. */
    HALF_OPEN
}
