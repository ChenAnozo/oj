package com.yupi.yuojbackendquestionservice.diagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
/** Compact phase timings, without code, test cases or credentials. */
public final class SubmissionTiming implements AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(SubmissionTiming.class);
    private final String scope;
    private final long questionId;
    private final long started = System.nanoTime();
    private final Map<String, Long> stagesMs = new LinkedHashMap<>();
    private long stageStarted = started;
    private String stage = "setup";
    private Long submissionId;
    private boolean success;
    public SubmissionTiming(String scope, long questionId) {
        this.scope = scope;
        this.questionId = questionId;
    }
    public void stage(String next) {
        long now = System.nanoTime();
        stagesMs.put(stage, TimeUnit.NANOSECONDS.toMillis(now - stageStarted));
        stage = next;
        stageStarted = now;
    }
    public void submissionId(Long id) { submissionId = id; }
    public void success() { success = true; }
    @Override public void close() {
        long now = System.nanoTime();
        stagesMs.put(stage, TimeUnit.NANOSECONDS.toMillis(now - stageStarted));
        LOG.info("SUBMIT_TIMING scope={} questionId={} submissionId={} success={} lastStage={} totalMs={} stagesMs={}",
                scope, questionId, submissionId, success, stage,
                TimeUnit.NANOSECONDS.toMillis(now - started), stagesMs);
    }
}
