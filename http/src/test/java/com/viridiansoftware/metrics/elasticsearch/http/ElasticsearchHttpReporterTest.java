/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2014 Thomas Cashman
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.viridiansoftware.metrics.elasticsearch.http;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.text.DecimalFormat;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.elasticsearch.action.admin.indices.delete.DeleteIndexRequestBuilder;
import org.elasticsearch.action.search.SearchRequestBuilder;
import org.elasticsearch.action.search.SearchResponse;
import org.elasticsearch.common.settings.ImmutableSettings;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.aggregations.Aggregation;
import org.elasticsearch.search.aggregations.AggregationBuilders;
import org.elasticsearch.search.aggregations.bucket.terms.InternalTerms;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Bucket;
import org.elasticsearch.search.aggregations.bucket.terms.Terms.Order;
import org.elasticsearch.test.ElasticsearchIntegrationTest;
import org.elasticsearch.test.ElasticsearchIntegrationTest.ClusterScope;
import org.elasticsearch.test.ElasticsearchIntegrationTest.Scope;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.codahale.metrics.Clock;
import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Timer;
import com.viridiansoftware.metrics.elasticsearch.ElasticsearchReporter;
import com.viridiansoftware.metrics.elasticsearch.MetricElasticsearchTypes;

/**
 * Integration tests for {@link ElasticsearchHttpReporter}
 */
@ClusterScope(scope = Scope.SUITE, numDataNodes = 1, numClientNodes = 1)
public class ElasticsearchHttpReporterTest extends ElasticsearchIntegrationTest {
    private final long timestamp = System.currentTimeMillis();
    private final Clock clock = mock(Clock.class);
    private final String prefix = "prefix";
    private final MetricRegistry registry = new MetricRegistry();

    private ElasticsearchReporter reporter;

    @Before
    public void setUp() throws Exception {
        reporter = ElasticsearchHttpReporter
                .forRegistryAndIndexPrefix(registry, "test-")
                .withClock(clock).prefixedWith(prefix)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .withBulkRequestLimit(10).filter(MetricFilter.ALL)
                .build("localhost:9201");
        reporter.start(1, TimeUnit.SECONDS);
        when(clock.getTime()).thenReturn(timestamp);
        super.setUp();
    }

    @After
    public void teardown() throws Exception {
        reporter.stop();
        deleteIndices();
        super.tearDown();
    }

    @Override
    protected Settings nodeSettings(int nodeOrdinal) {
        return ImmutableSettings.settingsBuilder()
                .put("path.data", System.getProperty("java.io.tmpdir"))
                .put(super.nodeSettings(nodeOrdinal)).build();
    }

    @Test
    public void testReportsWithBulkRequestLimit() {
        DecimalFormat formatter = new DecimalFormat("00");
        String metricNamePrefix = "com.codahale.metrics.elasticsearch.test.counter";
        for (int i = 0; i < 15; i++) {
            Counter counter = registry.counter(metricNamePrefix
                    + formatter.format(i));
            counter.inc();
        }
        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = nestedAggregationWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all")
                        .setTypes(MetricElasticsearchTypes.COUNTER)
                        .setQuery(QueryBuilders.matchAllQuery())
                        .setSize(0)
                        .addAggregation(
                                AggregationBuilders
                                        .terms("timestamps")
                                        .size(50)
                                        .field("@timestamp")
                                        .order(Order.term(true))
                                        .subAggregation(
                                                AggregationBuilders
                                                        .terms("names")
                                                        .size(50)
                                                        .field("@name")
                                                        .order(Order.term(true)))),
                15);

