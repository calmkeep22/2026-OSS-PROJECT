package org.ossproject.desktop.chart;

/** Application callback used by the chart feature to request exact-value speech. */
@FunctionalInterface
public interface ChartAnnouncementSink {
    void announce(String text, String deduplicationKey);
}
