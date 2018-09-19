package net.powermatcher.monitoring.csv;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.component.annotations.Modified;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

import net.powermatcher.api.data.Bid;
import net.powermatcher.api.data.MarketBasis;
import net.powermatcher.api.messages.BidUpdate;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.api.monitoring.events.AgentEvent;

/**
 * {@link CSVLogger} is an implementation of {@link AgentEventLogger} where the {@link AgentEvent}s are logged to a
 * comma separated file.
 *
 * @author FAN
 * @version 2.1
 */
@Component(immediate = true)
@Designate(ocd = CSVLogger.Config.class, factory = true)
public class CSVLogger
    extends AgentEventLogger {

    /**
     * The header for the bidlog file
     */
    private static final String[] BID_HEADER_ROW = new String[] { "logTime",
                                                                  "clusterId",
                                                                  "agentId",
                                                                  "commodity",
                                                                  "currency",
                                                                  "minimumPrice",
                                                                  "maximumPrice",
                                                                  "minimumDemand",
                                                                  "maximumDemand",
                                                                  "effectiveDemand",
                                                                  "effectivePrice",
                                                                  "lastUpdateTime",
                                                                  "bidNumber",
                                                                  "demand",
                                                                  "pricePoints" };

    /**
     * The header for the pricelog file
     */
    private static final String[] PRICE_HEADER_ROW = new String[] { "logTime",
                                                                    "clusterId",
                                                                    "id",
                                                                    "commodity",
                                                                    "currency",
                                                                    "minimumPrice",
                                                                    "maximumPrice",
                                                                    "priceValue",
                                                                    "lastUpdateTime" };

    /**
     * OSGI configuration of the {@link CSVLogger}
     */
    @ObjectClassDefinition
    public @interface Config {
        @AttributeDefinition(required = false,
                             description = "Filter for specific agentId's. When no filters are supplied, it will log everything.")
        String[] filter();

        @AttributeDefinition(name = "eventType", description = "The AgentEventType this logger has to log.")
        AgentEventType eventType();

        @AttributeDefinition(description = "The pattern for the file name of the log file. "
                                           + "Dataformat strings are placed between the delimeter '::'")
        String logFilenamePattern() default "event_log_::yyyyMMdd::.csv";

        @AttributeDefinition(description = "The date format for the timestamps in the log.")
        String dateFormat() default "yyyy-MM-dd HH:mm:ss";

        @AttributeDefinition(description = "The field separator the logger will use.")
        String separator() default ";";

        @AttributeDefinition(required = true, description = "The location of the log files.")
        String logLocation();

        @AttributeDefinition(type = AttributeType.LONG, description = "Time in seconds between file dumps.")
        long logUpdateRate() default 30L;

        @AttributeDefinition
        String loggerId() default "csvLogger";
    }

    /**
     * The filter containing the {@link ObservableAgent}s that have to be monitored
     */
    private List<String> filter;

    /**
     * The log file {@link BidUpdateLogRecord} will be written to.
     */
    private File logFile;

    /**
     * The field separator the logger will use.
     */
    private String separator;

    /**
     * OSGi calls this method to activate a managed service.
     *
     * @param properties
     *            the configuration properties
     */
    @Activate
    public synchronized void activate(Map<String, Object> properties) {
        super.baseActivate(properties);
        getLogger().info("CSVLogger [{}], activated", getLoggerId());
    }

    /**
     * OSGi calls this method to deactivate a managed service.
     */
    @Deactivate
    public void deactivate() {
        super.baseDeactivate();
        getLogger().info("CSVLogger [{}], deactivated", getLoggerId());
    }

    /**
     * OSGi calls this method to modify a managed service.
     *
     * @param properties
     *            the configuration properties
     */
    @Modified
    public synchronized void modified(Map<String, Object> properties) {
        super.baseModified(properties);
        getLogger().info("CSVLogger [{}], modified", getLoggerId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    @Reference(policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    public void addObservable(ObservableAgent observable, Map<String, Object> properties) {
        super.addObservable(observable, properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void removeObservable(ObservableAgent observable, Map<String, Object> properties) {
        super.removeObservable(observable, properties);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void processConfig(Map<String, Object> properties) {
        filter = Arrays.asList((String[]) properties.get("filter"));

        // ConfigAdmin will sometimes generate a filter with 1 empty element.
        // Ignore it.
        if (filter != null && !filter.isEmpty() && filter.get(0).isEmpty()) {
            filter = new ArrayList<String>();
        }

        setEventType((AgentEventType) properties.get("eventType"));
        setLogUpdateRate((Long) properties.get("logUpdateRate"));
        setLoggerId((String) properties.get("loggerId"));
        setDateFormat(new SimpleDateFormat((String) properties.get("dateFormat")));
        separator = (String) properties.get("separator");

        String logFilenamePattern = (String) properties.get("logFilenamePattern");
        String logLocation = (String) properties.get("logLocation");

        logFile = createLogFile(logFilenamePattern, logLocation);
        if (!logFile.exists()) {
            String[] header = null;

            switch (getEventType()) {
            case PRICE_EVENT:
                header = PRICE_HEADER_ROW;
                break;
            case BID_EVENT:
                header = BID_HEADER_ROW;
                break;
            default:
                break;
            }
            if (header != null) {
                writeLineToCSV(header, logFile);
            }
        }
        updateObservables();
    }

    /**
     * Creates a new {@link File} to write the csv lines to. It also parses possible {@link DateFormat} strings in the
     * fileName parameter.
     *
     * @param fileName
     *            the name of the {@link File} that has to be created
     * @param logLocation
     *            the location of the {@link File} that has to be created
     * @return The {@link File} with the fileName name and the logLocation as location
     */
    private File createLogFile(String fileName, String logLocation) {
        String newFileName = fileName;

        // in case somebody forgets the extention
        if (!fileName.endsWith(".csv")) {
            newFileName = newFileName.concat(".csv");
        }

        if (fileName.matches("\\S*::\\w*::*.csv")) {

            String logDateFormat = fileName.substring(fileName.indexOf("::") + 2, fileName.lastIndexOf("::"));
            String date = new SimpleDateFormat(logDateFormat).format(new Date());

            Pattern pattern = Pattern.compile("::\\w*::");
            Matcher matcher = pattern.matcher(fileName);
            newFileName = matcher.replaceAll(date);
        }

        return new File(logLocation + File.separator + newFileName);
    }

    /**
     * Write a comma separated line to a specified file
     *
     * @param line
     *            the comma separated line that has to be written to the outputFile
     * @param outputFile
     *            the csv log file where to line has to be written to
     */
    private void writeLineToCSV(String[] line, File outputFile) {
        BufferedWriter w = null;
        try {
            w = new BufferedWriter(new FileWriter(outputFile, true));

            for (String s : line) {
                w.write(s);
                w.write(separator);
            }
            w.newLine();
        } catch (IOException e) {
            getLogger().error("Error while writing to CSV file", e);
        } finally {
            if (w != null) {
                try {
                    w.close();
                } catch (IOException e) {
                    getLogger().error("Error while writing to CSV file", e);
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected void dumpLogs() {
        for (LogRecord logRecord : getLogRecords().toArray(new LogRecord[getLogRecords().size()])) {

            String[] output = null;

            if (logRecord instanceof BidUpdateLogRecord) {
                output = createLineForBidLogRecord((BidUpdateLogRecord) logRecord);
            } else if (logRecord instanceof PriceUpdateLogRecord) {
                output = createLineForPriceUpdateLog((PriceUpdateLogRecord) logRecord);
            }

            if (output != null) {
                writeLineToCSV(output, logFile);
            }
            removeLogRecord(logRecord);
        }
        getLogger().info("CSVLogger [{}] wrote to {}", getLoggerId(), logFile);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    protected List<String> getFilter() {
        return filter;
    }

    /**
     * Creates a <code>String[]</code> out of a {@link BidUpdateLogRecord} to be used in
     * {@link CSVLogger#writeLineToCSV(String[], File)}
     *
     * @param logRecord
     *            the {@link BidUpdateLogRecord} that has to be transformed
     * @return the <code>String[]</code> representation of logRecord
     */
    private String[] createLineForBidLogRecord(BidUpdateLogRecord logRecord) {

        BidUpdate bidUpdate = logRecord.getBidUpdate();
        Bid bid = bidUpdate.getBid();

        MarketBasis marketBasis = bid.getMarketBasis();

        StringBuilder demandBuilder = new StringBuilder();
        StringBuilder pricePointBuiler = new StringBuilder();

        Bid temp = bid;

        for (Double d : temp.getDemand()) {
            if (demandBuilder.length() > 0) {
                demandBuilder.append("#");
            }
            demandBuilder.append(d);
        }

        return new String[] { getDateFormat().format(logRecord.getLogTime()),
                              logRecord.getClusterId(),
                              logRecord.getAgentId(),
                              marketBasis.getCommodity(),
                              marketBasis.getCurrency(),
                              MarketBasis.PRICE_FORMAT.format(marketBasis.getMinimumPrice()),
                              MarketBasis.PRICE_FORMAT.format(marketBasis.getMaximumPrice()),
                              MarketBasis.DEMAND_FORMAT.format(bid.getMinimumDemand()),
                              MarketBasis.DEMAND_FORMAT.format(bid.getMaximumDemand()),
                              // TODO where/what is the "effective demand"?
                              MarketBasis.DEMAND_FORMAT.format(0),
                              // TODO where/what is the "effective price"?
                              MarketBasis.PRICE_FORMAT.format(0),
                              getDateFormat().format(logRecord.getEventTimestamp()),
                              String.valueOf(bidUpdate.getBidNumber()),
                              demandBuilder.toString(),
                              pricePointBuiler.toString() };
    }

    /**
     * Creates a <code>String[]</code> out of a {@link PriceUpdateLogRecord} to be used in
     * {@link CSVLogger#writeLineToCSV(String[], File)}
     *
     * @param logRecord
     *            the {@link PriceUpdateLogRecord} that has to be transformed
     * @return the <code>String[]</code> representation of logRecord
     */
    private String[] createLineForPriceUpdateLog(PriceUpdateLogRecord logRecord) {
        MarketBasis marketbasis = logRecord.getPriceUpdate().getPrice().getMarketBasis();

        return new String[] { getDateFormat().format(logRecord.getLogTime()),
                              logRecord.getClusterId(),
                              logRecord.getAgentId(),
                              marketbasis.getCommodity(),
                              marketbasis.getCurrency(),
                              MarketBasis.PRICE_FORMAT.format(marketbasis.getMinimumPrice()),
                              MarketBasis.PRICE_FORMAT.format(marketbasis.getMaximumPrice()),
                              MarketBasis.PRICE_FORMAT.format(logRecord.getPriceUpdate().getPrice().getPriceValue()),
                              getDateFormat().format(logRecord.getEventTimestamp()) };
    }
}
