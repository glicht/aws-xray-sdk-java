package com.amazonaws.xray;

import com.amazonaws.xray.contexts.LambdaSegmentContext;
import com.amazonaws.xray.contexts.LambdaSegmentContextResolver;
import com.amazonaws.xray.emitters.Emitter;
import com.amazonaws.xray.emitters.UDPEmitter;
import com.amazonaws.xray.entities.Segment;
import com.amazonaws.xray.entities.Subsegment;
import com.amazonaws.xray.entities.TraceHeader;
import com.amazonaws.xray.entities.TraceID;
import com.amazonaws.xray.exceptions.AlreadyEmittedException;
import com.amazonaws.xray.exceptions.SegmentNotFoundException;
import com.amazonaws.xray.strategy.ContextMissingStrategy;
import com.amazonaws.xray.strategy.LogErrorContextMissingStrategy;
import com.amazonaws.xray.strategy.RuntimeErrorContextMissingStrategy;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.json.JSONException;
import org.junit.*;
import org.junit.contrib.java.lang.system.EnvironmentVariables;
import org.junit.contrib.java.lang.system.RestoreSystemProperties;
import org.junit.runner.RunWith;
import org.junit.runners.MethodSorters;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.skyscreamer.jsonassert.JSONAssert;
import org.skyscreamer.jsonassert.JSONCompareMode;

import java.util.List;

@FixMethodOrder(MethodSorters.JVM)
@PrepareForTest({LambdaSegmentContext.class, LambdaSegmentContextResolver.class})
@RunWith(PowerMockRunner.class)
public class AWSXRayRecorderTest {

    @Rule
    public EnvironmentVariables environmentVariables = new EnvironmentVariables();
    @Rule
    public RestoreSystemProperties restoreSystemProperties = new RestoreSystemProperties();

    @Before
    public void setupAWSXRay() {
        Emitter blankEmitter = Mockito.mock(Emitter.class);
        Mockito.doReturn(true).when(blankEmitter).sendSegment(Mockito.anyObject());
        Mockito.doReturn(true).when(blankEmitter).sendSubsegment(Mockito.anyObject());
        AWSXRay.setGlobalRecorder(AWSXRayRecorderBuilder.standard().withEmitter(blankEmitter).build());
        AWSXRay.clearTraceEntity();
    }

    @Test
    public void testGetThreadLocalReturnsCurrentSegment() {
        Segment segment = AWSXRay.beginSegment("test");
        Assert.assertEquals(segment, AWSXRay.getThreadLocal());
        AWSXRay.endSegment();
    }

    @Test
    public void testGetTraceEntityReturnsCurrentSegment() {
        Segment segment = AWSXRay.beginSegment("test");
        Assert.assertEquals(segment, AWSXRay.getTraceEntity());
        AWSXRay.endSegment();
    }

    @Test
    public void testGetThreadLocalReturnsCurrentSubsegment() {
        AWSXRay.beginSegment("test");
        Subsegment subsegment = AWSXRay.beginSubsegment("test");
        Assert.assertEquals(subsegment, AWSXRay.getThreadLocal());
        AWSXRay.endSubsegment();
        AWSXRay.endSegment();
    }

    @Test
    public void testGetTraceEntityReturnsCurrentSubsegment() {
        AWSXRay.beginSegment("test");
        Subsegment subsegment = AWSXRay.beginSubsegment("test");
        Assert.assertEquals(subsegment, AWSXRay.getTraceEntity());
        AWSXRay.endSubsegment();
        AWSXRay.endSegment();
    }

    @Test
    public void testGetThreadLocalOnEmptyThreadDoesNotThrowException() {
        AWSXRay.beginSegment("test");
        AWSXRay.endSegment();
        AWSXRay.getThreadLocal();
    }

    @Test
    public void testGetTraceEntityOnEmptyThreadDoesNotThrowException() {
        AWSXRay.beginSegment("test");
        AWSXRay.endSegment();
        AWSXRay.getTraceEntity();
    }

