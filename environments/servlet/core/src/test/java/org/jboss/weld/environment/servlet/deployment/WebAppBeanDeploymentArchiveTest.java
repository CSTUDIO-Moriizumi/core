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
package org.jboss.weld.environment.servlet.deployment;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.Enumeration;
import java.util.EventListener;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.servlet.Filter;
import javax.servlet.FilterRegistration;
import javax.servlet.RequestDispatcher;
import javax.servlet.Servlet;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.ServletRegistration;
import javax.servlet.ServletRegistration.Dynamic;
import javax.servlet.SessionCookieConfig;
import javax.servlet.SessionTrackingMode;
import javax.servlet.descriptor.JspConfigDescriptor;

import org.junit.Test;

/**
 *
 * @author Martin Kouba
 */
public class WebAppBeanDeploymentArchiveTest {

    @Test
    public void testHandleResourcePath() {
        Set<String> classes = new HashSet<String>();
        WebAppBeanDeploymentArchive.handleResourcePath(WebAppBeanDeploymentArchive.WEB_INF_CLASSES, classes, new ServletContextMock());
        assertEquals(4, classes.size());
        assertTrue(classes.contains("org.Alpha"));
        assertTrue(classes.contains("org.foo.Bravo"));
        assertTrue(classes.contains("org.foo.Charlie"));
        assertTrue(classes.contains("org.bar.Delta"));
    }

    static class ServletContextMock implements ServletContext {

        @Override
        public String getContextPath() {

            return null;
        }

        @Override
        public ServletContext getContext(String uripath) {

            return null;
        }

        @Override
        public int getMajorVersion() {

            return 0;
        }

        @Override
        public int getMinorVersion() {

            return 0;
        }

        @Override
        public int getEffectiveMajorVersion() {

            return 0;
        }

        @Override
        public int getEffectiveMinorVersion() {

            return 0;
        }

        @Override
        public String getMimeType(String file) {

            return null;
        }

        @Override
        public Set<String> getResourcePaths(String path) {
            if (WebAppBeanDeploymentArchive.WEB_INF_CLASSES.equals(path)) {
                return toWebInfSet("/org/", "/components.xml");
            } else if (toWebInfPath("/org/").equals(path)) {
                return toWebInfSet("/org/foo/", "/org/bar/", "/org/Alpha.class");
            } else if (toWebInfPath("/org/foo/").equals(path)) {
                return toWebInfSet("/org/foo/Bravo.class", "/org/foo/Charlie.class");
            } else if (toWebInfPath("/org/bar/").equals(path)) {
                return toWebInfSet("/org/bar/Delta.class", "/org/bar/dummy.properties");
            }
            return null;
        }

        @Override
        public URL getResource(String path) throws MalformedURLException {

            return null;
        }

        @Override
        public InputStream getResourceAsStream(String path) {

            return null;
        }

        @Override
        public RequestDispatcher getRequestDispatcher(String path) {

            return null;
        }

        @Override
        public RequestDispatcher getNamedDispatcher(String name) {

            return null;
        }

        @Override
        public Servlet getServlet(String name) throws ServletException {

            return null;
        }

        @Override
        public Enumeration<Servlet> getServlets() {

            return null;
        }

        @Override
        public Enumeration<String> getServletNames() {

            return null;
        }

        @Override
        public void log(String msg) {

        }

        @Override
        public void log(Exception exception, String msg) {

        }

        @Override
        public void log(String message, Throwable throwable) {

        }

        @Override
        public String getRealPath(String path) {

            return null;
        }

        @Override
        public String getServerInfo() {

            return null;
        }

        @Override
        public String getInitParameter(String name) {

            return null;
        }

        @Override
        public Enumeration<String> getInitParameterNames() {

            return null;
        }

        @Override
        public boolean setInitParameter(String name, String value) {

            return false;
        }

        @Override
        public Object getAttribute(String name) {

            return null;
        }

        @Override
        public Enumeration<String> getAttributeNames() {

            return null;
        }

        @Override
        public void setAttribute(String name, Object object) {

        }

        @Override
        public void removeAttribute(String name) {

        }

        @Override
        public String getServletContextName() {

            return null;
        }

        @Override
        public Dynamic addServlet(String servletName, String className) {

            return null;
        }

        @Override
        public Dynamic addServlet(String servletName, Servlet servlet) {

            return null;
        }

        @Override
        public Dynamic addServlet(String servletName, Class<? extends Servlet> servletClass) {

            return null;
        }

        @Override
        public <T extends Servlet> T createServlet(Class<T> clazz) throws ServletException {

            return null;
        }

        @Override
        public ServletRegistration getServletRegistration(String servletName) {

            return null;
        }

        @Override
        public Map<String, ? extends ServletRegistration> getServletRegistrations() {

            return null;
        }

        @Override
        public javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, String className) {

            return null;
        }

        @Override
        public javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, Filter filter) {

            return null;
        }

        @Override
        public javax.servlet.FilterRegistration.Dynamic addFilter(String filterName, Class<? extends Filter> filterClass) {

            return null;
        }

        @Override
        public <T extends Filter> T createFilter(Class<T> clazz) throws ServletException {

            return null;
        }

        @Override
        public FilterRegistration getFilterRegistration(String filterName) {

            return null;
        }

        @Override
        public Map<String, ? extends FilterRegistration> getFilterRegistrations() {

            return null;
        }

        @Override
        public SessionCookieConfig getSessionCookieConfig() {

            return null;
        }

        @Override
        public void setSessionTrackingModes(Set<SessionTrackingMode> sessionTrackingModes) {

        }

        @Override
        public Set<SessionTrackingMode> getDefaultSessionTrackingModes() {

            return null;
        }

        @Override
        public Set<SessionTrackingMode> getEffectiveSessionTrackingModes() {

            return null;
        }

        @Override
        public void addListener(String className) {

        }

        @Override
        public <T extends EventListener> void addListener(T t) {

        }

        @Override
        public void addListener(Class<? extends EventListener> listenerClass) {

        }

        @Override
        public <T extends EventListener> T createListener(Class<T> clazz) throws ServletException {

            return null;
        }

        @Override
        public JspConfigDescriptor getJspConfigDescriptor() {

            return null;
        }

        @Override
        public ClassLoader getClassLoader() {

            return null;
        }

        @Override
        public void declareRoles(String... roleNames) {

        }

    }

    private static Set<String> toWebInfSet(String... paths) {
        Set<String> set = new HashSet<String>();
        for (String path : paths) {
            set.add(WebAppBeanDeploymentArchive.WEB_INF_CLASSES + path);
        }
        return set;
    }

    private static String toWebInfPath(String path) {
        return WebAppBeanDeploymentArchive.WEB_INF_CLASSES + path;
    }

}