        InternalTerms timestamps = searchResponse.getAggregations().get(
                "timestamps");
        Collection<Bucket> timestampBuckets = timestamps.getBuckets();
        Assert.assertEquals(true, timestampBuckets.size() > 0);
        for (Bucket bucket : timestampBuckets) {
            InternalTerms names = bucket.getAggregations().get("names");
            if(names.getBuckets().size() >= 15) {
                for (int i = 0; i < names.getBuckets().size(); i++) {
                    Assert.assertEquals(
                            prefix + "." + metricNamePrefix + formatter.format(i),
                            ((Bucket) names.getBuckets().toArray()[i]).getKey());
                }
                return;
            }
        }
        Assert.fail("Insufficient metrics reported");
    }

    @Test
    public void testReportsWithAlternateTimestampField() {
        reporter.stop();
        reporter = ElasticsearchHttpReporter
                .forRegistryAndIndexPrefix(registry, "test")
                .withTimestampFieldName("@timeywimey")
                .withClock(Clock.defaultClock()).prefixedWith(prefix)
                .convertRatesTo(TimeUnit.SECONDS)
                .convertDurationsTo(TimeUnit.MILLISECONDS)
                .withBulkRequestLimit(10).filter(MetricFilter.ALL)
                .build("localhost:9201");
        reporter.start(1, TimeUnit.SECONDS);

        String metricName = "com.codahale.metrics.elasticsearch.test.counter";

        Counter counter = registry.counter(metricName);
        counter.inc();

        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = searchWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all")
                        .setTypes(MetricElasticsearchTypes.COUNTER).setSize(20)
                        .setQuery(QueryBuilders.matchAllQuery()), 1);
        Assert.assertEquals(true, searchResponse.getHits().getHits().length > 0);
        Map<String, Object> searchHitSource = searchResponse.getHits()
                .getHits()[0].getSource();
        Assert.assertEquals(prefix + "." + metricName,
                searchHitSource.get("@name"));
        Assert.assertEquals(1, searchHitSource.get("count"));
        Assert.assertEquals(true, searchHitSource.containsKey("@timeywimey"));
    }

    @Test
    public void testReportsCounters() {
        String metricName = "com.codahale.metrics.elasticsearch.test.counter";

        Counter counter = registry.counter(metricName);
        counter.inc();

        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = searchWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all")
                        .setTypes(MetricElasticsearchTypes.COUNTER).setSize(20)
                        .setQuery(QueryBuilders.matchAllQuery()), 1);
        Assert.assertEquals(true, searchResponse.getHits().getHits().length > 0);
        Map<String, Object> searchHitSource = searchResponse.getHits()
                .getHits()[0].getSource();
        Assert.assertEquals(prefix + "." + metricName,
                searchHitSource.get("@name"));
        Assert.assertEquals(1, searchHitSource.get("count"));
    }

    @Test
    public void testReportsTimers() {
        String metricName = "com.codahale.metrics.elasticsearch.test.timer";

        Timer timer = registry.timer(metricName);
        timer.update(1000, TimeUnit.MILLISECONDS);

        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = searchWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all")
                        .setTypes(MetricElasticsearchTypes.TIMER).setSize(20)
                        .setQuery(QueryBuilders.matchAllQuery()), 1);
        Assert.assertEquals(true, searchResponse.getHits().getHits().length > 0);
        Map<String, Object> searchHitSource = searchResponse.getHits()
                .getHits()[0].getSource();
        Assert.assertEquals(prefix + "." + metricName,
                searchHitSource.get("@name"));
        Assert.assertEquals(1, searchHitSource.get("count"));
    }

    @Test
    public void testReportsMeters() {
        String metricName = "com.codahale.metrics.elasticsearch.test.meter";

        Meter meter = registry.meter(metricName);
        meter.mark();

        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = searchWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all").setSize(20)
                        .setTypes(MetricElasticsearchTypes.METER)
                        .setQuery(QueryBuilders.matchAllQuery()), 1);
        Assert.assertEquals(true, searchResponse.getHits().getHits().length > 0);
        Map<String, Object> searchHitSource = searchResponse.getHits()
                .getHits()[0].getSource();
        Assert.assertEquals(prefix + "." + metricName,
                searchHitSource.get("@name"));
        Assert.assertEquals(1, searchHitSource.get("count"));
    }

    @Test
    public void testReportsHistograms() {
        String metricName = "com.codahale.metrics.elasticsearch.test.histogram";

        Histogram histogram = registry.histogram(metricName);
        histogram.update(1);

        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = searchWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all").setSize(20)
                        .setTypes(MetricElasticsearchTypes.HISTOGRAM)
                        .setQuery(QueryBuilders.matchAllQuery()), 1);
        Assert.assertEquals(true, searchResponse.getHits().getHits().length > 0);
        Map<String, Object> searchHitSource = searchResponse.getHits()
                .getHits()[0].getSource();
        Assert.assertEquals(prefix + "." + metricName,
                searchHitSource.get("@name"));
        Assert.assertEquals(1, searchHitSource.get("count"));
    }

    @Test
    public void testReportsGauges() {
        testReportsGaugeOfType(Integer.class, 1);
        testReportsGaugeOfType(Float.class, Float.MAX_VALUE);
        testReportsGaugeOfType(Double.class, Double.MAX_VALUE);
        testReportsGaugeOfType(Byte.class, (byte) 1);
        testReportsGaugeOfType(Short.class, (short) 1);
        testReportsGaugeOfType(Long.class, Long.MAX_VALUE);
        testReportsGaugeOfType(String.class, "test");
    }

    private <T> void testReportsGaugeOfType(Class<T> type, final T value) {
        String metricName = "com.codahale.metrics.elasticsearch.test.guage."
                + type.getSimpleName().toLowerCase();

        registry.register(metricName, new Gauge<T>() {
            @Override
            public T getValue() {
                return value;
            }
        });

        waitForReporter();
        flushAndRefresh();

        SearchResponse searchResponse = searchWithTimeout(
                new SearchRequestBuilder(ElasticsearchIntegrationTest.client())
                        .setIndices("_all")
                        .setTypes(MetricElasticsearchTypes.GAUGE)
                        .setQuery(
                                QueryBuilders.matchPhraseQuery("@name", prefix
                                        + "." + metricName)), 1);
        Assert.assertEquals(true, searchResponse.getHits().getHits().length > 0);
        Map<String, Object> searchHitSource = searchResponse.getHits()
                .getHits()[0].getSource();
        Assert.assertEquals(prefix + "." + metricName,
                searchHitSource.get("@name"));
        if (value instanceof Float) {
            // Note: Elasticsearch returns Float value as Double
            Assert.assertEquals(Double.valueOf(value.toString()),
                    (Double) searchHitSource.get("floatValue"));
        } else if (value instanceof Double) {
            Assert.assertEquals((Double) value,
                    (Double) searchHitSource.get("doubleValue"));
        } else if (value instanceof Byte) {
            // Note: Elasticsearch returns Byte value as Integer
            Assert.assertEquals(((Byte) value).intValue(),
                    ((Integer) searchHitSource.get("byteValue")).intValue());
        } else if (value instanceof Short) {
            // Note: Elasticsearch returns Short value as Integer
            Assert.assertEquals(((Short) value).intValue(),
                    ((Integer) searchHitSource.get("shortValue")).intValue());
        } else if (value instanceof Integer) {
            Assert.assertEquals((Integer) value,
                    (Integer) searchHitSource.get("integerValue"));
        } else if (value instanceof Long) {
            Assert.assertEquals((Long) value,
                    (Long) searchHitSource.get("longValue"));
        } else {
            Assert.assertEquals((String) value,
                    (String) searchHitSource.get("stringValue"));
        }
    }

    private void waitForReporter() {
        try {
            Thread.sleep(1500);
        } catch (Exception e) {
        }
    }

    private SearchResponse searchWithTimeout(
            SearchRequestBuilder searchRequestBuilder, int expectedResultSize) {
        int timeout = 10;
        while (timeout > 0) {
            try {
                SearchResponse response = searchRequestBuilder.execute()
                        .actionGet();
                if (response.getFailedShards() == 0
                        && response.getHits().getTotalHits() >= expectedResultSize) {
                    return response;
                }
            } catch (Exception e) {
            }
            try {
                Thread.sleep(1000);
            } catch (Exception ex) {
            }
            timeout--;
        }
        return null;
    }
    
    private SearchResponse nestedAggregationWithTimeout(
            SearchRequestBuilder searchRequestBuilder, int expectedResultSize) {
        int timeout = 10;
        while (timeout > 0) {
            try {
                SearchResponse response = searchRequestBuilder.execute()
                        .actionGet();
                if (response.getFailedShards() == 0) {
                    for (Aggregation aggregation : response.getAggregations()) {
                        for (Bucket bucket : ((InternalTerms) aggregation)
                                .getBuckets()) {
                            for (Aggregation nestedAggregation : bucket
                                    .getAggregations()) {
                                if (((InternalTerms) nestedAggregation)
                                        .getBuckets().size() >= expectedResultSize) {
                                    return response;
                                }
                            }
                        }
                    }
                    return response;
                }
            } catch (Exception e) {
            }
            try {
                Thread.sleep(1000);
            } catch (Exception ex) {
            }
            timeout--;
        }
        return null;
    }

    private void deleteIndices() {
        new DeleteIndexRequestBuilder(admin().indices(), "test-*").execute()
                .actionGet();
    }
}
