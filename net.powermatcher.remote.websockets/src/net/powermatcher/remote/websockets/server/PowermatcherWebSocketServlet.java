package net.powermatcher.remote.websockets.server;

import javax.servlet.Servlet;
import javax.servlet.ServletException;

import org.eclipse.jetty.websocket.servlet.ServletUpgradeRequest;
import org.eclipse.jetty.websocket.servlet.ServletUpgradeResponse;
import org.eclipse.jetty.websocket.servlet.WebSocketCreator;
import org.eclipse.jetty.websocket.servlet.WebSocketServlet;
import org.eclipse.jetty.websocket.servlet.WebSocketServletFactory;
import org.osgi.framework.BundleContext;
import org.osgi.service.component.annotations.Activate;
import org.osgi.service.component.annotations.Component;
import org.osgi.service.metatype.annotations.AttributeDefinition;
import org.osgi.service.metatype.annotations.Designate;
import org.osgi.service.metatype.annotations.ObjectClassDefinition;

/**
 * Servlet which activates the PowerMatcher WebSocket communication.
 *
 * @author FAN
 * @version 2.1
 */
@Component(service = Servlet.class)
@Designate(ocd = PowermatcherWebSocketServlet.Config.class, factory = true)
public class PowermatcherWebSocketServlet
    extends WebSocketServlet
    implements WebSocketCreator {
    private static final long serialVersionUID = -8809366066221881974L;

    @ObjectClassDefinition
    public @interface Config {
        @AttributeDefinition(description = "The agent identifier of the parent matcher to which "
                                           + "agent proxies should be connected ")
        String desiredParentId() default "concentrator";

        @AttributeDefinition(description = "The path of the URL on which this servlet can be reached")
        String alias() default "/powermatcher/websocket";
    }

    private String desiredParentId;
    private BundleContext bundleContext;

    @Activate
    public void activate(BundleContext bundleContext, final Config config) {
        this.bundleContext = bundleContext;
        desiredParentId = config.desiredParentId();
    }

    @Override
    public void init() throws ServletException {
        // Hack to make sure that the WebsocketServerFactory is loaded with the correct ClassLoader
        ClassLoader old = Thread.currentThread().getContextClassLoader();
        try {
            Thread.currentThread().setContextClassLoader(WebSocketServletFactory.class.getClassLoader());
            super.init();
        } finally {
            Thread.currentThread().setContextClassLoader(old);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void configure(WebSocketServletFactory wssf) {
        wssf.setCreator(this);
    }

    @Override
    public Object createWebSocket(ServletUpgradeRequest req, ServletUpgradeResponse resp) {
        return new AgentEndpointProxy(bundleContext, desiredParentId);
    }
}
