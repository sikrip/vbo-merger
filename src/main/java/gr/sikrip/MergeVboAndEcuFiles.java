package gr.sikrip;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static gr.sikrip.helper.ThrottlePercentageCalculator.createThrottlePercentageData;
import static java.lang.Math.abs;

/**
 * Merges .vbo files with ecu log files.
 * Automatically syncs the files by using the log entry with the highest speed
 * that is available to both files.
 */
public class MergeVboAndEcuFiles {

    private static final String TIME_MILLIS_HEADER = "TimeMillis";

    private static final String ECU_TIME_HEADER = "Time(S)";
    private static final String ECU_SPEED_HEADER = "Speed";

    private static final String VBO_SPEED_HEADER = "velocity kmh";
    private static final String VBO_TIME_HEADER = "time";
    private static final String VBO_HEADER_SECTION = "[header]";
    private static final String VBO_DATA_SECTION = "[data]";
    private static final String VBO_COMMENTS_SECTION = "[comments]";
    private static final String VBO_COLUMN_NAMES_SECTION = "[column names]";
    private static final String MERGED_ECU_HEADER_PREFIX = "ecu_";
    private static final String ECU_OLI_PRESS_HEADER = "OilPress";
    private static final double LAST_LAP_MAX_SPEED_PERCENTAGE = 0.95;
    private static final int NUMBER_OF_SMOOTHING_CYCLES = 40;

    public static void main(String[] args) throws IOException {
        printVersion();
        printUsage();
        if (args.length != 2) {
            return;
        }

        final String ecuFilePath = args[0];
        final String vboFilePath = args[1];
        final String outputFilePath = vboFilePath.replace(".vbo", "-ecu.vbo");

        System.out.printf("Merging %s \nwith %s \ninto %s\n...\n", ecuFilePath, vboFilePath, outputFilePath);

        final Map<String, List<Object>> ecuLog = readEcuLog(ecuFilePath);
        final Map<String, List<Object>> vboLog = readVbo(vboFilePath);
        final Map<String, List<Object>> vboWithEcuData = mergeLogs(ecuLog, vboLog);
        createThrottlePercentageData(vboWithEcuData);

        final List<String> allHeadersSorted = vboWithEcuData.keySet().stream().sorted().collect(Collectors.toList());
        final PrintWriter writer = new PrintWriter(outputFilePath, "UTF-8");
        final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy/MM/dd");
        final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss");
        final Date now = new Date();
        writer.printf("File created on %s at %s\n", dateFormat.format(now), timeFormat.format(now));
        writer.println();
        writer.println(VBO_HEADER_SECTION);
        for (String h : allHeadersSorted) {
            writer.println(h);
        }

        writer.println();
        writer.println(VBO_COMMENTS_SECTION);
        writer.println("Merged vbo with ecu logs");
        writer.println();
        writer.println(VBO_COLUMN_NAMES_SECTION);
        writer.println(allHeadersSorted.stream().map(h -> {
            if (VBO_SPEED_HEADER.equals(h)) {
                // speed name in column names is "velocity" in vbo files
                return "velocity";
            }
            // make sure column name has no spaces
            return h.replaceAll("\\s+", "-");
        }).collect(Collectors.joining(" ")));

        writer.println();
        writer.println(VBO_DATA_SECTION);
        for(int i = 0; i<vboWithEcuData.get(VBO_TIME_HEADER).size(); i++) {
            for (String h : allHeadersSorted) {
                writer.print(vboWithEcuData.get(h).get(i) + " ");
            }
            writer.println();
        }

        writer.close();
        System.out.println("done!");
    }

    private static void printUsage() {
        System.out.println("Usage:");
        System.out.println("java -jar vbo-merger.jar <ecu file> <vbo-file>\n");
    }

    private static void printVersion() {
        try {
            final Properties properties = new Properties();
            properties.load(MergeVboAndEcuFiles.class.getClassLoader().getResourceAsStream("project.properties"));
            System.out.printf("vbo-merger version %s\n", properties.getProperty("version"));
        } catch (IOException e) {
            // ignore
        }
    }

