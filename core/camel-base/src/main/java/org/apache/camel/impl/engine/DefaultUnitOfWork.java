/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.camel.impl.engine;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import org.apache.camel.AsyncCallback;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.Message;
import org.apache.camel.Processor;
import org.apache.camel.Route;
import org.apache.camel.Service;
import org.apache.camel.spi.RouteContext;
import org.apache.camel.spi.Synchronization;
import org.apache.camel.spi.SynchronizationVetoable;
import org.apache.camel.spi.UnitOfWork;
import org.apache.camel.support.DefaultMessage;
import org.apache.camel.support.EventHelper;
import org.apache.camel.support.MessageSupport;
import org.apache.camel.support.UnitOfWorkHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The default implementation of {@link org.apache.camel.spi.UnitOfWork}
 */
public class DefaultUnitOfWork implements UnitOfWork, Service {
    private static final Logger LOG = LoggerFactory.getLogger(DefaultUnitOfWork.class);

    // TODO: This implementation seems to have transformed itself into a to broad concern
    //   where unit of work is doing a bit more work than the transactional aspect that ties
    //   to its name. Maybe this implementation should be named ExchangeContext and we can
    //   introduce a simpler UnitOfWork concept. This would also allow us to refactor the
    //   SubUnitOfWork into a general parent/child unit of work concept. However this
    //   requires API changes and thus is best kept for future Camel work

    private String id;
    private final CamelContext context;
    // reduce the default size of the stack with the number of nested routes deep (8 level down)
    private final Deque<RouteContext> routeContextStack = new ArrayDeque<>(8);
    private final transient Logger log;
    private List<Synchronization> synchronizations;
    private Message originalInMessage;
    private Set<Object> transactedBy;

    public DefaultUnitOfWork(Exchange exchange) {
        this(exchange, LOG);
    }

    protected DefaultUnitOfWork(Exchange exchange, Logger logger) {
        log = logger;
        if (log.isTraceEnabled()) {
            log.trace("UnitOfWork created for ExchangeId: {} with {}", exchange.getExchangeId(), exchange);
        }

        context = exchange.getContext();

        if (context.isAllowUseOriginalMessage()) {
            // special for JmsMessage as it can cause it to loose headers later.
            if (exchange.getIn().getClass().getName().equals("org.apache.camel.component.jms.JmsMessage")) {
                this.originalInMessage = new DefaultMessage(context);
                this.originalInMessage.setBody(exchange.getIn().getBody());
                this.originalInMessage.getHeaders().putAll(exchange.getIn().getHeaders());
            } else {
                this.originalInMessage = exchange.getIn().copy();
            }
            // must preserve exchange on the original in message
            if (this.originalInMessage instanceof MessageSupport) {
                ((MessageSupport) this.originalInMessage).setExchange(exchange);
            }
        }

        // mark the creation time when this Exchange was created
        if (exchange.getProperty(Exchange.CREATED_TIMESTAMP) == null) {
            exchange.setProperty(Exchange.CREATED_TIMESTAMP, new Date());
        }

        // inject breadcrumb header if enabled
        if (context.isUseBreadcrumb()) {
            // create or use existing breadcrumb
            String breadcrumbId = exchange.getIn().getHeader(Exchange.BREADCRUMB_ID, String.class);
            if (breadcrumbId == null) {
                // no existing breadcrumb, so create a new one based on the exchange id
                breadcrumbId = exchange.getExchangeId();
                exchange.getIn().setHeader(Exchange.BREADCRUMB_ID, breadcrumbId);
            }
        }
        
        // setup whether the exchange is externally redelivered or not (if not initialized before)
        // store as property so we know that the origin exchange was redelivered
        if (exchange.getProperty(Exchange.EXTERNAL_REDELIVERED) == null) {
            Boolean redelivered = exchange.isExternalRedelivered();
            if (redelivered == null) {
                // not from a transactional resource so mark it as false by default
                redelivered = false;
            }
            exchange.setProperty(Exchange.EXTERNAL_REDELIVERED, redelivered);
        }

        // fire event
        try {
            EventHelper.notifyExchangeCreated(context, exchange);
        } catch (Throwable e) {
            // must catch exceptions to ensure the exchange is not failing due to notification event failed
            log.warn("Exception occurred during event notification. This exception will be ignored.", e);
        }

        // register to inflight registry
        context.getInflightRepository().add(exchange);
    }

    UnitOfWork newInstance(Exchange exchange) {
        return new DefaultUnitOfWork(exchange);
    }

    @Override
    public void setParentUnitOfWork(UnitOfWork parentUnitOfWork) {
    }

    @Override
    public UnitOfWork createChildUnitOfWork(Exchange childExchange) {
        // create a new child unit of work, and mark me as its parent
        UnitOfWork answer = newInstance(childExchange);
        answer.setParentUnitOfWork(this);
        return answer;
    }

    @Override
    public void start() {
        // noop
    }

    @Override
    public void stop() {
        // noop
    }

