package net.powermatcher.examples;

import java.util.Random;
import java.util.concurrent.ScheduledFuture;

import javax.measure.Measure;
import javax.measure.unit.SI;

import org.flexiblepower.context.FlexiblePowerContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Deactivate;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.AttributeType;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.powermatcher.api.AgentEndpoint;
import net.powermatcher.api.data.Bid;
import net.powermatcher.api.data.Price;
import net.powermatcher.api.data.PricePoint;
import net.powermatcher.api.messages.PriceUpdate;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.core.BaseAgentEndpoint;

/**
 * {@link PVPanelAgent} is a implementation of a {@link BaseAgentEndpoint}. It represents a dummy freezer.
 * {@link PVPanelAgent} creates a {@link PointBid} with random {@link PricePoint}s at a set interval. It does nothing
 * with the returned {@link Price}.
 *
 * @author FAN
 * @version 2.1
 */
@Component(immediate = true,
           service = { ObservableAgent.class, AgentEndpoint.class })
@Designate(ocd = PVPanelAgent.Config.class, factory = true)
public class PVPanelAgent
    extends BaseAgentEndpoint
    implements AgentEndpoint {

    private static final Logger LOGGER = LoggerFactory.getLogger(PVPanelAgent.class);

    private static Random generator = new Random();

    @ObjectClassDefinition
    public @interface Config {
        @AttributeDefinition(description = "The unique identifier of the agent")
        String agentId() default "pvpanel";

        @AttributeDefinition(description = "The agent identifier of the parent matcher to which this agent should be connected")
        public String desiredParentId() default "concentrator";

        @AttributeDefinition(type = AttributeType.LONG, description = "Number of seconds between bid updates")
        long bidUpdateRate() default 30L;

        @AttributeDefinition(type = AttributeType.DOUBLE, description = "The mimimum value of the random demand.")
        double minimumDemand() default -700d;

        @AttributeDefinition(type = AttributeType.DOUBLE, description = "The maximum value the random demand.")
        double maximumDemand() default -600d;
    }

    /**
     * A delayed result-bearing action that can be cancelled.
     */
    private ScheduledFuture<?> scheduledFuture;

    /**
     * The mimimum value of the random demand.
     */
    private double minimumDemand;

    /**
     * The maximum value the random demand.
     */
    private double maximumDemand;

    private Config config;

    /**
     * OSGi calls this method to activate a managed service.
     *
     * @param properties
     *            the configuration properties
     */
    @Activate
    public void activate(final Config config) {
        init(config.agentId(), config.desiredParentId());

        this.config = config;
        minimumDemand = config.minimumDemand();
        maximumDemand = config.maximumDemand();

        LOGGER.info("Agent [{}], activated", config.agentId());
    }

    /**
     * OSGi calls this method to deactivate a managed service.
     */
    @Override
    @Deactivate
    public void deactivate() {
        super.deactivate();
        scheduledFuture.cancel(false);
        LOGGER.info("Agent [{}], deactivated", getAgentId());
    }

    /**
     * {@inheritDoc}
     */
    void doBidUpdate() {
        AgentEndpoint.Status currentStatus = getStatus();
        if (currentStatus.isConnected()) {
            double demand = minimumDemand + (maximumDemand - minimumDemand) * generator.nextDouble();
            publishBid(Bid.flatDemand(currentStatus.getMarketBasis(), demand));
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public synchronized void handlePriceUpdate(PriceUpdate priceUpdate) {
        super.handlePriceUpdate(priceUpdate);
        // Nothing to control for a PV panel
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setContext(FlexiblePowerContext context) {
        super.setContext(context);
        scheduledFuture = context.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                doBidUpdate();
            }
        }, Measure.valueOf(0, SI.SECOND), Measure.valueOf(config.bidUpdateRate(), SI.SECOND));
    }
}
