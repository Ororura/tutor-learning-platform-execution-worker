package com.tutorplatform.worker.application;

import com.tutorplatform.worker.config.ExecutionWorkerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@Service
public class ExecutionService {
    private static final Logger log = LoggerFactory.getLogger(ExecutionService.class);

    private final SandboxRuntime sandboxRuntime;
    private final OutputComparator outputComparator;
    private final ExecutionWorkerProperties properties;
    private final Semaphore concurrency;

    public ExecutionService(
        SandboxRuntime sandboxRuntime,
        OutputComparator outputComparator,
        ExecutionWorkerProperties properties
    ) {
        this.sandboxRuntime = sandboxRuntime;
        this.outputComparator = outputComparator;
        this.properties = properties;
        concurrency = new Semaphore(properties.worker().maxConcurrent(), true);
    }

    public ExecutionOutcome execute(ExecutionCommand command) {
        var startedAt = System.nanoTime();
        log.info(
            "Execution started: executionId={}, language={}",
            command.executionId(), command.language()
        );

        if (!acquireSlot(command)) {
            return finish(command, startedAt, systemError(command));
        }

        try {
            return finish(command, startedAt, executeTests(command));
        } catch (RuntimeException exception) {
            log.error(
                "Execution infrastructure failure: executionId={}, failureType={}",
                command.executionId(), exception.getClass().getSimpleName()
            );
            return finish(command, startedAt, systemError(command));
        } finally {
            concurrency.release();
        }
    }

    private ExecutionOutcome executeTests(ExecutionCommand command) {
        var results = new ArrayList<ExecutionOutcome.TestResult>();
        var stdout = new BoundedTextAccumulator(effectiveOutputLimit(command));
        var stderr = new BoundedTextAccumulator(effectiveOutputLimit(command));
        var passed = 0;
        var totalDuration = 0L;

        for (var testCase : command.testCases()) {
            var sandboxResult = bounded(sandboxRuntime.execute(new SandboxRequest(
                command.executionId(),
                command.language(),
                command.sourceCode(),
                testCase.inputText(),
                command.timeLimitMs(),
                command.memoryLimitMb(),
                effectiveOutputLimit(command)
            )), effectiveOutputLimit(command));
            totalDuration += sandboxResult.executionTimeMs();
            stdout.append(sandboxResult.stdoutExcerpt());
            stderr.append(sandboxResult.stderrExcerpt());

            if (sandboxResult.status() != SandboxResult.Status.COMPLETED) {
                results.add(testResult(testCase, false, sandboxResult));
                return outcome(
                    command,
                    mapStatus(sandboxResult.status()),
                    passed,
                    totalDuration,
                    stdout,
                    stderr,
                    results
                );
            }

            var testPassed = !sandboxResult.stdoutTruncated() && outputComparator.matches(
                testCase.expectedOutput(),
                sandboxResult.stdoutExcerpt() == null ? "" : sandboxResult.stdoutExcerpt(),
                testCase.comparisonMode()
            );
            if (testPassed) {
                passed++;
            }
            results.add(testResult(testCase, testPassed, sandboxResult));
        }

        var status = passed == command.testCases().size() ? ExecutionStatus.PASSED : ExecutionStatus.FAILED;
        return outcome(command, status, passed, totalDuration, stdout, stderr, results);
    }

    private boolean acquireSlot(ExecutionCommand command) {
        try {
            var acquired = concurrency.tryAcquire(
                properties.worker().queueTimeout().toMillis(), TimeUnit.MILLISECONDS
            );
            if (!acquired) {
                log.warn("Execution concurrency limit reached: executionId={}", command.executionId());
            }
            return acquired;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            log.warn("Execution interrupted while waiting for capacity: executionId={}", command.executionId());
            return false;
        }
    }

    private int effectiveOutputLimit(ExecutionCommand command) {
        return Math.min(command.outputLimitBytes(), properties.runtime().outputMaxBytes());
    }

    private static SandboxResult bounded(SandboxResult result, int maxBytes) {
        if (result == null) {
            return new SandboxResult(SandboxResult.Status.SYSTEM_ERROR, 0, null, null, false, false);
        }
        return new SandboxResult(
            result.status(),
            result.executionTimeMs(),
            BoundedTextAccumulator.truncate(result.stdoutExcerpt(), maxBytes),
            BoundedTextAccumulator.truncate(result.stderrExcerpt(), maxBytes),
            result.stdoutTruncated(),
            result.stderrTruncated()
        );
    }

    private static ExecutionOutcome.TestResult testResult(
        ExecutionCommand.TestCase testCase,
        boolean passed,
        SandboxResult sandboxResult
    ) {
        return new ExecutionOutcome.TestResult(
            testCase.id(),
            passed,
            sandboxResult.executionTimeMs(),
            sandboxResult.stdoutExcerpt(),
            sandboxResult.stderrExcerpt()
        );
    }

    private static ExecutionStatus mapStatus(SandboxResult.Status status) {
        return switch (status) {
            case TIMEOUT -> ExecutionStatus.TIMEOUT;
            case RUNTIME_ERROR -> ExecutionStatus.RUNTIME_ERROR;
            case SYSTEM_ERROR -> ExecutionStatus.SYSTEM_ERROR;
            case COMPLETED -> throw new IllegalArgumentException("Completed status cannot be mapped as a failure");
        };
    }

    private static ExecutionOutcome outcome(
        ExecutionCommand command,
        ExecutionStatus status,
        int passed,
        long duration,
        BoundedTextAccumulator stdout,
        BoundedTextAccumulator stderr,
        ArrayList<ExecutionOutcome.TestResult> results
    ) {
        return new ExecutionOutcome(
            command.executionId(),
            status,
            passed,
            command.testCases().size(),
            duration,
            stdout.valueOrNull(),
            stderr.valueOrNull(),
            List.copyOf(results)
        );
    }

    private static ExecutionOutcome systemError(ExecutionCommand command) {
        return new ExecutionOutcome(
            command.executionId(),
            ExecutionStatus.SYSTEM_ERROR,
            0,
            command.testCases().size(),
            0,
            null,
            null,
            java.util.List.of()
        );
    }

    private static ExecutionOutcome finish(
        ExecutionCommand command,
        long startedAt,
        ExecutionOutcome outcome
    ) {
        log.info(
            "Execution completed: executionId={}, language={}, durationMs={}, status={}",
            command.executionId(),
            command.language(),
            Duration.ofNanos(System.nanoTime() - startedAt).toMillis(),
            outcome.status()
        );
        return outcome;
    }
}