    @Test(expected=SegmentNotFoundException.class)
    public void testBeginSubsegmentOnEmptyThreadThrowsExceptionByDefault() {
        AWSXRay.beginSubsegment("test");
    }

    @Test
    public void testBeginSubsegmentOnEmptyThreadDoesNotThrowExceptionWithLogErrorContextMissingStrategy() {
        AWSXRay.getGlobalRecorder().setContextMissingStrategy(new LogErrorContextMissingStrategy());
        AWSXRay.beginSubsegment("test");
    }

    @Test
    public void testInjectThreadLocalInjectsCurrentSegment() {
        Segment segment = AWSXRay.beginSegment("test");

        Thread thread = new Thread() {
            public void run() {
                AWSXRay.injectThreadLocal(segment);
                Assert.assertEquals(segment, AWSXRay.getThreadLocal());
            }
        };
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ie) {
        }

        AWSXRay.endSegment();
    }

    @Test
    public void testSetTraceEntityInjectsCurrentSegment() {
        Segment segment = AWSXRay.beginSegment("test");

        Thread thread = new Thread() {
            public void run() {
                AWSXRay.setTraceEntity(segment);
                Assert.assertEquals(segment, AWSXRay.getTraceEntity());
            }
        };
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ie) {
        }

        AWSXRay.endSegment();
    }

    @Test
    public void testInjectThreadLocalInjectsCurrentSubsegment() {
        AWSXRay.beginSegment("test");
        Subsegment subsegment = AWSXRay.beginSubsegment("test");

        Thread thread = new Thread() {
            public void run() {
                AWSXRay.injectThreadLocal(subsegment);
                Assert.assertEquals(subsegment, AWSXRay.getThreadLocal());
            }
        };
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ie) {
        }

        AWSXRay.endSubsegment();
        AWSXRay.endSegment();
    }

    @Test
    public void testSetTraceEntityInjectsCurrentSubsegment() {
        AWSXRay.beginSegment("test");
        Subsegment subsegment = AWSXRay.beginSubsegment("test");

        Thread thread = new Thread() {
            public void run() {
                AWSXRay.setTraceEntity(subsegment);
                Assert.assertEquals(subsegment, AWSXRay.getThreadLocal());
            }
        };
        thread.start();
        try {
            thread.join();
        } catch (InterruptedException ie) {
        }

        AWSXRay.endSubsegment();
        AWSXRay.endSegment();
    }

    @Test
    public void testIsCurrentSegmentPresent() {
        Assert.assertFalse(AWSXRay.getCurrentSegmentOptional().isPresent());
        AWSXRay.beginSegment("test");
        Assert.assertTrue(AWSXRay.getCurrentSegmentOptional().isPresent());
        AWSXRay.endSegment();
        Assert.assertFalse(AWSXRay.getCurrentSegmentOptional().isPresent());
    }
    
    @Test
    public void testIsCurrentSubsegmentPresent() {
        Assert.assertFalse(AWSXRay.getCurrentSubsegmentOptional().isPresent());

        AWSXRay.beginSegment("test");
        Assert.assertFalse(AWSXRay.getCurrentSubsegmentOptional().isPresent());

        AWSXRay.beginSubsegment("test");
        Assert.assertTrue(AWSXRay.getCurrentSubsegmentOptional().isPresent());

        AWSXRay.endSubsegment();
        Assert.assertFalse(AWSXRay.getCurrentSubsegmentOptional().isPresent());

        AWSXRay.endSegment();
        Assert.assertFalse(AWSXRay.getCurrentSubsegmentOptional().isPresent());
    }

    @Test(expected=SegmentNotFoundException.class)
    public void testSubsegmentBeginWithoutSegmentContextThrowsException() {
        AWSXRay.beginSubsegment("test");
    }

    @Test
    public void testNotSendingUnsampledSegment() {
        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();

        Segment segment = recorder.beginSegment("test");
        segment.setSampled(false);
        recorder.endSegment();

        Mockito.verify(mockEmitter, Mockito.times(0)).sendSegment(Mockito.any());
    }

    @Test
    public void testSegmentEmitted() {
        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();

        recorder.beginSegment("test");
        recorder.beginSubsegment("test");
        recorder.endSubsegment();
        recorder.endSegment();

        Mockito.verify(mockEmitter, Mockito.times(1)).sendSegment(Mockito.any());
    }

    @Test
    public void testDummySegmentNotEmitted() {
        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();

        recorder.beginDummySegment();
        recorder.beginSubsegment("test");
        recorder.endSubsegment();
        recorder.endSegment();

        Mockito.verify(mockEmitter, Mockito.times(0)).sendSegment(Mockito.any());
    }

    private static final String TRACE_HEADER = "Root=1-57ff426a-80c11c39b0c928905eb0828d;Parent=1234abcd1234abcd;Sampled=1";
    @Test
    public void testSubsegmentEmittedInLambdaContext() throws JSONException {
        TraceHeader header = TraceHeader.fromString(TRACE_HEADER);

        PowerMockito.stub(PowerMockito.method(LambdaSegmentContext.class, "getTraceHeaderFromEnvironment")).toReturn(header);
        PowerMockito.stub(PowerMockito.method(LambdaSegmentContextResolver.class, "getLambdaTaskRoot")).toReturn("/var/task");

        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();
        recorder.createSubsegment("test", () -> {});

        ArgumentCaptor<Subsegment> emittedSubsegment = ArgumentCaptor.forClass(Subsegment.class);
        Mockito.verify(mockEmitter, Mockito.times(1)).sendSubsegment(emittedSubsegment.capture());

        Subsegment captured = emittedSubsegment.getValue();

        JSONAssert.assertEquals(expectedLambdaSubsegment(header.getRootTraceId(), header.getParentId(), captured.getId(), captured.getStartTime(), captured.getEndTime()).toString(), captured.streamSerialize(), JSONCompareMode.NON_EXTENSIBLE);
    }

    private ObjectNode expectedLambdaSubsegment(TraceID traceId, String segmentId, String subsegmentId, double startTime, double endTime) {
        ObjectNode expected = JsonNodeFactory.instance.objectNode();
        expected.put("name", "test");
        expected.put("type", "subsegment");
        expected.put("start_time", startTime);
        expected.put("end_time", endTime);
        expected.put("trace_id", traceId.toString());
        expected.put("parent_id", segmentId);
        expected.put("id", subsegmentId);
        return expected;
    }

    @Test
    public void testSubsegmentNotEmittedWithoutExceptionInLambdaInitContext() {
        PowerMockito.stub(PowerMockito.method(LambdaSegmentContext.class, "getTraceHeaderFromEnvironment")).toReturn(TraceHeader.fromString(null));
        PowerMockito.stub(PowerMockito.method(LambdaSegmentContextResolver.class, "getLambdaTaskRoot")).toReturn("/var/task");

        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();
        recorder.createSubsegment("test", () -> {});

        Mockito.verify(mockEmitter, Mockito.times(0)).sendSubsegment(Mockito.any());
    }

    @Test
    public void testSubsegmentWithChildEmittedTogetherInLambdaContext() {
        TraceHeader header = TraceHeader.fromString(TRACE_HEADER);

        PowerMockito.stub(PowerMockito.method(LambdaSegmentContext.class, "getTraceHeaderFromEnvironment")).toReturn(header);
        PowerMockito.stub(PowerMockito.method(LambdaSegmentContextResolver.class, "getLambdaTaskRoot")).toReturn("/var/task");

        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();

        recorder.createSubsegment("testTogether", () -> {
            recorder.createSubsegment("testTogether2", () -> {
            });
        });

        ArgumentCaptor<Subsegment> emittedSubsegment = ArgumentCaptor.forClass(Subsegment.class);
        Mockito.verify(mockEmitter, Mockito.times(1)).sendSubsegment(emittedSubsegment.capture());

        Subsegment captured = emittedSubsegment.getValue();

        Assert.assertEquals(1, captured.getSubsegments().size());
    }

    @Test
    public void testSubsequentSubsegmentBranchesEmittedInLambdaContext() {
        TraceHeader header = TraceHeader.fromString(TRACE_HEADER);

        PowerMockito.stub(PowerMockito.method(LambdaSegmentContext.class, "getTraceHeaderFromEnvironment")).toReturn(header);
        PowerMockito.stub(PowerMockito.method(LambdaSegmentContextResolver.class, "getLambdaTaskRoot")).toReturn("/var/task");

        Emitter mockEmitter = Mockito.mock(UDPEmitter.class);
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withEmitter(mockEmitter).build();

        recorder.createSubsegment("testTogether", () -> {
            recorder.createSubsegment("testTogether2", () -> {
            });
        });

        recorder.createSubsegment("testTogether3", () -> {
            recorder.createSubsegment("testTogether4", () -> {
            });
        });

        ArgumentCaptor<Subsegment> emittedSubsegments = ArgumentCaptor.forClass(Subsegment.class);

        Mockito.verify(mockEmitter, Mockito.times(2)).sendSubsegment(emittedSubsegments.capture());

        List<Subsegment> captured = emittedSubsegments.getAllValues();

        captured.forEach( (capturedSubsegment) -> {
            Assert.assertEquals(1, capturedSubsegment.getSubsegments().size());
        });
    }

    @Test
    public void testContextMissingStrategyOverrideEnvironmentVariable() {
        environmentVariables.set(ContextMissingStrategy.CONTEXT_MISSING_STRATEGY_ENVIRONMENT_VARIABLE_OVERRIDE_KEY, "log_error");
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withContextMissingStrategy(new RuntimeErrorContextMissingStrategy()).build();
        Assert.assertTrue(recorder.getContextMissingStrategy() instanceof LogErrorContextMissingStrategy);
        environmentVariables.set(ContextMissingStrategy.CONTEXT_MISSING_STRATEGY_ENVIRONMENT_VARIABLE_OVERRIDE_KEY, null);
    }

    @Test
    public void testContextMissingStrategyOverrideSystemProperty() {
        System.setProperty(ContextMissingStrategy.CONTEXT_MISSING_STRATEGY_SYSTEM_PROPERTY_OVERRIDE_KEY, "log_error");
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withContextMissingStrategy(new RuntimeErrorContextMissingStrategy()).build();
        Assert.assertTrue(recorder.getContextMissingStrategy() instanceof LogErrorContextMissingStrategy);
    }

    @Test
    public void testContextMissingStrategyOverrideEnvironmentVariableOverridesSystemProperty() {
        environmentVariables.set(ContextMissingStrategy.CONTEXT_MISSING_STRATEGY_ENVIRONMENT_VARIABLE_OVERRIDE_KEY, "log_error");
        System.setProperty(ContextMissingStrategy.CONTEXT_MISSING_STRATEGY_SYSTEM_PROPERTY_OVERRIDE_KEY, "runtime_error");
        AWSXRayRecorder recorder = AWSXRayRecorderBuilder.standard().withContextMissingStrategy(new RuntimeErrorContextMissingStrategy()).build();
        Assert.assertTrue(recorder.getContextMissingStrategy() instanceof LogErrorContextMissingStrategy);
        environmentVariables.set(ContextMissingStrategy.CONTEXT_MISSING_STRATEGY_ENVIRONMENT_VARIABLE_OVERRIDE_KEY, null);
    }

    @Test(expected=AlreadyEmittedException.class)
    public void testEmittingSegmentTwiceThrowsSegmentAlreadyEmittedException() {
        Segment s = AWSXRay.beginSegment("test");
        AWSXRay.endSegment();
        AWSXRay.injectThreadLocal(s);
        AWSXRay.endSegment();
    }
}
