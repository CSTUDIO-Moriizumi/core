/*
 * JBoss, Home of Professional Open Source
 * Copyright 2008, Red Hat Middleware LLC, and individual contributors
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
package org.jboss.weld.environment.servlet.jsf;

import javax.el.ELContextListener;
import javax.el.ELResolver;
import javax.el.ExpressionFactory;
import javax.enterprise.inject.spi.BeanManager;
import javax.faces.application.Application;
import javax.faces.context.FacesContext;
import javax.servlet.ServletContext;

import org.jboss.weld.environment.servlet.WeldServletLifecycle;
import org.jboss.weld.environment.servlet.portlet.PortletSupport;
import org.jboss.weld.environment.servlet.util.ForwardingELResolver;
import org.jboss.weld.environment.servlet.util.Reflections;
import org.jboss.weld.environment.servlet.util.TransparentELResolver;

/**
 * @author Pete Muir
 * @author Dan Allen
 * @author Ales Justin
 */
public class WeldApplication extends ForwardingApplication {
    /**
     * The BeanManager may not have been initialized at the time JSF is initializing. Therefore,
     * we stick in a ForwardingELResolver that delegates to the BeanManager ELResolver, which will
     * be plugged in when it's available. If the ELResolver is invoked before the BeanManager
     * is available, the resolver will perform no action (and thus produce no result).
     */
    private static class LazyBeanManagerIntegrationELResolver extends ForwardingELResolver {
        private ELResolver delegate;

        public LazyBeanManagerIntegrationELResolver() {
            delegate = new TransparentELResolver();
        }

        public void beanManagerReady(BeanManager beanManager) {
            this.delegate = beanManager.getELResolver();
        }

        @Override
        protected ELResolver delegate() {
            return delegate;
        }
    }

    private final Application application;
    private LazyBeanManagerIntegrationELResolver elResolver;
    private ExpressionFactory expressionFactory;
    private BeanManager beanManager;

    public WeldApplication(Application application) {
        this.application = application;
        // QUESTION should the context listener be registered in init() instead?
        application.addELContextListener(Reflections.<ELContextListener>newInstance("org.jboss.weld.el.WeldELContextListener"));
        elResolver = new LazyBeanManagerIntegrationELResolver();
        application.addELResolver(elResolver);
    }

    private void init() {
        ExpressionFactory expressionFactory = this.expressionFactory;
        BeanManager beanManager = null;
        if (expressionFactory == null && (expressionFactory = application.getExpressionFactory()) != null && (beanManager = beanManager()) != null) {
            elResolver.beanManagerReady(beanManager);
            this.expressionFactory = beanManager.wrapExpressionFactory(expressionFactory);
        }
    }

    @Override
    protected Application delegate() {
        init();
        return application;
    }

    @Override
    public ExpressionFactory getExpressionFactory() {
        init();
        if (expressionFactory == null) {
            return application.getExpressionFactory();
        } else {
            return expressionFactory;
        }
    }

    private BeanManager beanManager() {
        FacesContext facesContext;
        if (beanManager == null && (facesContext = FacesContext.getCurrentInstance()) != null) {
            Object obj = facesContext.getExternalContext().getContext();
            boolean notFound = false;
            try {
                if (obj instanceof ServletContext) {
                    final ServletContext ctx = (ServletContext) obj;
                    final BeanManager tmp = (BeanManager) ctx.getAttribute(WeldServletLifecycle.BEAN_MANAGER_ATTRIBUTE_NAME);
                    if (tmp == null) {
                        return null;
                    }
                    this.beanManager = tmp;
                } else if (PortletSupport.isPortletEnvSupported() && PortletSupport.isPortletContext(obj)) {
                    final BeanManager tmp = PortletSupport.getBeanManager(obj);
                    if (tmp == null) {
                        return null;
                    }
                    this.beanManager = tmp;
                } else {
                    notFound = true;
                }
            } catch (Throwable t) {
                throw new IllegalStateException("Exception fetching BeanManager instance!", t);
            }
            if (notFound) {
                throw new IllegalStateException("Not in a servlet or portlet environment!");
            }
        }
        return beanManager;
    }

}
