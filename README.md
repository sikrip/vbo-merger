# Vbo and ECU log merger


Merges .vbo files with ecu log files (csv files).
Automatically syncs the files.
This is done by matching the log entries with the highest speed ("Speed" column on ecu data and "velocity kmh" on .vbo data).

A new vbo is created containing all the data from the original vbo plus all the ECU data (prefixed with ecu_) from
the csv log.

## Usage

> java -jar vbo-merger.jar ecu-log-file vbo-file
