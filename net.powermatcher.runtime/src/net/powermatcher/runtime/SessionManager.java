package net.powermatcher.runtime;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListSet;

import org.flexiblepower.context.FlexiblePowerContext;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.component.annotations.Reference;
import org.osgi.service.component.annotations.ReferenceCardinality;
import org.osgi.service.component.annotations.ReferencePolicy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.powermatcher.api.Agent;
import net.powermatcher.api.AgentEndpoint;
import net.powermatcher.api.MatcherEndpoint;
import net.powermatcher.api.Session;

/**
 * The {@link SessionManager} is an OSGi Component which is responsible for connecting PowerMatcher Agents. To be
 * specific, it will connect {@link MatcherEndpoint}s with {@link AgentEndpoint}. Connections are based on the agentId
 * of the {@link MatcherEndpoint} and the desiredParentId of the {@link AgentEndpoint}. Connections are represented by
 * an {@link Session} instance.
 *
 * @author FAN
 * @version 2.1
 */
@Component(immediate = true)
public class SessionManager {

    private static final Logger LOGGER = LoggerFactory.getLogger(SessionManager.class);

    // This part is for giving each agent an instance of the PowerMatcherContext without setting it twice
    private final FlexiblePowerContext runtimeContext = new PowerMatcherContext();
    private final Set<String> agents = new ConcurrentSkipListSet<String>();

    private void addAgent(Agent agent) {
        if (agents.add(agent.getAgentId())) {
            agent.setContext(runtimeContext);
        }
    }

    private void removeAgent(Agent agent) {
        agents.remove(agent.getAgentId());
    }

    /**
     * Data structure with all the {@link PotentialSession}s. Key of the map is the agentId, the value is a list with
     * all the {@link PotentialSession}s with that agent.
     */
    private final Map<String, List<PotentialSession>> potentialSessions = new HashMap<String, List<PotentialSession>>();

    /**
     * Map with references to all the known {@link MatcherEndpoint}s. Key of the map is the agentId.
     */
    private final Map<String, MatcherEndpoint> matcherEndpoints = new HashMap<String, MatcherEndpoint>();

    /**
     * Informs the SessionManager that there is a new {@link MatcherEndpoint}.
     *
     * @param matcherEndpoint
     *            the new {@link MatcherEndpoint}
     */
    @Reference(policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    public void addMatcherEndpoint(MatcherEndpoint matcherEndpoint) {
        addAgent(matcherEndpoint);

        String agentId = matcherEndpoint.getAgentId();
        synchronized (potentialSessions) {
            // Check for duplicate
            if (matcherEndpoints.containsKey(agentId)) {
                LOGGER.warn("MatcherEndpoint added with agentId " + agentId
                            + ", but it already exists. Ignoring the new one...");
                return;
            }

            if (!potentialSessions.containsKey(agentId)) {
                potentialSessions.put(agentId, new ArrayList<PotentialSession>());
            }
            matcherEndpoints.put(agentId, matcherEndpoint);

            for (PotentialSession ps : potentialSessions.get(agentId)) {
                ps.setMatcherEndpoint(matcherEndpoint);
            }

            LOGGER.debug("MatcherEndpoint with id [{}] added", agentId);
        }

        tryConnect();
    }

    /**
     * Informs the SessionManager that a {@link MatcherEndpoint} has been removed. It will disconnect any existing
     * Sessions with the {@link MatcherEndpoint}.
     *
     * @param matcherEndpoint
     *            the {@link MatcherEndpoint} to be removed
     */
    public void removeMatcherEndpoint(MatcherEndpoint matcherEndpoint) {
        removeAgent(matcherEndpoint);

        String agentId = matcherEndpoint.getAgentId();

        synchronized (potentialSessions) {
            for (PotentialSession ps : potentialSessions.get(agentId)) {
                // PotentialSessions are disconnected, but are not removed
                ps.disconnect();
                ps.setMatcherEndpoint(null);
            }
            matcherEndpoints.remove(agentId);
        }
    }

    /**
     * Informs the SessionManager that there is a new {@link AgentEndpoint}.
     *
     * @param agentEndpoint
     *            the new {@link AgentEndpoint}
     */
    @Reference(policy = ReferencePolicy.DYNAMIC, cardinality = ReferenceCardinality.MULTIPLE)
    public void addAgentEndpoint(AgentEndpoint agentEndpoint) {
        addAgent(agentEndpoint);

        String agentId = agentEndpoint.getAgentId();
        String matcherId = agentEndpoint.getDesiredParentId();
        synchronized (potentialSessions) {
            if (!potentialSessions.containsKey(matcherId)) {
                potentialSessions.put(matcherId, new ArrayList<PotentialSession>());
            }
            // Check if it already exists
            for (PotentialSession ps : potentialSessions.get(matcherId)) {
                if (agentId.equals(ps.getAgentId())) {
                    LOGGER.warn("AgentEndpoint added with agentId {}, but it already exists. Ignoring the new one...",
                                agentId);
                    return;
                }
            }

            PotentialSession ps = new PotentialSession(agentEndpoint, runtimeContext);
            ps.setMatcherEndpoint(matcherEndpoints.get(matcherId));
            potentialSessions.get(matcherId).add(ps);
            LOGGER.debug("AgentEndpoint with id [{}] added", agentId);
        }

        tryConnect();
    }

    /**
     * Informs the SessionManager that an {@link AgentEndpoint} has been removed. It will disconnect any existing
     * Sessions with the {@link AgentEndpoint}.
     *
     * @param agentEndpoint
     *            the {@link AgentEndpoint} to be removed
     */
    public void removeAgentEndpoint(AgentEndpoint agentEndpoint) {
        removeAgent(agentEndpoint);

        String agentId = agentEndpoint.getAgentId();
        String matcherId = agentEndpoint.getDesiredParentId();
        PotentialSession currentSession = null;
        synchronized (potentialSessions) {
            Iterator<PotentialSession> it = potentialSessions.get(matcherId).iterator();
            while (it.hasNext()) {
                PotentialSession ps = it.next();
                if (agentId.equals(ps.getAgentId())) {
                    currentSession = ps;
                    it.remove();
                    break;
                }
            }
        }
        if (currentSession != null) {
            currentSession.disconnect();
        }
    }

    /**
     * See if there is a {@link PotentialSession} that can be connected. Since one new Session can lead to another, this
     * is tried until nothing changes.
     */
    private void tryConnect() {
        // TODO do something more efficient? We could build some tree and try to connect agents from top to bottom.
        boolean somethingChanged;
        do {
            somethingChanged = false;
            synchronized (potentialSessions) {
                for (List<PotentialSession> list : potentialSessions.values()) {
                    for (PotentialSession ps : list) {
                        if (ps.tryConnect()) {
                            somethingChanged = true;
                        }
                    }
                }
            }
        } while (somethingChanged);
    }
}
