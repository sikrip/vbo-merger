package gr.sikrip.helper;

import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.awt.geom.Line2D;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static java.lang.Math.pow;
import static java.lang.Math.sqrt;

/**
 * Extracts laps from a collection of coordinates.
 */
final class LapExtractor {

    private static final List<StartFinishLine> START_FINISH_LINES = Arrays.asList(
        new StartFinishLine( // Megara
            Coordinate.builder()
                .x(37.986926 * 60.0)
                .y(-23.363105 * 60.0)
                .build(),
            Coordinate.builder()
                .x(37.987165 * 60)
                .y(-23.363024 * 60)
                .build()
        ),
        new StartFinishLine( // Serres
            Coordinate.builder()
                .x(41.073082 * 60.0)
                .y(-23.517710 * 60.0)
                .build(),
            Coordinate.builder()
                 .x(41.073275 * 60.0)
                 .y(-23.517918 * 60.0)
                 .build()
        )
    );

    static List<LapBounds> getLapBoundIndices(Map<String, List<Object>> vboLog) {
        final List<Object> latitudeValues = vboLog.get("latitude");
        final List<Object> longitude = vboLog.get("longitude");

        List<Coordinate> coordinates = new ArrayList<>();
        for (int i = 0; i < latitudeValues.size(); i++) {
            coordinates.add(
                Coordinate.builder()
                    .x(Double.parseDouble(latitudeValues.get(i).toString()))
                    .y(Double.parseDouble(longitude.get(i).toString()))
                    .build()
            );
        }
        final StartFinishLine startFinishLine = findNearestTrack(coordinates);
        if (startFinishLine != null) {
            return getLapsIndexBounds(coordinates, startFinishLine.getA(), startFinishLine.getB());
        }
        return new ArrayList<>();
    }

    private static StartFinishLine findNearestTrack(List<Coordinate> coordinates) {
        double nearestTrackDistance = -1;
        StartFinishLine resolvedTrack = null;
        for (StartFinishLine track : START_FINISH_LINES) {
            double minDistance = -1;
            for (Coordinate coordinate : coordinates) {
                double distance = pointsLength(coordinate, track.getA());
                if (minDistance == -1 || minDistance > distance) {
                    minDistance = distance;
                }
            }
            if (nearestTrackDistance == -1 || nearestTrackDistance > minDistance) {
                nearestTrackDistance = minDistance;
                resolvedTrack = track;
            }
        }
        return resolvedTrack;
    }

    private static double pointsLength(Coordinate p1, Coordinate p2) {
        return sqrt(pow(p2.getX() - p1.getX(), 2) + pow(p2.getY() - p1.getY(), 2));
    }

    /**
     * Gets the start & end indices of each lap found the provided track points.
     */
    private static List<LapBounds> getLapsIndexBounds(
            List<Coordinate> sessionTrackPoints,
            Coordinate startFinishP1, Coordinate startFinishP2) {
        final List<LapBounds> allLapIndexBounds = new ArrayList<>();
        for(int i=0; i < sessionTrackPoints.size() -1; i++) {
            final Coordinate start = sessionTrackPoints.get(i);
            final Coordinate end = sessionTrackPoints.get(i + 1);

            final boolean intersectsWithStartFinish = Line2D.linesIntersect(
                    startFinishP1.getX(), startFinishP1.getY(),
                    startFinishP2.getX(), startFinishP2.getY(),
                    start.getX(), start.getY(),
                    end.getX(), end.getY()
            );
            final LapBounds.LapBoundsBuilder lapBoundsBuilder = LapBounds.builder();
            if (intersectsWithStartFinish) {
                // Start of lap found
                lapBoundsBuilder.start(i);

                // Search for the end of the lap
                i++;
                for(; i < sessionTrackPoints.size() -1; i++) {
                    final Coordinate start1 = sessionTrackPoints.get(i);
                    final Coordinate end1 = sessionTrackPoints.get(i + 1);
                    final boolean intersectsWithFinish = Line2D.linesIntersect(
                            startFinishP1.getX(), startFinishP1.getY(),
                            startFinishP2.getX(), startFinishP2.getY(),
                            start1.getX(), start1.getY(),
                            end1.getX(), end1.getY()
                    );
                    if (intersectsWithFinish) {
                        // end of lap found, we add one because i is the start of the segment
                        lapBoundsBuilder.end(i + 1);
                        allLapIndexBounds.add(lapBoundsBuilder.build());
                        i--; // move one point back so that the next lap will not be missed by outer loop
                        break;
                    }
                }
            }
        }
        return allLapIndexBounds;
    }

    @Getter
    @Builder
    private static class Coordinate {
        private final double x;
        private final double y;

    }

    @Getter
    @Builder
    static class LapBounds {
        private final int start;
        private final int end;
    }

    @Getter
    @RequiredArgsConstructor
    static class StartFinishLine {
        private final Coordinate a;
        private final Coordinate b;
    }
}
