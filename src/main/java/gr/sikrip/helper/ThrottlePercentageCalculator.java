package gr.sikrip.helper;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static gr.sikrip.helper.LapExtractor.getLapBoundIndices;
import static java.lang.Math.max;
import static java.lang.Math.min;

/**
 * Creates the throttle percentage and lap accumulated lap percentage for
 * throttle application based on throttle voltage.
 */
public final class ThrottlePercentageCalculator {

    private static final String THROTTLE_VOLT_HEADER = "ecu_VTA V";
    private static final double THROTTLE_VOLT_MAX = 4.0;
    private static final double THROTTLE_VOLT_MIN = 0.6;
    private static final String THROTTLE_PERCENTAGE_HEADER = "calc_throttlePercentage";
    private static final String LAP_ACCUMULATED_THROTTLE_PERCENTAGE_HEADER = "calc_lapAccumThrottlePercentage";

    public static void createThrottlePercentageData(Map<String, List<Object>> vboLog) {
        final List<Object> lapAccumulatedThrottlePercentageValues = new ArrayList<>();
        final List<Object> throttlePercentageValues = new ArrayList<>();
        for(int i = 0; i < vboLog.get(THROTTLE_VOLT_HEADER).size(); i++) {
            // Fit volt between min and max
            final double throttleVolt = max(THROTTLE_VOLT_MIN, min(THROTTLE_VOLT_MAX, Double.parseDouble(vboLog.get(THROTTLE_VOLT_HEADER).get(i).toString())));

            // Map volt to percentage
            throttlePercentageValues.add(100 * (throttleVolt - THROTTLE_VOLT_MIN) / (THROTTLE_VOLT_MAX - THROTTLE_VOLT_MIN));

            // Initiate lap accumulated percentage
            lapAccumulatedThrottlePercentageValues.add(0.0);
        }

        vboLog.put(THROTTLE_PERCENTAGE_HEADER, throttlePercentageValues);
        vboLog.put(LAP_ACCUMULATED_THROTTLE_PERCENTAGE_HEADER, lapAccumulatedThrottlePercentageValues);
        final List<LapExtractor.LapBounds> lapBoundIndices = getLapBoundIndices(vboLog);
        for (LapExtractor.LapBounds lapBoundIndex : lapBoundIndices) {
            int valuesCount = 0;
            double valuesSum = 0;
            for(int i=lapBoundIndex.getStart(); i<=lapBoundIndex.getEnd(); i++) {
                final double throttlePercentage = (double)throttlePercentageValues.get(i);
                valuesSum += throttlePercentage;
                valuesCount++;
                double runningAvg = valuesSum / valuesCount;
                lapAccumulatedThrottlePercentageValues.set(i, runningAvg);
            }
        }
    }
}
