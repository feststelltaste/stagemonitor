package org.stagemonitor.jdbc.p6spy;

import com.codahale.metrics.MetricRegistry;
import com.p6spy.engine.logging.Category;
import com.p6spy.engine.spy.appender.P6Logger;
import org.stagemonitor.core.Configuration;
import org.stagemonitor.core.StageMonitor;
import org.stagemonitor.requestmonitor.RequestMonitor;
import org.stagemonitor.requestmonitor.RequestTrace;
import org.stagemonitor.requestmonitor.profiler.CallStackElement;
import org.stagemonitor.requestmonitor.profiler.Profiler;

import java.util.concurrent.TimeUnit;

import static com.codahale.metrics.MetricRegistry.name;

public class StagemonitorP6Logger implements P6Logger {

	private final Configuration configuration = StageMonitor.getConfiguration();
	private final MetricRegistry metricRegistry = StageMonitor.getMetricRegistry();

	@Override
	public void logSQL(int connectionId, String now, long elapsed, Category category, String prepared, String sql) {
		if (sql != null && !sql.isEmpty()) {
			RequestTrace request = RequestMonitor.getRequest();
			if (request != null) {
				request.dbCallCompleted(elapsed);
				CallStackElement currentCall = Profiler.getMethodCallParent();
				metricRegistry.timer("db._all.time.statement").update(elapsed, TimeUnit.MILLISECONDS);
				if (currentCall != null) {
					metricRegistry
							.timer(name("db", currentCall.getShortSignature(), "time.statement"))
							.update(elapsed, TimeUnit.MILLISECONDS);
				}

				if (configuration.collectPreparedStatementParameters()) {
					Profiler.addCall(sql, TimeUnit.MILLISECONDS.toNanos(elapsed));
				} else {
					Profiler.addCall(prepared, TimeUnit.MILLISECONDS.toNanos(elapsed));
				}
			}
		}
	}

	@Override
	public void logException(Exception e) {
	}

	@Override
	public void logText(String text) {
	}

	@Override
	public boolean isCategoryEnabled(Category category) {
		return Category.STATEMENT.equals(category);
	}
}