    /**
     * Merges the vbo and ecu logs.
     */
    private static Map<String, List<Object>> mergeLogs(Map<String, List<Object>> ecuLog,
                                                       Map<String, List<Object>> vboLog) {
        final Map<String, List<Object>> vboWithEcu = new HashMap<>(vboLog);
        ecuLog.keySet().forEach(h -> vboWithEcu.put(MERGED_ECU_HEADER_PREFIX + h, new ArrayList<>()));
        for(int i = 0; i < vboLog.get(TIME_MILLIS_HEADER).size(); i++) {
            final long refTimeMillis = (long)vboLog.get(TIME_MILLIS_HEADER).get(i);
            final List<Object> ecuTimes = ecuLog.get(TIME_MILLIS_HEADER);
            long minDiff = Long.MAX_VALUE;
            int bestMatchEcuIdx = -1;
            for (int j = 0; j < ecuTimes.size(); j++) {
                final long timeDiff = abs((long) ecuTimes.get(j) - refTimeMillis);
                if (timeDiff < minDiff) {
                    minDiff = timeDiff;
                    bestMatchEcuIdx = j;
                }
            }
            for (String h : ecuLog.keySet()) {
                vboWithEcu.get(MERGED_ECU_HEADER_PREFIX + h).add(ecuLog.get(h).get(bestMatchEcuIdx));
            }
        }
        // not needed in the final vbo data
        vboWithEcu.remove(TIME_MILLIS_HEADER);
        vboWithEcu.remove(MERGED_ECU_HEADER_PREFIX + TIME_MILLIS_HEADER);
        return vboWithEcu;
    }

    /**
     * Finds the index with the max speed and subtracts the time of this point
     * from all time values.
     */
    private static void adjustTimeValues(Map<String, List<Object>> dataLog, String speedHeader) {
        final List<Object> speedData = dataLog.get(speedHeader);

        final double maxSpeedOfSession = speedData.stream()
            .mapToDouble(s -> Double.parseDouble(s.toString()))
            .max().orElseThrow(() -> new IllegalStateException("Could not find max speed"));

        double lastLapMaxSpeed = 0;
        int maxSpeedIdx = 0;
        boolean lastLapTopSpeedRegionReached = false;
        for (int i = speedData.size()-1; i >=0; i--) {
            final double speed = Double.parseDouble(speedData.get(i).toString());
            if (speed > LAST_LAP_MAX_SPEED_PERCENTAGE * maxSpeedOfSession) {
                lastLapTopSpeedRegionReached = true;
            }
            if (lastLapTopSpeedRegionReached && speed <= LAST_LAP_MAX_SPEED_PERCENTAGE * maxSpeedOfSession) {
                // we are past last lap top speed region
                break;
            }
            if (speed > LAST_LAP_MAX_SPEED_PERCENTAGE * maxSpeedOfSession && speed > lastLapMaxSpeed) {
                lastLapMaxSpeed = speed;
                maxSpeedIdx = i;
            }
        }

        final long timeAtMaxSpeed = (long)dataLog.get(TIME_MILLIS_HEADER).get(maxSpeedIdx);
        final List<Object> timeValues = dataLog.get(TIME_MILLIS_HEADER);
        timeValues.replaceAll(o -> (long) o - timeAtMaxSpeed);
    }

