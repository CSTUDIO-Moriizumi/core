/*
 * JBoss, Home of Professional Open Source
 * Copyright 2014, Red Hat Middleware LLC, and individual contributors
 * by the @authors tag. See the copyright.txt in the distribution for a
 * full listing of individual contributors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.weld.environment.servlet;

import java.util.Set;

import javax.servlet.ServletContainerInitializer;
import javax.servlet.ServletContext;
import javax.servlet.ServletContextEvent;
import javax.servlet.ServletException;
import javax.servlet.ServletRequestEvent;
import javax.servlet.http.HttpSessionEvent;

import org.jboss.logging.Logger;
import org.jboss.weld.servlet.api.ServletListener;
import org.jboss.weld.servlet.api.helpers.ForwardingServletListener;

/**
 * This listener also implements {@link ServletContainerInitializer} so that it's able to boot Weld before any application code is called, and thus injections
 * will succeed for all listeners, servlets, filters etc.
 *
 * This listener MUST NOT be defined in web.xml because it registers itself during {@link ServletContainerInitializer#onStartup(Set, ServletContext)}
 * notification!
 *
 * ServletRequest and HttpSession notifications are no-op in case of the {@link Listener} is registered as well.
 *
 * @author Martin Kouba
 * @author Jan Bartel
 * @author Pete Muir
 * @author Ales Justin
 * @see Listener
 */
public class EnhancedListener extends ForwardingServletListener implements ServletContainerInitializer {

    public static final String ENHANCED_LISTENER_USED_ATTRIBUTE_NAME = EnhancedListener.class.getPackage().getName() + ".enhancedListenerUsed";

    private static final Logger log = Logger.getLogger(EnhancedListener.class);

    private boolean isOriginalListenerUsed = false;

    private WeldServletLifecycle lifecycle;

    @Override
    public void onStartup(Set<Class<?>> classes, ServletContext context) throws ServletException {
        log.info("Initialize Weld using ServletContainerInitializer");
        context.setAttribute(ENHANCED_LISTENER_USED_ATTRIBUTE_NAME, Boolean.TRUE);
        lifecycle = new WeldServletLifecycle();
        lifecycle.initialize(context, null);
        context.setAttribute(WeldServletLifecycle.INSTANCE_ATTRIBUTE_NAME, lifecycle);
        context.addListener(this);
        super.contextInitialized(new ServletContextEvent(context));
    }

    @Override
    public void contextInitialized(ServletContextEvent sce) {
        // This listener will be always notified after org.jboss.weld.environment.servlet.Listener (if registered)
        if (Boolean.TRUE.equals(sce.getServletContext().getAttribute(Listener.LISTENER_USED_ATTRIBUTE_NAME))) {
            isOriginalListenerUsed = true;
            log.info("org.jboss.weld.environment.servlet.Listener used for ServletRequest and HttpSession notifications");
        }
    }

    @Override
    public void contextDestroyed(ServletContextEvent sce) {
        super.contextDestroyed(sce);
        lifecycle.destroy(sce.getServletContext());
    }

    @Override
    public void requestDestroyed(ServletRequestEvent sre) {
        if (isOriginalListenerUsed) {
            return;
        }
        super.requestDestroyed(sre);
    }

    @Override
    public void requestInitialized(ServletRequestEvent sre) {
        if (isOriginalListenerUsed) {
            return;
        }
        super.requestInitialized(sre);
    }

    @Override
    public void sessionCreated(HttpSessionEvent se) {
        if (isOriginalListenerUsed) {
            return;
        }
        super.sessionCreated(se);
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent se) {
        if (isOriginalListenerUsed) {
            return;
        }
        super.sessionDestroyed(se);
    }

    @Override
    protected ServletListener delegate() {
        return lifecycle.getWeldListener();
    }

}
