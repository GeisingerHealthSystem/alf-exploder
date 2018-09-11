package com.github.rkanter.alfexploder;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.GnuParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.conf.Configured;
import org.apache.hadoop.fs.AbstractFileSystem;
import org.apache.hadoop.fs.FSDataOutputStream;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.HarFs;
import org.apache.hadoop.fs.LocatedFileStatus;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.RemoteIterator;
import org.apache.hadoop.util.Tool;
import org.apache.hadoop.yarn.conf.YarnConfiguration;
import org.apache.hadoop.yarn.logaggregation.AggregatedLogFormat;

import java.io.DataInputStream;
import java.io.IOException;

public class ALFExploder extends Configured implements Tool {

    private static final String APP_LOG_DIR_OPTION = "applogdir";
    private static final String OUTPUT_DIR_OPTION = "outputdir";

    public static void main(String args[]) throws Exception {
        // Issues with recognizing HAR path, testing solutions...
        conf.set("fs.hdfs.impl", org.apache.hadoop.hdfs.DistributedFileSystem.class.getName());
        conf.set("fs.file.impl", org.apache.hadoop.fs.LocalFileSystem.class.getName());
        // Upstream code continues here
        Configuration conf = new YarnConfiguration();
        ALFExploder calf = new ALFExploder();
        calf.setConf(conf);
        int exitCode = calf.run(args);
        System.exit(exitCode);
    }

    public int run(String[] args) throws Exception {
        Options opts = new Options();
        Option logDirOption = new Option(APP_LOG_DIR_OPTION, true, "Application Log Dir (local or remote)");
        logDirOption.setRequired(true);
        opts.addOption(logDirOption);
        Option outputOption = new Option(OUTPUT_DIR_OPTION, true, "Output Directory");
        outputOption.setRequired(true);
        opts.addOption(outputOption);

        if (args.length != 4) {
            printHelpMessage(opts);
            return -1;
        }

        CommandLineParser parser = new GnuParser();
        String logDir = null;
        String outputDir = null;
        try {
            CommandLine commandLine = parser.parse(opts, args, true);
            logDir = commandLine.getOptionValue(APP_LOG_DIR_OPTION);
            outputDir = commandLine.getOptionValue(OUTPUT_DIR_OPTION);
        } catch (ParseException e) {
            System.err.println("options parsing failed: " + e.getMessage());
            printHelpMessage(opts);
            return -1;
        }

        Path logDirPath = new Path(logDir);
        Path outputDirPath = new Path(outputDir);
        explode(logDirPath, outputDirPath);
        return 0;
    }

    private void explode(Path logDir, Path outputDir) throws IOException {
        FileSystem ldFs = null;
        FileSystem odFs = null;
        try {
            ldFs = logDir.getFileSystem(getConf());
            odFs = outputDir.getFileSystem(getConf());
            odFs.mkdirs(outputDir);

            // Aggregated logs
            RemoteIterator<LocatedFileStatus> logFiles = ldFs.listLocatedStatus(logDir);
            while (logFiles.hasNext()) {
                LocatedFileStatus logFile = logFiles.next();
                // Exclude all invisible files (e.g. ".DS_Store")
                if (!logFile.getPath().getName().startsWith(".")) {
                    if (logFile.getPath().getName().endsWith(".har")) {
                        explodeHARAggregatedLog(logFile.getPath(), outputDir, odFs);
                    } else {
                        explodeAggregatedLog(logFile.getPath(), outputDir, odFs);
                    }
                }
            }
        } finally {
            if (ldFs != null) {
                ldFs.close();
            }
            if (odFs != null) {
                odFs.close();
            }
        }
    }

    private void explodeHARAggregatedLog(Path logFile, Path outputDir, FileSystem odFs) {
        Path harFile = new Path("har:///" + logFile.toUri().getRawPath());
        AbstractFileSystem harFs = null;
        try {
            harFs = HarFs.get(harFile.toUri(), getConf());
            RemoteIterator<FileStatus> it = harFs.listStatusIterator(harFile);
            while (it.hasNext()) {
                explodeAggregatedLog(it.next().getPath(), outputDir, odFs);
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
    }

    private void explodeAggregatedLog(Path logFile, Path outputDir, FileSystem odFs) {
        AggregatedLogFormat.LogReader reader = null;
        try {
            System.out.println(logFile.toUri().getPath());
            reader = new AggregatedLogFormat.LogReader(getConf(), logFile);
            DataInputStream valueStream = null;
            try {
                AggregatedLogFormat.LogKey key = new AggregatedLogFormat.LogKey();
                valueStream = reader.next(key);
                while (key.toString() != null && valueStream != null) {
                    try {
                        Path containerPath = new Path(outputDir, key.toString());
                        if (!odFs.exists(containerPath)) {
                            odFs.mkdirs(containerPath);
                        }
                        System.out.println("    --> " + containerPath.toUri().getPath());
                        AggregatedLogFormat.ContainerLogsReader logReader = new AggregatedLogFormat.ContainerLogsReader(valueStream);
                        writeContainerLog(logReader, containerPath, odFs);
                    } catch (IOException ioe) {
                        ioe.printStackTrace();
                    } finally {
                        if (valueStream != null) {
                            valueStream.close();
                        }
                    }
                    valueStream = reader.next(key);
                }
            } finally {
                if (valueStream != null) {
                    valueStream.close();
                }
            }
        } catch (IOException ioe) {
            ioe.printStackTrace();
        } finally {
            if (reader != null) {
                reader.close();
            }
        }
    }

    private void writeContainerLog(AggregatedLogFormat.ContainerLogsReader logReader, Path containerPath, FileSystem odFs)
            throws IOException {
        while (logReader.nextLog() != null) {
            String logType = logReader.getCurrentLogType();
            Path logTypePath = new Path(containerPath, logType);
            FSDataOutputStream logOut = null;
            try {
                System.out.println("        - " + logTypePath.getName());
                logOut = odFs.create(logTypePath);
                long fileLength = logReader.getCurrentLogLength();
                byte[] buf = new byte[65535];
                long curRead = 0;
                long pendingRead = fileLength - curRead;
                int toRead = pendingRead > buf.length ? buf.length : (int) pendingRead;
                int len = logReader.read(buf, 0, toRead);
                while (len != -1 && curRead < fileLength) {
                    logOut.write(buf, 0, len);
                    curRead += len;
                    pendingRead = fileLength - curRead;
                    toRead = pendingRead > buf.length ? buf.length : (int) pendingRead;
                    len = logReader.read(buf, 0, toRead);
                }
            } finally {
                if (logOut != null) {
                    logOut.close();
                }
            }
        }
    }

    private void printHelpMessage(Options options) {
        System.out.println("Takes an application log directory and explodes any aggregated log files into "
                + "their original separate log files");
        HelpFormatter formatter = new HelpFormatter();
        formatter.printHelp("./run.sh", options, true);
    }
}
