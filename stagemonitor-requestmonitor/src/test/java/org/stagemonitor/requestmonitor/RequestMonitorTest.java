package org.stagemonitor.requestmonitor;

import com.codahale.metrics.Meter;
import com.uber.jaeger.Span;
import com.uber.jaeger.context.TracingUtils;
import com.uber.jaeger.samplers.ConstSampler;

import org.junit.Ignore;
import org.junit.Test;
import org.mockito.Mockito;
import org.stagemonitor.core.Stagemonitor;
import org.stagemonitor.requestmonitor.tracing.jaeger.LoggingSpanReporter;
import org.stagemonitor.requestmonitor.tracing.wrapper.SpanEventListener;
import org.stagemonitor.requestmonitor.tracing.wrapper.SpanWrapper;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import io.opentracing.Tracer;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.stagemonitor.core.metrics.metrics2.MetricName.name;

public class RequestMonitorTest extends AbstractRequestMonitorTest {

	protected Tracer getTracer() {
		return new com.uber.jaeger.Tracer.Builder("RequestMonitorTest",
				new LoggingSpanReporter(requestMonitorPlugin), new ConstSampler(true)).build();
	}

	@Test
	public void testDeactivated() throws Exception {
		doReturn(false).when(corePlugin).isStagemonitorActive();

		final SpanContextInformation spanContext = requestMonitor.monitor(createMonitoredRequest());

		assertNull(spanContext.getSpan());
	}

	@Test
	public void testRecordException() throws Exception {
		final MonitoredRequest monitoredRequest = createMonitoredRequest();
		doThrow(new RuntimeException("test")).when(monitoredRequest).execute();
		try {
			requestMonitor.monitor(monitoredRequest);
		} catch (Exception e) {
		}
		assertEquals("java.lang.RuntimeException", tags.get("exception.class"));
		assertEquals("test", tags.get("exception.message"));
		assertNotNull(tags.get("exception.stack_trace"));
	}

	@Test
	public void testInternalMetricsDeactive() throws Exception {
		internalMonitoringTestHelper(false);
	}

	@Test
	public void testInternalMetricsActive() throws Exception {
		doReturn(true).when(corePlugin).isInternalMonitoringActive();

		requestMonitor.monitor(createMonitoredRequest());
		verify(registry, times(1)).timer(name("internal_overhead_request_monitor").build());
	}

	private void internalMonitoringTestHelper(boolean active) throws Exception {
		doReturn(active).when(corePlugin).isInternalMonitoringActive();
		requestMonitor.monitor(createMonitoredRequest());
		verify(registry, times(active ? 1 : 0)).timer(name("internal_overhead_request_monitor").build());
	}

	private MonitoredRequest createMonitoredRequest() throws Exception {
		return Mockito.spy(new MonitoredMethodRequest(configuration, "test", () -> {
		}));
	}

	@Test
	public void testProfileThisExecutionDeactive() throws Exception {
		doReturn(0d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		final SpanContextInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		assertNull(monitor.getCallTree());
	}

	@Test
	public void testProfileThisExecutionAlwaysActive() throws Exception {
		doReturn(1000000d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		final SpanContextInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		assertNotNull(monitor.getCallTree());
	}

	@Test
	public void testDontActivateProfilerWhenNoSpanReporterIsActive() throws Exception {
		// don't profile if no one is interested in the result
		tracer.addSpanInterceptor(() -> new SpanEventListener() {
			@Override
			public void onStart(SpanWrapper spanWrapper) {
				requestMonitor.getSpanContext().setReport(false);
			}
		});
		doReturn(1000000d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		doReturn(false).when(requestMonitorPlugin).isLogSpans();
		final SpanContextInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		assertNull(monitor.getCallTree());
	}

	@Test
	public void testProfileThisExecutionActiveEvery2Requests() throws Exception {
		doReturn(2d).when(requestMonitorPlugin).getOnlyCollectNCallTreesPerMinute();
		testProfileThisExecutionHelper(0, true);
		testProfileThisExecutionHelper(1.99, true);
		testProfileThisExecutionHelper(2, false);
		testProfileThisExecutionHelper(3, false);
		testProfileThisExecutionHelper(1, true);
	}

	private void testProfileThisExecutionHelper(double callTreeRate, boolean callStackExpected) throws Exception {
		final Meter callTreeMeter = mock(Meter.class);
		doReturn(callTreeRate).when(callTreeMeter).getOneMinuteRate();
		requestMonitor.setCallTreeMeter(callTreeMeter);

		final SpanContextInformation monitor = requestMonitor.monitor(createMonitoredRequest());
		if (callStackExpected) {
			assertNotNull(monitor.getCallTree());
		} else {
			assertNull(monitor.getCallTree());
		}
	}

	@Test
	@Ignore
	public void testGetInstanceNameFromExecution() throws Exception {
		final MonitoredRequest monitoredRequest = createMonitoredRequest();
		doReturn("testInstance").when(monitoredRequest).getInstanceName();
		requestMonitor.monitor(monitoredRequest);
		assertEquals("testInstance", Stagemonitor.getMeasurementSession().getInstanceName());
	}

	@Test
	public void testExecutorServiceContextPropagation() throws Exception {
		SpanCapturingReporter spanCapturingReporter = new SpanCapturingReporter(requestMonitor);

		final ExecutorService executorService = TracingUtils.tracedExecutor(Executors.newSingleThreadExecutor());
		final SpanContextInformation[] asyncSpan = new SpanContextInformation[1];

		final Future<?>[] asyncMethodCallFuture = new Future<?>[1];
		final SpanContextInformation testInfo = requestMonitor.monitor(new MonitoredMethodRequest(configuration, "test", () -> {
			assertNotNull(TracingUtils.getTraceContext().getCurrentSpan());
			asyncMethodCallFuture[0] = monitorAsyncMethodCall(executorService, asyncSpan);
		}));
		executorService.shutdown();
		// waiting for completion
		spanCapturingReporter.get();
		spanCapturingReporter.get();
		asyncMethodCallFuture[0].get();
		assertEquals("test", testInfo.getOperationName());
		assertEquals("async", asyncSpan[0].getOperationName());

		assertEquals(((SpanWrapper) testInfo.getSpan()).unwrap(Span.class).context(), ((SpanWrapper) asyncSpan[0].getSpan()).unwrap(Span.class).context());
	}

	private Future<?> monitorAsyncMethodCall(ExecutorService executorService, final SpanContextInformation[] asyncSpan) {
		return executorService.submit((Callable<Object>) () ->
				asyncSpan[0] = requestMonitor.monitor(new MonitoredMethodRequest(configuration, "async", () -> {
					assertNotNull(TracingUtils.getTraceContext().getCurrentSpan());
					callAsyncMethod();
				})));
	}

	private Object callAsyncMethod() {
		return null;
	}

	@Test
	public void testDontMonitorClientRootSpans() throws Exception {
		when(requestMonitorPlugin.getRateLimitClientSpansPerMinute()).thenReturn(1_000_000.0);

		requestMonitor.monitorStart(new AbstractExternalRequest(requestMonitorPlugin) {
			@Override
			protected String getType() {
				return "jdbc";
			}
		});

		assertFalse(requestMonitor.getSpanContext().isReport());

		requestMonitor.monitorStop();
	}
}