    private static Map<String, List<Object>> readVbo(String filePath) throws IOException {
        final Map<String, List<Object>> logData = new HashMap<>();
        final List<String> headers = new ArrayList<>();
        final AtomicBoolean onHeaderLine = new AtomicBoolean(false);
        final AtomicBoolean onDataLines = new AtomicBoolean(false);
        Files.lines(Paths.get(filePath)).forEach(line -> {
            if (VBO_HEADER_SECTION.equals(line)) {
                onHeaderLine.set(true);
            } else if (onHeaderLine.get()) {
                if ("".equals(line)) {
                    onHeaderLine.set(false);
                } else {
                    headers.add(line);
                    logData.put(line, new ArrayList<>());
                }
            } else if (VBO_DATA_SECTION.equals(line)) {
                onDataLines.set(true);
            } else if (onDataLines.get()) {
                AtomicInteger dataIdx = new AtomicInteger(0);
                Arrays.stream(line.split(" "))
                    .forEach(stringData -> {
                        final String header = headers.get(dataIdx.getAndIncrement());
                        logData.get(header).add(stringData);
                    });
            }
        });
        final List<Object> timeMillisValues = new ArrayList<>();
        logData.put(TIME_MILLIS_HEADER, timeMillisValues);
        logData.get(VBO_TIME_HEADER).forEach(t -> {
            timeMillisValues.add(vboTimeToMillis((String) t));
        });
        adjustTimeValues(logData, VBO_SPEED_HEADER);
        return logData;
    }

    private static Map<String, List<Object>> readEcuLog(String filePath) throws IOException {
        final Map<String, List<Object>> logData = new HashMap<>();
        final List<String> headers = new ArrayList<>();
        AtomicBoolean headerCreated = new AtomicBoolean(false);
        Files.lines(Paths.get(filePath)).forEach(line -> {
            AtomicInteger dataIdx = new AtomicInteger(0);
            Arrays.stream(line.split(",")).forEach(stringData -> {
                if (headerCreated.get()) {
                    logData.get(headers.get(dataIdx.getAndIncrement())).add(stringData);
                } else {
                    headers.add(stringData);
                    logData.put(stringData, new ArrayList<>());
                }
            });
            headerCreated.set(true);
        });
        final List<Object> timeMillisValues = new ArrayList<>();
        logData.put(TIME_MILLIS_HEADER, timeMillisValues);
        logData.get(ECU_TIME_HEADER).forEach(t -> {
            timeMillisValues.add((long)(Double.parseDouble(t.toString()) * 1000));
        });
        adjustTimeValues(logData, ECU_SPEED_HEADER);

        for(int i = 0; i< NUMBER_OF_SMOOTHING_CYCLES; i++) {
            logData.put(ECU_OLI_PRESS_HEADER, smoothValues(logData.get(ECU_OLI_PRESS_HEADER)));
        }

        return logData;
    }

    /**
     * Smoothing the raw values by rolling average of 3 values.
     */
    private static List<Object> smoothValues(List<Object> rawValues) {
        final List<Object> smoothedValues = new ArrayList<>();
        smoothedValues.add(rawValues.get(0));
        for (int i = 1; i < rawValues.size()-1; i++) {
            final double avg = (
                Double.parseDouble(rawValues.get(i - 1).toString()) +
                Double.parseDouble(rawValues.get(i).toString()) +
                Double.parseDouble(rawValues.get(i + 1).toString())
            ) / 3;
            smoothedValues.add("" + avg);
        }
        smoothedValues.add(smoothedValues.get(smoothedValues.size()-1));
        return smoothedValues;
    }

    /**
     * Converts the vbo format of time (UTC time since midnight in the form HH:MM:SS.SS) to milliseconds.
     *
     * @param time the time in the format used by the vbo files
     * @return the equivalent time in milliseconds
     */
    private static long vboTimeToMillis(String time) {
        final int length = time.length();
        if (length == 10 || length == 9) {
            final long hh = Long.parseLong(time.substring(0, 2));
            final long mm = Long.parseLong(time.substring(2, 4));
            final long ss = Long.parseLong(time.substring(4, 6));
            final long millis;
            if (length==9) {
                millis = Long.parseLong(time.substring(7, length)) * 10;
            } else {
                millis = Long.parseLong(time.substring(7, length));
            }
            return millis + ss * 1000 + mm * 60 * 1000 + hh * 60 * 60 * 1000;
        }
        throw new IllegalArgumentException(String.format("Unexpected VBO time value %s", time));
    }

}
