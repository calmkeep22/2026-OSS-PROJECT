package org.ossproject.sonification;

import org.ossproject.sonification.model.TimeSeriesSample;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Largest-Triangle-Three-Buckets reduction that retains the first and last points and favors
 * visually significant bends and extrema over evenly dropping samples.
 */
public final class LargestTriangleThreeBucketsReducer implements GraphSeriesReducer {
    @Override public List<TimeSeriesSample> reduce(List<TimeSeriesSample> samples, int maximumPoints) {
        List<TimeSeriesSample> checked = GraphAnalyzer.validate(samples);
        if (maximumPoints < 3) throw new IllegalArgumentException("maximumPoints must be at least three");
        if (checked.size() <= maximumPoints) return checked;

        List<TimeSeriesSample> reduced = new ArrayList<>(maximumPoints);
        reduced.add(checked.get(0));
        double bucketWidth = (checked.size() - 2.0) / (maximumPoints - 2.0);
        int selectedIndex = 0;
        Instant origin = checked.get(0).timestamp();

        for (int bucket = 0; bucket < maximumPoints - 2; bucket++) {
            int averageStart = Math.min(checked.size() - 1,
                    (int) Math.floor((bucket + 1) * bucketWidth) + 1);
            int averageEnd = Math.min(checked.size(),
                    (int) Math.floor((bucket + 2) * bucketWidth) + 1);
            if (averageStart >= averageEnd) {
                averageStart = checked.size() - 1;
                averageEnd = checked.size();
            }

            double averageX = 0;
            double averageY = 0;
            for (int i = averageStart; i < averageEnd; i++) {
                averageX += secondsFrom(origin, checked.get(i).timestamp());
                averageY += checked.get(i).value();
            }
            int averageCount = averageEnd - averageStart;
            averageX /= averageCount;
            averageY /= averageCount;

            int rangeStart = (int) Math.floor(bucket * bucketWidth) + 1;
            int rangeEnd = Math.min(checked.size() - 1,
                    (int) Math.floor((bucket + 1) * bucketWidth) + 1);
            TimeSeriesSample anchor = checked.get(selectedIndex);
            double anchorX = secondsFrom(origin, anchor.timestamp());
            double largestArea = -1;
            int nextSelectedIndex = rangeStart;
            for (int i = rangeStart; i < rangeEnd; i++) {
                TimeSeriesSample candidate = checked.get(i);
                double candidateX = secondsFrom(origin, candidate.timestamp());
                double area = Math.abs((anchorX - averageX) * (candidate.value() - anchor.value())
                        - (anchorX - candidateX) * (averageY - anchor.value()));
                if (area > largestArea) {
                    largestArea = area;
                    nextSelectedIndex = i;
                }
            }
            selectedIndex = nextSelectedIndex;
            reduced.add(checked.get(selectedIndex));
        }
        reduced.add(checked.get(checked.size() - 1));
        return List.copyOf(reduced);
    }

    private static double secondsFrom(Instant origin, Instant timestamp) {
        long seconds = timestamp.getEpochSecond() - origin.getEpochSecond();
        int nanos = timestamp.getNano() - origin.getNano();
        return seconds + nanos / 1_000_000_000.0;
    }
}
