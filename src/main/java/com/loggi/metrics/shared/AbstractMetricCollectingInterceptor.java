/*
 * Copyright (c) 2016-2019 Michael Zhang <yidongnan@gmail.com>
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the
 * Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE
 * WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR
 * COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
 * OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.loggi.metrics.shared;

import io.grpc.MethodDescriptor;
import io.grpc.ServiceDescriptor;
import io.grpc.Status.Code;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import static com.loggi.metrics.shared.MetricConstants.TAG_STATUS_CODE;


/**
 * An abstract gRPC interceptor that will collect metrics for micrometer.
 *
 * @author Daniel Theuke (daniel.theuke@heuboe.de)
 */
@Slf4j
public abstract class AbstractMetricCollectingInterceptor {

    private final Map<MethodDescriptor<?, ?>, MetricSet> metricsForMethods = new ConcurrentHashMap<>();

    protected final MeterRegistry registry;

    protected final UnaryOperator<Counter.Builder> counterCustomizer;
    protected final UnaryOperator<Timer.Builder> timerCustomizer;
    protected final Code[] eagerInitializedCodes;

    /**
     * Creates a new gRPC interceptor that will collect metrics into the given {@link MeterRegistry}. This method won't
     * use any customizers and will only initialize the {@link Code#OK OK} status.
     *
     * @param registry The registry to use.
     */
    public AbstractMetricCollectingInterceptor(final MeterRegistry registry) {
        this(registry, UnaryOperator.identity(), UnaryOperator.identity(), Code.OK);
    }

    /**
     * Creates a new gRPC interceptor that will collect metrics into the given {@link MeterRegistry} and uses the given
     * customizer to configure the {@link Counter}s and {@link Timer}s.
     *
     * @param registry              The registry to use.
     * @param counterCustomizer     The unary function that can be used to customize the created counters.
     * @param timerCustomizer       The unary function that can be used to customize the created timers.
     * @param eagerInitializedCodes The status codes that should be eager initialized.
     */
    public AbstractMetricCollectingInterceptor(final MeterRegistry registry,
                                               final UnaryOperator<Counter.Builder> counterCustomizer,
                                               final UnaryOperator<Timer.Builder> timerCustomizer, final Code... eagerInitializedCodes) {
        this.registry = registry;
        this.counterCustomizer = counterCustomizer;
        this.timerCustomizer = timerCustomizer;
        this.eagerInitializedCodes = eagerInitializedCodes;
    }

    /**
     * Pre-registers the all methods provided by the given service. This will initialize all default counters and timers
     * for those methods.
     *
     * @param service The service to initialize the meters for.
     * @see #preregisterMethod(MethodDescriptor)
     */
    public void preregisterService(final ServiceDescriptor service) {
        for (final MethodDescriptor<?, ?> method : service.getMethods()) {
            preregisterMethod(method);
        }
    }

    /**
     * Pre-registers the given method. This will initialize all default counters and timers for that method.
     *
     * @param method The method to initialize the meters for.
     */
    public void preregisterMethod(final MethodDescriptor<?, ?> method) {
        metricsFor(method);
    }

    /**
     * Gets or creates a {@link MetricSet} for the given gRPC method. This will initialize all default counters and
     * timers for that method.
     *
     * @param method The method to get the metric set for.
     * @return The metric set for the given method.
     * @see #newMetricsFor(MethodDescriptor)
     */
    protected final MetricSet metricsFor(final MethodDescriptor<?, ?> method) {
        return this.metricsForMethods.computeIfAbsent(method, this::newMetricsFor);
    }

    /**
     * Creates a {@link MetricSet} for the given gRPC method. This will initialize all default counters and timers for
     * that method.
     *
     * @param method The method to get the metric set for.
     * @return The newly created metric set for the given method.
     */
    protected MetricSet newMetricsFor(final MethodDescriptor<?, ?> method) {
        log.debug("Creating new metrics for {}", method.getFullMethodName());
        return new MetricSet(newRequestCounterFor(method), newResponseCounterFor(method), newTimerFunction(method));
    }

    /**
     * Creates a new request counter for the given method.
     *
     * @param method The method to create the counter for.
     * @return The newly created request counter.
     */
    protected abstract Counter newRequestCounterFor(final MethodDescriptor<?, ?> method);

    /**
     * Creates a new response counter for the given method.
     *
     * @param method The method to create the counter for.
     * @return The newly created response counter.
     */
    protected abstract Counter newResponseCounterFor(final MethodDescriptor<?, ?> method);

    /**
     * Creates a new timer function using the given template. This method initializes the default timers.
     *
     * @param timerTemplate The template to create the instances from.
     * @return The newly created function that returns a timer for a given code.
     */
    protected Function<Code, Timer> asTimerFunction(final Supplier<Timer.Builder> timerTemplate) {
        final Map<Code, Timer> cache = new EnumMap<>(Code.class);
        final Function<Code, Timer> creator = code -> timerTemplate.get()
                .tag(TAG_STATUS_CODE, code.name())
                .register(this.registry);
        final Function<Code, Timer> cacheResolver = code -> cache.computeIfAbsent(code, creator);
        // Eager initialize
        for (final Code code : this.eagerInitializedCodes) {
            cacheResolver.apply(code);
        }
        return cacheResolver;
    }

    /**
     * Creates a new timer for a given code for the given method.
     *
     * @param method The method to create the timer for.
     * @return The newly created function that returns a timer for a given code.
     */
    protected abstract Function<Code, Timer> newTimerFunction(final MethodDescriptor<?, ?> method);

    /**
     * Container for all metrics of a certain call. Used instead of 3 maps to improve performance.
     */
    @Getter
    protected static class MetricSet {

        private final Counter requestCounter;
        private final Counter responseCounter;
        private final Function<Code, Timer> timerFunction;

        /**
         * Creates a new metric set with the given meter instances.
         *
         * @param requestCounter  The request counter to use.
         * @param responseCounter The response counter to use.
         * @param timerFunction   The timer function to use.
         */
        public MetricSet(final Counter requestCounter, final Counter responseCounter,
                         final Function<Code, Timer> timerFunction) {
            this.requestCounter = requestCounter;
            this.responseCounter = responseCounter;
            this.timerFunction = timerFunction;
        }

    }
}
