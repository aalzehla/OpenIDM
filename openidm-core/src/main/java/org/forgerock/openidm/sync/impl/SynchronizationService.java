/*
 * The contents of this file are subject to the terms of the Common Development and
 * Distribution License (the License). You may not use this file except in compliance with the
 * License.
 *
 * You can obtain a copy of the License at legal/CDDLv1.0.txt. See the License for the
 * specific language governing permission and limitations under the License.
 *
 * When distributing Covered Software, include this CDDL Header Notice in each file and include
 * the License file at legal/CDDLv1.0.txt. If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]".
 *
 * Copyright © 2011 ForgeRock AS. All rights reserved.
 */

package org.forgerock.openidm.sync.impl;

// Java Standard Edition
import java.io.InputStream;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

// OSGi Framework
import org.forgerock.openidm.context.InvokeContext;
import org.osgi.service.component.ComponentContext;
import org.osgi.service.component.ComponentException;

// Apache Felix Maven SCR Plugin
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.ConfigurationPolicy;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;

// JSON-Fluent library
import org.forgerock.json.fluent.JsonNode;
import org.forgerock.json.fluent.JsonNodeException;

// ForgeRock OpenIDM
import org.forgerock.openidm.config.JSONEnhancedConfig;
import org.forgerock.openidm.crypto.CryptoService;
import org.forgerock.openidm.objset.ObjectSet;
import org.forgerock.openidm.scheduler.ExecutionException;
import org.forgerock.openidm.scheduler.ScheduledService;
import org.forgerock.openidm.sync.SynchronizationException;
import org.forgerock.openidm.sync.SynchronizationListener;
import org.forgerock.openidm.scope.ObjectSetFunctions;

/**
 * TODO: Description.
 *
 * @author Paul C. Bryan
 */
@Component(
    name = "org.forgerock.openidm.sync",
    policy = ConfigurationPolicy.REQUIRE,
    immediate = true
)
@Properties({
    @Property(name = "service.description", value = "OpenIDM object synchronization service"),
    @Property(name = "service.vendor", value = "ForgeRock AS"),
    @Property(name = "openidm.osgi.shell.group.id", value = "sync"),
    @Property(name = "openidm.osgi.shell.commands", value = {
                "oncreate#Call the onCreate(<arg>,null)",
                "onupdate#Call the onUpdate(<arg>,null,null)",
                "ondelete#Call the onDelete(<arg>)"})
})
@Service
public class SynchronizationService implements SynchronizationListener, ScheduledService {

    /** Object mappings. Order of mappings evaluated during synchronization is significant. */
    private final ArrayList<ObjectMapping> mappings = new ArrayList<ObjectMapping>();

    /** TODO: Description. */
    private ComponentContext context;

    /** Object set router service. */
    @Reference(
        name = "ref_SynchronizationService_ObjectSetRouterService",
        referenceInterface = ObjectSet.class,
        bind = "bindRouter",
        unbind = "unbindRouter",
        cardinality = ReferenceCardinality.MANDATORY_UNARY,
        policy = ReferencePolicy.STATIC,
        target = "(service.pid=org.forgerock.openidm.router)"
    )
    private ObjectSet router;
    protected void bindRouter(ObjectSet router) {
        this.router = router;
    }
    protected void unbindRouter(ObjectSet router) {
        this.router = null;
    }

    /** Cryptographic service. */
    @Reference(
        name="ref_SynchronizationService_CryptoService",
        referenceInterface=CryptoService.class,
        bind="bindCryptoService",
        unbind="unbindCryptoService",
        cardinality = ReferenceCardinality.MANDATORY_UNARY,
        policy = ReferencePolicy.STATIC
    )
    protected CryptoService cryptoService;
    protected void bindCryptoService(CryptoService cryptoService) {
        this.cryptoService = cryptoService;
    }
    protected void unbindCryptoService(CryptoService cryptoService) {
        this.cryptoService = null;
    }

    @Activate
    protected void activate(ComponentContext context) {
        this.context = context;
        JsonNode config = new JsonNode(new JSONEnhancedConfig().getConfiguration(context));
        try {
            for (JsonNode node : config.get("mappings").expect(List.class)) {
                mappings.add(new ObjectMapping(this, node)); // throws JsonNodeException
            }
        } catch (JsonNodeException jne) {
            throw new ComponentException("Configuration error", jne);
        }
    }

    @Deactivate
    protected void deactivate(ComponentContext context) {
        mappings.clear();
        this.context = null;
    }