    @Override
    public synchronized void addSynchronization(Synchronization synchronization) {
        if (synchronizations == null) {
            synchronizations = new ArrayList<>(8);
        }
        log.trace("Adding synchronization {}", synchronization);
        synchronizations.add(synchronization);
    }

    @Override
    public synchronized void removeSynchronization(Synchronization synchronization) {
        if (synchronizations != null) {
            synchronizations.remove(synchronization);
        }
    }

    @Override
    public synchronized boolean containsSynchronization(Synchronization synchronization) {
        return synchronizations != null && synchronizations.contains(synchronization);
    }

    @Override
    public void handoverSynchronization(Exchange target) {
        handoverSynchronization(target, null);
    }

    @Override
    public void handoverSynchronization(Exchange target, Predicate<Synchronization> filter) {
        if (synchronizations == null || synchronizations.isEmpty()) {
            return;
        }

        Iterator<Synchronization> it = synchronizations.iterator();
        while (it.hasNext()) {
            Synchronization synchronization = it.next();

            boolean handover = true;
            if (synchronization instanceof SynchronizationVetoable) {
                SynchronizationVetoable veto = (SynchronizationVetoable) synchronization;
                handover = veto.allowHandover();
            }

            if (handover && (filter == null || filter.test(synchronization))) {
                log.trace("Handover synchronization {} to: {}", synchronization, target);
                target.addOnCompletion(synchronization);
                // remove it if its handed over
                it.remove();
            } else {
                log.trace("Handover not allow for synchronization {}", synchronization);
            }
        }
    }

    @Override
    public void done(Exchange exchange) {
        if (log.isTraceEnabled()) {
            log.trace("UnitOfWork done for ExchangeId: {} with {}", exchange.getExchangeId(), exchange);
        }

        boolean failed = exchange.isFailed();

        // at first done the synchronizations
        UnitOfWorkHelper.doneSynchronizations(exchange, synchronizations, log);

        // unregister from inflight registry, before signalling we are done
        if (exchange.getContext() != null) {
            exchange.getContext().getInflightRepository().remove(exchange);
        }

        // then fire event to signal the exchange is done
        try {
            if (failed) {
                EventHelper.notifyExchangeFailed(exchange.getContext(), exchange);
            } else {
                EventHelper.notifyExchangeDone(exchange.getContext(), exchange);
            }
        } catch (Throwable e) {
            // must catch exceptions to ensure synchronizations is also invoked
            log.warn("Exception occurred during event notification. This exception will be ignored.", e);
        }
    }

    @Override
    public void beforeRoute(Exchange exchange, Route route) {
        if (log.isTraceEnabled()) {
            log.trace("UnitOfWork beforeRoute: {} for ExchangeId: {} with {}", route.getId(), exchange.getExchangeId(), exchange);
        }
        UnitOfWorkHelper.beforeRouteSynchronizations(route, exchange, synchronizations, log);
    }

    @Override
    public void afterRoute(Exchange exchange, Route route) {
        if (log.isTraceEnabled()) {
            log.trace("UnitOfWork afterRoute: {} for ExchangeId: {} with {}", route.getId(), exchange.getExchangeId(), exchange);
        }
        UnitOfWorkHelper.afterRouteSynchronizations(route, exchange, synchronizations, log);
    }

    @Override
    public String getId() {
        if (id == null) {
            id = context.getUuidGenerator().generateUuid();
        }
        return id;
    }

    @Override
    public Message getOriginalInMessage() {
        if (originalInMessage == null && !context.isAllowUseOriginalMessage()) {
            throw new IllegalStateException("AllowUseOriginalMessage is disabled. Cannot access the original message.");
        }
        return originalInMessage;
    }

    @Override
    public boolean isTransacted() {
        return transactedBy != null && !transactedBy.isEmpty();
    }

    @Override
    public boolean isTransactedBy(Object key) {
        return transactedBy != null && getTransactedBy().contains(key);
    }

    @Override
    public void beginTransactedBy(Object key) {
        getTransactedBy().add(key);
    }

    @Override
    public void endTransactedBy(Object key) {
        getTransactedBy().remove(key);
    }

    @Override
    public RouteContext getRouteContext() {
        return routeContextStack.peek();
    }

    @Override
    public void pushRouteContext(RouteContext routeContext) {
        routeContextStack.push(routeContext);
    }

    @Override
    public RouteContext popRouteContext() {
        return routeContextStack.pollFirst();
    }

    @Override
    public AsyncCallback beforeProcess(Processor processor, Exchange exchange, AsyncCallback callback) {
        // no wrapping needed
        return callback;
    }

    @Override
    public void afterProcess(Processor processor, Exchange exchange, AsyncCallback callback, boolean doneSync) {
    }

    private Set<Object> getTransactedBy() {
        if (transactedBy == null) {
            transactedBy = new LinkedHashSet<>();
        }
        return transactedBy;
    }

    @Override
    public String toString() {
        return "DefaultUnitOfWork";
    }
}
