package net.powermatcher.examples;

import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.powermatcher.api.monitoring.AgentObserver;
import net.powermatcher.api.monitoring.ObservableAgent;
import net.powermatcher.api.monitoring.events.AgentEvent;

/**
 * {@link ConsoleObserver} is an example implementation of the {@link BaseObserver} interface. You can add
 * {@link ObservableAgent}s and it can receive {@link AgentEvent}s from them.
 *
 * @author FAN
 * @version 2.1
 */
@Component(immediate = true)
@Designate(ocd = ConsoleObserver.Config.class)
public class ConsoleObserver
    implements AgentObserver {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConsoleObserver.class);

    /**
     * This interface describes the configuration of this {@link ConsoleObserver}. It defines the filter for the
     * {@link ObservableAgent}s that are needed.
     */
    @ObjectClassDefinition
    public @interface Config {
        @AttributeDefinition(required = false,
                             description = "The LDAP filter for the ObservableAgents that we want to monitor. "
                                           + "E.g. '(agentId=auctioneer)'")
        String observableAgent_filter() default "";
    }

    /**
     * Adds an {@link ObservableAgent} to this {@link ConsoleObserver}. This will register itself with the object.
     * Normally this should be called by the OSGi platform using DS. This method has no effect if this was already
     * registered.
     *
     * @param observable
     *            The {@link ObservableAgent} that it should be registered on.
     */
    @Reference(policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    public void addObservableAgent(ObservableAgent observable) {
        observable.addObserver(this);
    }

    /**
     * Removes an {@link ObservableAgent} from this {@link ConsoleObserver}. This will unregister itself with the
     * object. Normally this should be called by the OSGi platform using DS. This method has no effect if this wasn't
     * already registered.
     *
     * @param observable
     *            The {@link ObservableAgent} that it should unregister from.
     */
    public void removeObservableAgent(ObservableAgent observable) {
        observable.removeObserver(this);
    }

    /**
     * Prints the {@link AgentEvent} to the logging using its toString() method.
     *
     * @param event
     *            The {@link AgentEvent} that is to be printed.
     */
    @Override
    public void handleAgentEvent(AgentEvent event) {
        LOGGER.info("Received event: {}", event);
    }
}