    /**
     * TODO: Description.
     *
     * @param name TODO.
     * @return TODO.
     * @throws org.forgerock.openidm.sync.SynchronizationException
     */
    ObjectMapping getMapping(String name) throws SynchronizationException {
        for (ObjectMapping mapping : mappings) {
            if (mapping.getName().equals(name)) {
                return mapping;
            }
        }
        throw new SynchronizationException("No such mapping: " + name);
    }

    /**
     * TODO: Description.
     *
     * @throws SynchronizationException TODO.
     * @return
     */
    ObjectSet getRouter() throws SynchronizationException {
        if (router == null) {
            throw new SynchronizationException("Not bound to internal router");
        }
        return router;
    }

    /**
     * TODO: Description.
     *
     * @return TODO.
     * @throws SynchronizationException TODO.
     */
    Map<String, Object> newScope()  throws SynchronizationException {
        return ObjectSetFunctions.addToScope(new HashMap<String, Object>(), getRouter());
    }

    @Override
    public void onCreate(String id, JsonNode object) throws SynchronizationException {
        for (ObjectMapping mapping : mappings) {
            mapping.onCreate(id, object);
        }
    }

    @Override
    public void onUpdate(String id, JsonNode oldValue, JsonNode newValue)
    throws SynchronizationException {
        for (ObjectMapping mapping : mappings) {
            mapping.onUpdate(id, oldValue, newValue);
        }
    }

    @Override
    public void onDelete(String id) throws SynchronizationException {
        for (ObjectMapping mapping : mappings) {
            mapping.onDelete(id);
        }
    }

    @Override
    public void execute(Map<String, Object> context) throws ExecutionException {
        try {
            JsonNode params = new JsonNode(context).get(CONFIGURED_INVOKE_CONTEXT);
            String action = params.get("action").asString();
            if ("reconcile".equals(action)) { // "action": "reconcile"
                reconcile(params.get("mapping").asString()); // "mapping": string (mapping name)
            } else {
                throw new ExecutionException("Unknown action '" + action + "' configured in schedule. "
                        + "valid action(s) are: 'reconcile'");
            }
        } catch (JsonNodeException jne) {
            throw new ExecutionException(jne);
        } catch (SynchronizationException se) {
            throw new ExecutionException(se);
        }
    }

    /**
     * TODO: Description.
     *
     * @param mapping TODO.
     * @throws SynchronizationException TODO.
     * @return
     */
    public String reconcile(String mapping) throws SynchronizationException {
        String reconId = UUID.randomUUID().toString();
        getMapping(mapping).recon(reconId); // throws SynchronizationException
        return reconId;
    }

    /**
     * TODO: Description.
     * @return
     */
    CryptoService getCryptoService() {
        return cryptoService;
    }

    /**
     * Shell Commands
     */
    public void oncreate(InputStream in, PrintStream out, String[] args) throws Exception {
        PrintWriter output = new PrintWriter(out);
        try {
            if (args.length > 0) {
                String activityId = UUID.randomUUID().toString();
                InvokeContext.getContext().pushActivityId(activityId);
                try {
                    output.println("Execute activityId: " + activityId);
                    this.onCreate(args[0], null);
                } catch (SynchronizationException e) {
                    output.append("Error:").append(e.getMessage());
                } finally {
                    InvokeContext.getContext().popActivityId();
                }
            } else {
                output.println("Usage: oncreate <id>");
            }
        } finally {
            output.flush();
        }
    }

    public void onupdate(InputStream in, PrintStream out, String[] args) throws Exception {
        PrintWriter output = new PrintWriter(out);
        try {
            if (args.length > 0) {
                String activityId = UUID.randomUUID().toString();
                InvokeContext.getContext().pushActivityId(activityId);
                try {
                    out.println("Execute activityId: " + activityId);
                    this.onUpdate(args[0], null, null);
                } catch (SynchronizationException e) {
                    output.append("Error:").append(e.getMessage());
                } finally {
                    InvokeContext.getContext().popActivityId();
                }
            } else {
                out.println("Usage: onupdate <id>");
            }
        } finally {
            output.flush();
        }
    }

    public void ondelete(InputStream in, PrintStream out, String[] args) throws Exception {
        PrintWriter output = new PrintWriter(out);
        try {
            if (args.length > 0) {
                String activityId = UUID.randomUUID().toString();
                InvokeContext.getContext().pushActivityId(activityId);
                try {
                    output.println("Execute activityId: " + activityId);
                    this.onDelete(args[0]);
                } catch (SynchronizationException e) {
                    output.append("Error:").append(e.getMessage());
                } finally {
                    InvokeContext.getContext().popActivityId();
                }
            } else {
                output.println("Usage: ondelete <id>");
            }
        } finally {
            output.flush();
        }
    }
}
